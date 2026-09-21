package cn.timebank.dataexchange.application;

import cn.timebank.dataexchange.application.validation.ImportRuleSet;
import cn.timebank.dataexchange.application.validation.Rfc4180Reader;
import cn.timebank.dataexchange.application.validation.RuleEngine;
import cn.timebank.dataexchange.application.validation.RuleSetSource;
import cn.timebank.dataexchange.domain.Job;
import cn.timebank.file.application.FileStorage;
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
 * re-run the same row validator, so commit trusts nothing cached from
 * validate and the two phases can never disagree.
 *
 * <p>Neither run is checkpoint-resumed any more (issue 146). Validation
 * never was — it is side-effect-free, so a reclaim that re-runs it from
 * scratch produces the identical report. Commit used to be, because it
 * applied rows one transaction at a time and a crash left the ones before
 * it applied; it is now a single transaction in {@link
 * ImportCommitTransaction}, so a crash leaves nothing and there is nothing
 * to resume from. The known mid-chunk double-credit gap that the
 * checkpoint scheme carried went with it.</p>
 */
@Component
class ImportJobRunner {

    private static final Logger log = LoggerFactory.getLogger(ImportJobRunner.class);
    private static final int CHUNK_SIZE = 500;

    /**
     * A commit's lease, in seconds — far longer than validation's 2 minutes,
     * because nothing renews it any more (issue 146). The renewals existed to
     * carry the checkpoint; with the whole commit in one transaction there is
     * no checkpoint to carry, and a lease long enough to outlast the work is
     * simpler than a heartbeat that exists only to say "still going".
     */
    private static final int COMMIT_LEASE_SECONDS = 30 * 60;

    private final JobProgressWriter progressWriter;
    private final ImportCommitTransaction commitTransaction;
    private final HistoricalImportRowValidator historicalRowValidator;
    private final StudentImportRowValidator studentRowValidator;
    private final FileStorage fileStorage;
    private final RuleEngine ruleEngine;
    private final RuleSetSource ruleSetSource;

    ImportJobRunner(JobProgressWriter progressWriter, ImportCommitTransaction commitTransaction,
                     HistoricalImportRowValidator historicalRowValidator,
                     StudentImportRowValidator studentRowValidator, FileStorage fileStorage,
                     RuleEngine ruleEngine, RuleSetSource ruleSetSource) {
        this.progressWriter = progressWriter;
        this.commitTransaction = commitTransaction;
        this.historicalRowValidator = historicalRowValidator;
        this.studentRowValidator = studentRowValidator;
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
    static final String REPORT_LANGUAGE = "en";

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
            // A single bad row ends it here (issue 146). This used to land on
            // VALIDATED regardless, leaving commit to skip what it could not
            // apply — which is how a 200-row roster became 197 students under a
            // success message. The error report is still written and still
            // served by GET /imports/{id}/errors; it is now the end of the road
            // rather than a footnote beside a partial success.
            String status = errors > 0 ? "VALIDATION_FAILED" : "VALIDATED";
            progressWriter.completeValidation(jobId, status, errorRef, total, valid);
            log.info("Import job {} {}: {} rows, {} valid, {} errors",
                    jobId, errors > 0 ? "rejected" : "validated", total, valid, errors);
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

    /**
     * Applies the file, or applies none of it (issue 146).
     *
     * <p>No resume: {@link ImportCommitTransaction} is one transaction, so an
     * interrupted commit left nothing behind and re-running from row zero is
     * the only correct thing to do. The checkpoint machinery this method used
     * to carry existed to recover a half-applied file, and there is no longer
     * such a thing.</p>
     */
    @Async(JOB_EXECUTOR)
    void commitAsync(String jobId, String actorId) {
        Job claimed = progressWriter.claim(jobId, "COMMITTING", COMMIT_LEASE_SECONDS);
        try {
            int committed = commitTransaction.commit(jobId, claimed.getResourceType(),
                    claimed.getSourceFileKey(), actorId);
            log.info("Import job {} committed: {} rows applied", jobId, committed);
        } catch (ImportCommitTransaction.ClaimLost e) {
            // Someone else owns the job now and this run rolled back. Saying so
            // would overwrite their status with a stale one; the log is enough.
            log.warn("Import job {} commit abandoned: {}", jobId, e.getMessage());
        } catch (Exception e) {
            log.error("Import job {} commit failed — nothing was applied", jobId, e);
            progressWriter.fail(jobId, "COMMIT_FAILED", String.valueOf(e.getMessage()));
        }
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        return value.contains(",") || value.contains("\"") ? '"' + value.replace("\"", "\"\"") + '"' : value;
    }
}
