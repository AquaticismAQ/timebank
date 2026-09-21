package cn.timebank.dataexchange.application;

import cn.timebank.account.application.AccountService;
import cn.timebank.account.application.dto.AccountRequests.AdjustmentRequest;
import cn.timebank.dataexchange.domain.Job;
import cn.timebank.dataexchange.infrastructure.JobRepository;
import cn.timebank.file.application.FileStorage;
import cn.timebank.iam.application.UserService;
import cn.timebank.dataexchange.application.validation.RuleEngine;
import cn.timebank.student.application.StudentService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * The commit half of an import, as a single all-or-nothing transaction
 * (issue 146).
 *
 * <p>Its own bean, not a method on {@link ImportJobRunner}: a {@code
 * @Transactional} method called from inside the same class goes through no
 * proxy and silently gets no transaction — the trap this codebase has already
 * hit twice (B0/B1, and again in `ProposalServiceImpl.submit`). Here that
 * failure would be invisible in the worst way, since the code would still work
 * and just stop being atomic.</p>
 *
 * <h2>Why one transaction rather than a row at a time</h2>
 *
 * <p>`08-mlps-level1-security-design-guide.md` §7.1 principle 3 says a file
 * commits whole or not at all. Row-at-a-time with a resume checkpoint was the
 * opposite bargain: it accepted a half-applied file and made it recoverable.
 * Recoverable is not the same as never happening — the operator holding the
 * spreadsheet has no way to see that the database now holds 197 of its 200
 * rows.</p>
 *
 * <p>The size cap makes this affordable: 10 000 rows and 10 MiB per file, once
 * a year for a roster. A long transaction is the right cost for a school-scale
 * import that must not land half-way.</p>
 */
@Component
class ImportCommitTransaction {

    private final JobRepository jobRepository;
    private final HistoricalImportRowValidator historicalRowValidator;
    private final StudentImportRowValidator studentRowValidator;
    private final AccountService accountService;
    private final StudentService studentService;
    private final UserService userService;
    private final FileStorage fileStorage;
    private final RuleEngine ruleEngine;
    private final HistoricalImportWindowService windowService;

    ImportCommitTransaction(JobRepository jobRepository, HistoricalImportRowValidator historicalRowValidator,
                             StudentImportRowValidator studentRowValidator, AccountService accountService,
                             StudentService studentService, UserService userService, FileStorage fileStorage,
                             RuleEngine ruleEngine, HistoricalImportWindowService windowService) {
        this.jobRepository = jobRepository;
        this.historicalRowValidator = historicalRowValidator;
        this.studentRowValidator = studentRowValidator;
        this.accountService = accountService;
        this.studentService = studentService;
        this.userService = userService;
        this.fileStorage = fileStorage;
        this.ruleEngine = ruleEngine;
        this.windowService = windowService;
    }

    /**
     * A row that was valid at validation time and is not valid now. Thrown
     * rather than skipped: the whole point of §7.1's principle is that a file
     * the system cannot apply completely is not applied at all.
     */
    static class RowRejected extends RuntimeException {
        RowRejected(int rowNumber, String code, String message) {
            super("Row " + rowNumber + " is no longer valid (" + code + "): " + message
                    + ". Nothing was imported — correct the file and import it again.");
        }
    }

    /** The job moved under us (reclaimed, retried, already finished) — see {@link #finish}. */
    static class ClaimLost extends RuntimeException {
        ClaimLost(String jobId) {
            super("Import job " + jobId + " was no longer in COMMITTING when its commit finished;"
                    + " another run owns it. This run applied nothing.");
        }
    }

    @Transactional
    public int commit(String jobId, String resourceType, String sourceFileKey, String actorId) {
        int committed = switch (resourceType) {
            case HistoricalImportRowValidator.TYPE -> commitHistorical(sourceFileKey, actorId);
            case StudentImportRowValidator.TYPE -> commitStudents(sourceFileKey, actorId);
            default -> throw new IllegalStateException("Unknown import type: " + resourceType);
        };
        if (HistoricalImportRowValidator.TYPE.equals(resourceType)) {
            // 11-DataImport.md §6.1: the first successful historical import starts
            // the 15-day clock, and nothing can restart it. Inside this transaction
            // deliberately (issue 146) — as a REQUIRES_NEW call it would commit even
            // when the import rolled back, burning a window on an import that never
            // happened.
            windowService.openOnFirstCommit(jobId, actorId);
        }
        finish(jobId, committed);
        return committed;
    }

    private int commitHistorical(String sourceFileKey, String actorId) {
        RuleEngine.Run run = ruleEngine.newRun();
        int committed = 0;
        try (InputStream in = fileStorage.open(sourceFileKey);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            reader.readLine(); // header, already checked at validation
            String line;
            int rowNumber = 0;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                rowNumber++;
                // Re-validated here, never trusting the validate pass's result:
                // the two phases are minutes apart and the reference data can move
                // between them. Unchanged from before — what changed is what a
                // failure does.
                var result = historicalRowValidator.validate(line, run, ImportJobRunner.REPORT_LANGUAGE);
                if (result instanceof HistoricalImportRowValidator.Invalid invalid) {
                    throw new RowRejected(rowNumber, invalid.code(), invalid.message());
                }
                var v = (HistoricalImportRowValidator.Valid) result;
                accountService.adjustForStudent(v.studentId(), v.gradeId(), v.classId(),
                        new AdjustmentRequest(v.rule().convertMinutesToCentiCoins(
                                v.rule().roundToNearestGranularity(v.durationMinutes())),
                                "HISTORICAL_IMPORT", v.description()),
                        actorId);
                committed++;
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return committed;
    }

    private int commitStudents(String sourceFileKey, String actorId) {
        RuleEngine.Run run = ruleEngine.newRun();
        int committed = 0;
        try (InputStream in = fileStorage.open(sourceFileKey);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            reader.readLine(); // header
            String line;
            int rowNumber = 0;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                rowNumber++;
                var result = studentRowValidator.validate(line, run, ImportJobRunner.REPORT_LANGUAGE);
                if (result instanceof StudentImportRowValidator.Invalid invalid) {
                    throw new RowRejected(rowNumber, invalid.code(), invalid.message());
                }
                var v = (StudentImportRowValidator.Valid) result;
                // resolvedLanguage only shapes the returned DTO's localized names, which this
                // fire-and-forget path never reads — fixed default is fine, no request context exists here.
                var created = studentService.create(v.request(), actorId, "zh-CN");
                // 10-StudentIdentityAndOnboarding.md §3.3: the roster row on its own
                // answers nothing about how the student gets in. The account is created
                // here, in the same pass, with no password and PENDING_ACTIVATION status
                // — inert until the activation code on their slip is redeemed (§2.4).
                userService.createPendingStudentAccount(created.id(), created.displayName(),
                        "zh-CN", actorId);
                committed++;
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return committed;
    }

    /**
     * Marks the job COMMITTED in the same transaction as the rows it describes.
     *
     * <p>This used to be a separate {@code REQUIRES_NEW} write after the rows
     * were applied, which left a window: a crash in it meant applied rows under
     * a job still reading COMMITTING — precisely the state the reclaim sweep
     * would re-run and apply a second time.</p>
     *
     * <p>Conditional on the status still being {@code COMMITTING}, so a run
     * whose job was reclaimed or retried under it loses: {@link ClaimLost}
     * rolls its own work back rather than overwriting the run that owns the
     * job now.</p>
     */
    private void finish(String jobId, int committed) {
        int updated = jobRepository.finishCommitIfStillCommitting(jobId, committed, Instant.now());
        if (updated == 0) {
            throw new ClaimLost(jobId);
        }
    }
}
