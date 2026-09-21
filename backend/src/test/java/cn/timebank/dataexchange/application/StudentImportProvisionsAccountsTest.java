package cn.timebank.dataexchange.application;

import cn.timebank.account.application.AccountService;
import cn.timebank.dataexchange.application.validation.RuleEngine;
import cn.timebank.dataexchange.infrastructure.JobRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    private final JobRepository jobRepository = mock(JobRepository.class);
    private final StudentImportRowValidator rowValidator = mock(StudentImportRowValidator.class);
    private final StudentService studentService = mock(StudentService.class);
    private final UserService userService = mock(UserService.class);
    private final FileStorage fileStorage = mock(FileStorage.class);
    private final RuleEngine ruleEngine = mock(RuleEngine.class);

    private ImportCommitTransaction commitTransaction() {
        return new ImportCommitTransaction(jobRepository, mock(HistoricalImportRowValidator.class), rowValidator,
                mock(AccountService.class), studentService, userService, fileStorage, ruleEngine,
                mock(HistoricalImportWindowService.class));
    }

    private void rosterFile(String body) {
        when(fileStorage.open("import_job_1.csv")).thenReturn(new ByteArrayInputStream(
                ("studentNumber,displayName\n" + body).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void committing_a_roster_row_creates_the_student_and_a_pending_account() {
        rosterFile("HKTZ4HKV,Example Student\n");
        CreateStudentRequest request = new CreateStudentRequest("HKTZ4HKV", "Example Student",
                "grd_1", "cls_1", "ay_1", 2031, LocalDate.of(2026, 9, 1));
        when(rowValidator.validate(anyString(), any(), anyString()))
                .thenReturn(new StudentImportRowValidator.Valid(request));
        when(studentService.create(any(), anyString(), anyString())).thenReturn(detail());
        when(jobRepository.finishCommitIfStillCommitting(anyString(), anyInt(), any())).thenReturn(1);

        int committed = commitTransaction().commit("job_1", "students", "import_job_1.csv", "usr_admin");

        assertThat(committed).isEqualTo(1);
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
        verify(jobRepository).finishCommitIfStillCommitting(eq("job_1"), eq(1), any());
    }

    /**
     * Issue 146. This replaces a test about resuming a half-committed file,
     * because a half-committed file is no longer a state the system can reach.
     *
     * <p>The row that fails is the <em>second</em> one deliberately: the first
     * has already been applied by the time the failure is found, so nothing but
     * the transaction rolling back can undo it. Throwing is what makes that
     * happen — a skip would leave student one created and student two missing,
     * under a success message.</p>
     */
    @Test
    void one_bad_row_takes_the_whole_file_down_with_it() {
        rosterFile("HKTZ4HKV,First Student\nZZZZZZZZ,Second Student\n");
        when(rowValidator.validate(anyString(), any(), anyString()))
                .thenReturn(new StudentImportRowValidator.Valid(new CreateStudentRequest("HKTZ4HKV",
                        "First Student", "grd_1", "cls_1", "ay_1", 2031, LocalDate.of(2026, 9, 1))))
                .thenReturn(new StudentImportRowValidator.Invalid("studentNumber", "DUPLICATE_STUDENT_NUMBER",
                        "This student number already exists."));
        when(studentService.create(any(), anyString(), anyString())).thenReturn(detail());

        assertThatThrownBy(() -> commitTransaction().commit("job_1", "students", "import_job_1.csv", "usr_admin"))
                .isInstanceOf(ImportCommitTransaction.RowRejected.class)
                .hasMessageContaining("Row 2")
                .hasMessageContaining("DUPLICATE_STUDENT_NUMBER")
                // The operator has to be told the file did not land, or they
                // will go looking for the rows that did.
                .hasMessageContaining("Nothing was imported");

        // The job is never marked committed — the exception is what rolls the
        // first row back, and a status write here would survive that rollback
        // only if it ran in its own transaction, which is the bug this fix removed.
        verify(jobRepository, never()).finishCommitIfStillCommitting(anyString(), anyInt(), any());
    }

    /**
     * The job moved on under this run — reclaimed as failed after its lease
     * expired, or retried by an operator. Its own work must come off, rather
     * than both runs writing a finished status over each other.
     */
    @Test
    void a_run_that_no_longer_owns_the_job_rolls_itself_back() {
        rosterFile("HKTZ4HKV,Example Student\n");
        when(rowValidator.validate(anyString(), any(), anyString()))
                .thenReturn(new StudentImportRowValidator.Valid(new CreateStudentRequest("HKTZ4HKV",
                        "Example Student", "grd_1", "cls_1", "ay_1", 2031, LocalDate.of(2026, 9, 1))));
        when(studentService.create(any(), anyString(), anyString())).thenReturn(detail());
        when(jobRepository.finishCommitIfStillCommitting(anyString(), anyInt(), any())).thenReturn(0);

        assertThatThrownBy(() -> commitTransaction().commit("job_1", "students", "import_job_1.csv", "usr_admin"))
                .isInstanceOf(ImportCommitTransaction.ClaimLost.class);
    }

    private StudentDetailDto detail() {
        return new StudentDetailDto(STUDENT_ID, "HKTZ4HKV", "Example Student", "grd_1", 6, "cls_1",
                "Class 1", "ACTIVE", 2031, LocalDate.of(2026, 9, 1), 1L, Instant.now(), Instant.now());
    }
}
