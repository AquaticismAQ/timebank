package cn.timebank.dataexchange.application;

import cn.timebank.account.application.AccountService;
import cn.timebank.dataexchange.application.validation.RuleEngine;
import cn.timebank.dataexchange.application.validation.RuleSetSource;
import cn.timebank.dataexchange.domain.Job;
import cn.timebank.dataexchange.domain.JobKind;
import cn.timebank.file.application.FileStorage;
import cn.timebank.iam.application.UserService;
import cn.timebank.student.application.StudentService;
import cn.timebank.student.application.dto.StudentDetailDto;
import cn.timebank.student.application.dto.StudentRequests.CreateStudentRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 10-StudentIdentityAndOnboarding.md §3.3: the roster import's commit stage
 * has to create the login account too, not only the student row — "nothing
 * currently answers the question of how a freshly imported student gets into
 * their account for the first time" is exactly the gap this closes.
 *
 * <p>Asserted at the commit run rather than at the service it calls, because
 * the failure mode this guards against is the wiring being absent: a commit
 * that creates students and no accounts passes every test about accounts
 * while leaving the whole import unusable.</p>
 */
class StudentImportProvisionsAccountsTest {

    private static final String STUDENT_ID = "std_0000000000000000000000001";

    private final JobProgressWriter progressWriter = mock(JobProgressWriter.class);
    private final StudentImportRowValidator rowValidator = mock(StudentImportRowValidator.class);
    private final StudentService studentService = mock(StudentService.class);
    private final UserService userService = mock(UserService.class);
    private final FileStorage fileStorage = mock(FileStorage.class);
    private final RuleEngine ruleEngine = mock(RuleEngine.class);

    private ImportJobRunner runner() {
        return new ImportJobRunner(progressWriter, mock(HistoricalImportRowValidator.class), rowValidator,
                mock(AccountService.class), studentService, userService, fileStorage, ruleEngine,
                mock(RuleSetSource.class), mock(HistoricalImportWindowService.class));
    }

    private Job queuedStudentImport() {
        Job job = new Job("job_1", JobKind.IMPORT, "students", "VALIDATED", "usr_admin", Instant.now());
        job.setSourceFileKey("import_job_1.csv");
        return job;
    }

    @Test
    void committing_a_roster_row_creates_the_student_and_a_pending_account() {
        Job job = queuedStudentImport();
        when(progressWriter.claim("job_1", "COMMITTING")).thenReturn(job);
        when(fileStorage.open("import_job_1.csv")).thenReturn(new ByteArrayInputStream(
                "studentNumber,displayName\nHKTZ4HKV,Example Student\n".getBytes(StandardCharsets.UTF_8)));
        CreateStudentRequest request = new CreateStudentRequest("HKTZ4HKV", "Example Student",
                "grd_1", "cls_1", "ay_1", 2031, LocalDate.of(2026, 9, 1));
        when(rowValidator.validate(anyString(), any(), anyString()))
                .thenReturn(new StudentImportRowValidator.Valid(request));
        when(studentService.create(any(), anyString(), anyString())).thenReturn(detail());

        runner().commitAsync("job_1", "usr_admin");

        verify(studentService).create(eq(request), eq("usr_admin"), anyString());
        ArgumentCaptor<String> studentId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> displayName = ArgumentCaptor.forClass(String.class);
        // The account is provisioned for the student the commit just created,
        // keyed off the created row's own id rather than anything re-derived
        // from the CSV.
        verify(userService).createPendingStudentAccount(studentId.capture(), displayName.capture(),
                anyString(), eq("usr_admin"));
        assertThat(studentId.getValue()).isEqualTo(STUDENT_ID);
        assertThat(displayName.getValue()).isEqualTo("Example Student");
        verify(progressWriter).succeed(eq("job_1"), eq("COMMITTED"), any(), eq(1), eq(1));
    }

    @Test
    void an_already_committed_row_is_not_provisioned_again_on_a_resumed_commit() {
        Job job = queuedStudentImport();
        job.setCheckpointOffset(1); // the one data row was already committed before the crash
        when(progressWriter.claim("job_1", "COMMITTING")).thenReturn(job);
        when(fileStorage.open("import_job_1.csv")).thenReturn(new ByteArrayInputStream(
                "studentNumber,displayName\nHKTZ4HKV,Example Student\n".getBytes(StandardCharsets.UTF_8)));
        when(rowValidator.validate(anyString(), any(), anyString()))
                .thenReturn(new StudentImportRowValidator.Valid(new CreateStudentRequest("HKTZ4HKV",
                        "Example Student", "grd_1", "cls_1", "ay_1", 2031, LocalDate.of(2026, 9, 1))));

        runner().commitAsync("job_1", "usr_admin");

        verify(studentService, org.mockito.Mockito.never()).create(any(), anyString(), anyString());
        verify(userService, org.mockito.Mockito.never())
                .createPendingStudentAccount(anyString(), anyString(), anyString(), anyString());
    }

    private StudentDetailDto detail() {
        return new StudentDetailDto(STUDENT_ID, "HKTZ4HKV", "Example Student", "grd_1", 6, "cls_1",
                "Class 1", "ACTIVE", 2031, LocalDate.of(2026, 9, 1), 1L, Instant.now(), Instant.now());
    }
}
