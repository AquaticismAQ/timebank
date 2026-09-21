package cn.timebank.dataexchange.application;

import cn.timebank.account.application.AccountService;
import cn.timebank.dataexchange.application.validation.RuleEngine;
import cn.timebank.dataexchange.infrastructure.JobRepository;
import cn.timebank.file.application.FileStorage;
import cn.timebank.iam.application.UserService;
import cn.timebank.rules.application.dto.EffectiveRule;
import cn.timebank.student.application.StudentService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 12-DataImport.md §6.1: the clock is started by the first historical import
 * that actually commits. Asserted at the commit run itself, for the same reason
 * {@code StudentImportProvisionsAccountsTest} does — the failure this guards
 * against is the call being absent, and an import that credits the ledger while
 * opening no window passes every other test in this module while leaving the
 * feature open forever.
 */
class HistoricalImportOpensWindowTest {

    private final JobRepository jobRepository = mock(JobRepository.class);
    private final HistoricalImportRowValidator historicalRowValidator = mock(HistoricalImportRowValidator.class);
    private final StudentImportRowValidator studentRowValidator = mock(StudentImportRowValidator.class);
    private final HistoricalImportWindowService windowService = mock(HistoricalImportWindowService.class);
    private final AccountService accountService = mock(AccountService.class);
    private final StudentService studentService = mock(StudentService.class);
    private final UserService userService = mock(UserService.class);
    private final FileStorage fileStorage = mock(FileStorage.class);
    private final RuleEngine ruleEngine = mock(RuleEngine.class);

    private ImportCommitTransaction commitTransaction() {
        when(jobRepository.finishCommitIfStillCommitting(anyString(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(1);
        return new ImportCommitTransaction(jobRepository, historicalRowValidator, studentRowValidator,
                accountService, studentService, userService, fileStorage, ruleEngine, windowService,
                mock(cn.timebank.audit.application.AuditService.class));
    }

    @Test
    void a_committed_historical_import_opens_the_window_inside_the_commit_transaction() {
        when(fileStorage.open("import_job_1.csv")).thenReturn(new ByteArrayInputStream(
                "studentNumber,serviceDate,durationMinutes,description\nHKTZ4HKV,2024-10-01,60,Ledger\n"
                        .getBytes(StandardCharsets.UTF_8)));
        when(historicalRowValidator.validate(anyString(), any(), anyString())).thenReturn(validRow());

        commitTransaction().commit("job_1", "serviceRecordsHistorical", "import_job_1.csv", "sha-1", "usr_admin");

        // Both happen inside the one transaction now (issue 146), so ordering
        // between them no longer carries the guarantee — atomicity does. What
        // still has to hold is that the call happens at all.
        verify(windowService).openOnFirstCommit("job_1", "usr_admin");
    }

    /** A school enrols students every year, so roster import is outside the window and must not touch it. */
    @Test
    void a_roster_import_never_opens_the_window() {
        when(fileStorage.open("import_job_1.csv")).thenReturn(new ByteArrayInputStream(
                "studentNumber,displayName\nHKTZ4HKV,Example Student\n".getBytes(StandardCharsets.UTF_8)));
        when(studentRowValidator.validate(anyString(), any(), anyString()))
                .thenReturn(new StudentImportRowValidator.Valid(new cn.timebank.student.application.dto.StudentRequests
                        .CreateStudentRequest("HKTZ4HKV", "Example Student", "grd_1", "cls_1", "ay_1", 2031,
                        LocalDate.of(2026, 9, 1))));
        when(studentService.create(any(), anyString(), anyString()))
                .thenReturn(mock(cn.timebank.student.application.dto.StudentDetailDto.class));

        commitTransaction().commit("job_1", "students", "import_job_1.csv", "sha-1", "usr_admin");

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
