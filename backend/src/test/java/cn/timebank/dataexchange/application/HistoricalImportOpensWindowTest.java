package cn.timebank.dataexchange.application;

import cn.timebank.account.application.AccountService;
import cn.timebank.dataexchange.application.validation.RuleEngine;
import cn.timebank.dataexchange.application.validation.RuleSetSource;
import cn.timebank.dataexchange.domain.Job;
import cn.timebank.dataexchange.domain.JobKind;
import cn.timebank.file.application.FileStorage;
import cn.timebank.iam.application.UserService;
import cn.timebank.rules.application.dto.EffectiveRule;
import cn.timebank.student.application.StudentService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 11-DataImport.md §6.1: the clock is started by the first historical import
 * that actually commits. Asserted at the commit run itself, for the same reason
 * {@code StudentImportProvisionsAccountsTest} does — the failure this guards
 * against is the call being absent, and an import that credits the ledger while
 * opening no window passes every other test in this module while leaving the
 * feature open forever.
 */
class HistoricalImportOpensWindowTest {

    private final JobProgressWriter progressWriter = mock(JobProgressWriter.class);
    private final HistoricalImportRowValidator historicalRowValidator = mock(HistoricalImportRowValidator.class);
    private final StudentImportRowValidator studentRowValidator = mock(StudentImportRowValidator.class);
    private final HistoricalImportWindowService windowService = mock(HistoricalImportWindowService.class);
    private final AccountService accountService = mock(AccountService.class);
    private final StudentService studentService = mock(StudentService.class);
    private final UserService userService = mock(UserService.class);
    private final FileStorage fileStorage = mock(FileStorage.class);
    private final RuleEngine ruleEngine = mock(RuleEngine.class);

    private ImportJobRunner runner() {
        return new ImportJobRunner(progressWriter, historicalRowValidator, studentRowValidator, accountService,
                studentService, userService, fileStorage, ruleEngine, mock(RuleSetSource.class), windowService);
    }

    private Job job(String resourceType) {
        Job job = new Job("job_1", JobKind.IMPORT, resourceType, "VALIDATED", "usr_admin", Instant.now());
        job.setSourceFileKey("import_job_1.csv");
        return job;
    }

    @Test
    void a_committed_historical_import_opens_the_window_before_the_job_is_marked_committed() {
        when(progressWriter.claim("job_1", "COMMITTING")).thenReturn(job("serviceRecordsHistorical"));
        when(fileStorage.open("import_job_1.csv")).thenReturn(new ByteArrayInputStream(
                "studentNumber,serviceDate,durationMinutes,description\nHKTZ4HKV,2024-10-01,60,Ledger\n"
                        .getBytes(StandardCharsets.UTF_8)));
        when(historicalRowValidator.validate(anyString(), any(), anyString())).thenReturn(validRow());

        runner().commitAsync("job_1", "usr_admin");

        // Order matters: a job that reads COMMITTED with no window row behind it
        // is a state the two can never be allowed to reach.
        var order = inOrder(windowService, progressWriter);
        order.verify(windowService).openOnFirstCommit("job_1", "usr_admin");
        order.verify(progressWriter).succeed(eq("job_1"), eq("COMMITTED"), any(), eq(1), eq(1));
    }

    /** A school enrols students every year, so roster import is outside the window and must not touch it. */
    @Test
    void a_roster_import_never_opens_the_window() {
        when(progressWriter.claim("job_1", "COMMITTING")).thenReturn(job("students"));
        when(fileStorage.open("import_job_1.csv")).thenReturn(new ByteArrayInputStream(
                "studentNumber,displayName\nHKTZ4HKV,Example Student\n".getBytes(StandardCharsets.UTF_8)));
        when(studentRowValidator.validate(anyString(), any(), anyString()))
                .thenReturn(new StudentImportRowValidator.Invalid("studentNumber", "STUDENT_NOT_FOUND", "nope"));

        runner().commitAsync("job_1", "usr_admin");

        verify(windowService, never()).openOnFirstCommit(anyString(), anyString());
    }

    private HistoricalImportRowValidator.Valid validRow() {
        return new HistoricalImportRowValidator.Valid("std_1", "grd_1", "cls_1", LocalDate.of(2024, 10, 1),
                60, "Ledger", rule());
    }

    /** Real arithmetic rather than a mock: 60 minutes at 30-minute granularity is one coin unit, which is all this test needs the credit to be. */
    private EffectiveRule rule() {
        return new EffectiveRule("rv_1", 30, 60, 100, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
