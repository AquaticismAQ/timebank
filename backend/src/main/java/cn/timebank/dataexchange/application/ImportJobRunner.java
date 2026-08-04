package cn.timebank.dataexchange.application;

import cn.timebank.account.application.AccountService;
import cn.timebank.account.application.dto.AccountRequests.AdjustmentRequest;
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

import static cn.timebank.dataexchange.application.JobExecutorConfig.JOB_EXECUTOR;

/**
 * B5.10: the validate and commit runs. Both stream the source file line by
 * line (bounded heap regardless of file size, Design.md §16.2) and re-run
 * the same {@link HistoricalImportRowValidator} — commit trusts nothing
 * cached from validate, so the two phases can never disagree.
 *
 * Only {@link #commitAsync} needs true checkpoint-resume: it is the one
 * run with an external side effect (crediting the ledger), so restarting
 * it from row zero after a crash would double-credit already-committed
 * rows. {@link #validateAsync} is side-effect-free — a reclaim that
 * re-runs it from scratch produces the identical report, so it is
 * deliberately *not* checkpoint-resumed, only re-launched.
 *
 * <p><b>Known gap, named rather than hidden:</b> the checkpoint is
 * persisted at chunk boundaries ({@code CHUNK_SIZE} rows) and once more
 * unconditionally at the end of a successful run — not after every row.
 * A crash exactly mid-chunk therefore resumes from the *previous* chunk
 * boundary and re-credits the rows already applied within the
 * in-flight chunk. Closing this fully would mean making each row's
 * {@code AccountService} write and its checkpoint update atomic
 * together, which {@code AccountService}'s per-call transaction
 * ownership does not currently support. Reducing {@code CHUNK_SIZE}
 * narrows the exposure window; it does not remove it.</p>
 */
@Component
class ImportJobRunner {

    private static final Logger log = LoggerFactory.getLogger(ImportJobRunner.class);
    private static final int CHUNK_SIZE = 500;

    private final JobProgressWriter progressWriter;
    private final HistoricalImportRowValidator rowValidator;
    private final AccountService accountService;
    private final FileStorage fileStorage;

    ImportJobRunner(JobProgressWriter progressWriter, HistoricalImportRowValidator rowValidator,
                     AccountService accountService, FileStorage fileStorage) {
        this.progressWriter = progressWriter;
        this.rowValidator = rowValidator;
        this.accountService = accountService;
        this.fileStorage = fileStorage;
    }

    @Async(JOB_EXECUTOR)
    void validateAsync(String jobId) {
        Job claimed = progressWriter.claim(jobId, "VALIDATING");
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

                reader.readLine(); // header
                String line;
                int rowNumber = 0;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    rowNumber++;
                    total++;
                    var result = rowValidator.validate(line);
                    if (result instanceof HistoricalImportRowValidator.Valid) {
                        valid++;
                    } else if (result instanceof HistoricalImportRowValidator.Invalid invalid) {
                        errors++;
                        errorWriter.write(rowNumber + "," + invalid.field() + "," + invalid.code() + "," + escapeCsv(invalid.message()));
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
            completeValidation(jobId, "VALIDATED", errorRef, total, valid);
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
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                rowNumber++;
                var result = rowValidator.validate(line);
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

    private void completeValidation(String jobId, String status, String errorRef, int total, int valid) {
        progressWriter.completeValidation(jobId, status, errorRef, total, valid);
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        return value.contains(",") || value.contains("\"") ? '"' + value.replace("\"", "\"\"") + '"' : value;
    }
}
