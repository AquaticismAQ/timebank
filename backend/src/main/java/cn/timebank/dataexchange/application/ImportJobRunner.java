package cn.timebank.dataexchange.application;

import cn.timebank.account.application.AccountService;
import cn.timebank.account.application.dto.AccountRequests.AdjustmentRequest;
import cn.timebank.dataexchange.application.validation.ImportRuleSet;
import cn.timebank.dataexchange.application.validation.Rfc4180Reader;
import cn.timebank.dataexchange.application.validation.RuleEngine;
import cn.timebank.dataexchange.application.validation.RuleSetSource;
import cn.timebank.dataexchange.domain.Job;
import cn.timebank.file.application.FileStorage;
import cn.timebank.student.application.StudentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

import static cn.timebank.dataexchange.application.JobExecutorConfig.JOB_EXECUTOR;

/**
 * B5.10/B5.10b: the validate and commit runs, for both import types this
 * module supports ({@code serviceRecordsHistorical} and {@code students} —
 * {@link Job#getResourceType()} says which). Both stream the source file
 * line by line (bounded heap regardless of file size, Design.md §16.2) and
 * re-run the same row validator commit trusts nothing cached from
 * validate, so the two phases can never disagree.
 *
 * Only {@link #commitAsync} needs true checkpoint-resume: it is the one
 * run with an external side effect (crediting the ledger, or creating a
 * student), so restarting it from row zero after a crash would double-
 * apply already-committed rows. {@link #validateAsync} is side-effect-free
 * — a reclaim that re-runs it from scratch produces the identical report,
 * so it is deliberately *not* checkpoint-resumed, only re-launched.
 *
 * <p><b>Known gap, named rather than hidden</b> (historical import only —
 * see {@link #commitHistorical}): the checkpoint is persisted at chunk
 * boundaries ({@code CHUNK_SIZE} rows) and once more unconditionally at
 * the end of a successful run — not after every row. A crash exactly
 * mid-chunk therefore resumes from the *previous* chunk boundary and
 * re-credits the rows already applied within the in-flight chunk. Closing
 * this fully would mean making each row's {@code AccountService} write and
 * its checkpoint update atomic together, which {@code AccountService}'s
 * per-call transaction ownership does not currently support. Reducing
 * {@code CHUNK_SIZE} narrows the exposure window; it does not remove it.
 * {@link #commitStudents} does not share this exposure the same way — a
 * re-committed row is caught by {@code studentNumberExists} and reported
 * as a per-row error rather than silently duplicated, since student
 * creation (unlike a ledger credit) is naturally idempotent-checkable by
 * its own unique key.</p>
 */
@Component
class ImportJobRunner {

    private static final Logger log = LoggerFactory.getLogger(ImportJobRunner.class);
    private static final int CHUNK_SIZE = 500;

    private final JobProgressWriter progressWriter;
    private final HistoricalImportRowValidator historicalRowValidator;
    private final StudentImportRowValidator studentRowValidator;
    private final AccountService accountService;
    private final StudentService studentService;
    private final FileStorage fileStorage;
    private final RuleEngine ruleEngine;
    private final RuleSetSource ruleSetSource;

    ImportJobRunner(JobProgressWriter progressWriter, HistoricalImportRowValidator historicalRowValidator,
                     StudentImportRowValidator studentRowValidator, AccountService accountService,
                     StudentService studentService, FileStorage fileStorage, RuleEngine ruleEngine,
                     RuleSetSource ruleSetSource) {
        this.progressWriter = progressWriter;
        this.historicalRowValidator = historicalRowValidator;
        this.studentRowValidator = studentRowValidator;
        this.accountService = accountService;
        this.studentService = studentService;
        this.fileStorage = fileStorage;
        this.ruleEngine = ruleEngine;
        this.ruleSetSource = ruleSetSource;
    }

    /**
     * Columns are matched by header name now, not by position (plan §3.5). A
     * file whose header does not match the rule set is rejected outright with a
     * diff naming what is missing and what is unexpected, rather than being
     * parsed positionally into the wrong columns — which is how a roster could
     * previously be imported with names in the grade column and no error at all.
     *
     * @return null when the header is acceptable, otherwise the reason
     */
    private String headerProblem(ImportRuleSet ruleSet, String headerLine) {
        if (headerLine == null) {
            return "The file is empty — a header row naming the columns is required.";
        }
        List<String> actual;
        try {
            actual = Rfc4180Reader.parseLine(headerLine).stream().map(String::trim).toList();
        } catch (Rfc4180Reader.MalformedCsvException e) {
            return "The header row is not valid CSV: " + e.getMessage();
        }
        List<String> expected = ruleSet.columnNames();
        if (actual.equals(expected)) {
            return null;
        }
        List<String> missing = expected.stream().filter(c -> !actual.contains(c)).toList();
        List<String> unexpected = actual.stream().filter(c -> !expected.contains(c)).toList();
        if (missing.isEmpty() && unexpected.isEmpty()) {
            return "Columns are in the wrong order. Expected: " + String.join(", ", expected);
        }
        StringBuilder sb = new StringBuilder("Header does not match the template.");
        if (!missing.isEmpty()) {
            sb.append(" Missing: ").append(String.join(", ", missing)).append('.');
        }
        if (!unexpected.isEmpty()) {
            sb.append(" Unexpected: ").append(String.join(", ", unexpected)).append('.');
        }
        return sb.toString();
    }

    /**
     * Import errors are reported to whoever uploaded the file, and this runs on
     * a background executor with no request context to read a language from, so
     * the report is produced in English. The stable machine `code` on every row
     * is what a localized UI should render from; the message is the fallback for
     * a human reading the raw CSV.
     */
    private static final String REPORT_LANGUAGE = "en";

    private record LineOutcome(boolean valid, String field, String code, String message) {
        static final LineOutcome VALID = new LineOutcome(true, null, null, null);
    }

    /**
     * One fresh per-run line-checker per resource type. Each closes over its own
     * {@link RuleEngine.Run}, which carries the file-scope state (in-file
     * uniqueness) for this pass only — the same role the old hand-rolled
     * `seenInFile` set played, now owned by the engine.
     */
    private Function<String, LineOutcome> lineCheckerFor(String resourceType) {
        return switch (resourceType) {
            case HistoricalImportRowValidator.TYPE -> {
                RuleEngine.Run run = ruleEngine.newRun();
                yield line -> {
                    var result = historicalRowValidator.validate(line, run, REPORT_LANGUAGE);
                    if (result instanceof HistoricalImportRowValidator.Invalid invalid) {
                        return new LineOutcome(false, invalid.field(), invalid.code(), invalid.message());
                    }
                    return LineOutcome.VALID;
                };
            }
            case StudentImportRowValidator.TYPE -> {
                RuleEngine.Run run = ruleEngine.newRun();
                yield line -> {
                    var result = studentRowValidator.validate(line, run, REPORT_LANGUAGE);
                    if (result instanceof StudentImportRowValidator.Invalid invalid) {
                        return new LineOutcome(false, invalid.field(), invalid.code(), invalid.message());
                    }
                    return LineOutcome.VALID;
                };
            }
            default -> line -> new LineOutcome(false, "row", "UNKNOWN_IMPORT_TYPE", "Unknown import type: " + resourceType);
        };
    }

    @Async(JOB_EXECUTOR)
    void validateAsync(String jobId) {
        Job claimed = progressWriter.claim(jobId, "VALIDATING");
        Function<String, LineOutcome> lineChecker = lineCheckerFor(claimed.getResourceType());
        Path errorTemp;
        try {
            errorTemp = Files.createTempFile("import-errors-" + jobId, ".csv");
        } catch (IOException e) {
            progressWriter.fail(jobId, "VALIDATION_FAILED", "Could not create error report file: " + e.getMessage());
            return;
        }
        try {
            int total = 0;
            int valid = 0;
            int errors = 0;
            // The writer must be fully closed (and its buffer flushed to disk)
            // before errorTemp is reopened for reading below — reading it from
            // a second stream while still inside this try-with-resources block
            // captured a stale, effectively-empty file (BufferedWriter's
            // buffer had not been flushed yet), the bug a live run caught.
            try (BufferedWriter errorWriter = Files.newBufferedWriter(errorTemp);
                 InputStream in = fileStorage.open(claimed.getSourceFileKey());
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                errorWriter.write("rowNumber,field,code,message");
                errorWriter.newLine();

                ImportRuleSet ruleSet = ruleSetSource.forType(claimed.getResourceType());
                String headerProblem = headerProblem(ruleSet, reader.readLine());
                if (headerProblem != null) {
                    errorWriter.write("0,header,HEADER_MISMATCH," + escapeCsv(headerProblem));
                    errorWriter.newLine();
                    errors = 1;
                }

                String line;
                int rowNumber = 0;
                while (headerProblem == null && (line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    rowNumber++;
                    if (rowNumber > ruleSet.maxRows()) {
                        errorWriter.write(rowNumber + ",row,TOO_MANY_ROWS,"
                                + escapeCsv("This import type accepts at most " + ruleSet.maxRows() + " rows."));
                        errorWriter.newLine();
                        errors++;
                        break;
                    }
                    total++;
                    LineOutcome outcome = lineChecker.apply(line);
                    if (outcome.valid()) {
                        valid++;
                    } else {
                        errors++;
                        errorWriter.write(rowNumber + "," + outcome.field() + "," + outcome.code() + "," + escapeCsv(outcome.message()));
                        errorWriter.newLine();
                    }
                    if (rowNumber % CHUNK_SIZE == 0) {
                        progressWriter.renewLeaseAndProgress(jobId, total, rowNumber);
                    }
                }
            }

            String errorRef = null;
            if (errors > 0) {
                errorRef = "import_errors_" + jobId + ".csv";
                try (var errIn = new FileInputStream(errorTemp.toFile())) {
                    fileStorage.store(errorRef, errIn);
                }
            }
            progressWriter.completeValidation(jobId, "VALIDATED", errorRef, total, valid);
            log.info("Import job {} validated: {} rows, {} valid, {} errors", jobId, total, valid, errors);
        } catch (Exception e) {
            log.error("Import job {} validation failed", jobId, e);
            progressWriter.fail(jobId, "VALIDATION_FAILED", String.valueOf(e.getMessage()));
        } finally {
            try {
                Files.deleteIfExists(errorTemp);
            } catch (Exception ignored) {
            }
        }
    }

    /** Resumes from {@code checkpointOffset} raw lines already processed — the correctness-critical path (D5's "survives a deploy mid-job"). */
    @Async(JOB_EXECUTOR)
    void commitAsync(String jobId, String actorId) {
        Job claimed = progressWriter.claim(jobId, "COMMITTING");
        switch (claimed.getResourceType()) {
            case "serviceRecordsHistorical" -> commitHistorical(claimed, actorId);
            case "students" -> commitStudents(claimed, actorId);
            default -> {
                log.error("Import job {} commit failed: unknown resource type {}", jobId, claimed.getResourceType());
                progressWriter.fail(jobId, "COMMIT_FAILED", "Unknown import type: " + claimed.getResourceType());
            }
        }
    }

    private void commitHistorical(Job claimed, String actorId) {
        String jobId = claimed.getId();
        int resumeFromLine = claimed.getCheckpointOffset();
        try (InputStream in = fileStorage.open(claimed.getSourceFileKey());
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            reader.readLine(); // header
            String line;
            int rowNumber = 0;
            // Deliberately NOT seeded from job.validRows: that column means
            // something different depending on which phase last wrote it —
            // "how many rows are valid" after validate, vs. "how many have
            // been committed so far" after a partial commit — and reading it
            // here misread the former as the latter, over-counting by
            // exactly the valid-row count on every fresh (non-resumed)
            // commit. A real bug, caught live (see 00-Status.md §18): the
            // ledger was correct either way (crediting is still governed
            // solely by the skip-below), but the reported committed count
            // was wrong. Fixed by recomputing it honestly: every row is
            // re-validated even when skipped, so `committed` always equals
            // the true count of valid rows seen so far, in this run or a
            // resumed one, with no dependency on a value another phase wrote.
            int committed = 0;
            RuleEngine.Run run = ruleEngine.newRun();
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                rowNumber++;
                var result = historicalRowValidator.validate(line, run, REPORT_LANGUAGE);
                boolean alreadyProcessed = rowNumber <= resumeFromLine;
                if (result instanceof HistoricalImportRowValidator.Valid v) {
                    if (!alreadyProcessed) {
                        accountService.adjustForStudent(v.studentId(), v.gradeId(), v.classId(),
                                new AdjustmentRequest(v.rule().convertMinutesToCentiCoins(v.rule().roundToNearestGranularity(v.durationMinutes())),
                                        "HISTORICAL_IMPORT", v.description()),
                                actorId);
                    }
                    committed++;
                }
                if (!alreadyProcessed && rowNumber % CHUNK_SIZE == 0) {
                    progressWriter.renewCommitProgress(jobId, rowNumber, rowNumber, committed);
                }
            }
            // Unconditional final checkpoint write, not just at chunk
            // boundaries — a file whose row count isn't a multiple of
            // CHUNK_SIZE (or is smaller than one chunk entirely) would
            // otherwise finish with a stale checkpointOffset, making a later
            // reclaim of this already-COMMITTED job re-run and double-credit
            // every row again. A real bug, caught live: see 00-Status.md §18.
            progressWriter.renewCommitProgress(jobId, rowNumber, rowNumber, committed);
            progressWriter.succeed(jobId, "COMMITTED", null, rowNumber, committed);
            log.info("Import job {} committed: {} rows credited (resumed from line {})", jobId, committed, resumeFromLine);
        } catch (Exception e) {
            log.error("Import job {} commit failed", jobId, e);
            progressWriter.fail(jobId, "COMMIT_FAILED", String.valueOf(e.getMessage()));
        }
    }

    private void commitStudents(Job claimed, String actorId) {
        String jobId = claimed.getId();
        int resumeFromLine = claimed.getCheckpointOffset();
        RuleEngine.Run run = ruleEngine.newRun();
        try (InputStream in = fileStorage.open(claimed.getSourceFileKey());
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            reader.readLine(); // header
            String line;
            int rowNumber = 0;
            int committed = 0;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                rowNumber++;
                var result = studentRowValidator.validate(line, run, REPORT_LANGUAGE);
                boolean alreadyProcessed = rowNumber <= resumeFromLine;
                if (result instanceof StudentImportRowValidator.Valid v) {
                    if (!alreadyProcessed) {
                        // resolvedLanguage only shapes the returned DTO's localized names, which this
                        // fire-and-forget path never reads — fixed default is fine, no request context exists here.
                        studentService.create(v.request(), actorId, "zh-CN");
                    }
                    committed++;
                }
                if (!alreadyProcessed && rowNumber % CHUNK_SIZE == 0) {
                    progressWriter.renewCommitProgress(jobId, rowNumber, rowNumber, committed);
                }
            }
            progressWriter.renewCommitProgress(jobId, rowNumber, rowNumber, committed);
            progressWriter.succeed(jobId, "COMMITTED", null, rowNumber, committed);
            log.info("Import job {} committed: {} students created (resumed from line {})", jobId, committed, resumeFromLine);
        } catch (Exception e) {
            log.error("Import job {} commit failed", jobId, e);
            progressWriter.fail(jobId, "COMMIT_FAILED", String.valueOf(e.getMessage()));
        }
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        return value.contains(",") || value.contains("\"") ? '"' + value.replace("\"", "\"\"") + '"' : value;
    }
}
