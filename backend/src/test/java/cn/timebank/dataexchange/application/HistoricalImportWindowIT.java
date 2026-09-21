package cn.timebank.dataexchange.application;

import cn.timebank.account.application.AccountService;
import cn.timebank.common.error.ApiException;
import cn.timebank.dataexchange.application.dto.HistoricalImportWindowDto;
import cn.timebank.dataexchange.application.dto.JobDto;
import cn.timebank.dataexchange.domain.HistoricalImportWindow;
import cn.timebank.dataexchange.infrastructure.HistoricalImportWindowRepository;
import cn.timebank.dataexchange.infrastructure.JobRepository;
import cn.timebank.student.application.StudentService;
import cn.timebank.student.application.dto.StudentDetailDto;
import cn.timebank.student.application.dto.StudentRequests.CreateStudentRequest;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Design/*&#47;12-DataImport.md §6 against real MySQL.
 *
 * <p>The load-bearing part of this class is that it <b>crosses the expiry for
 * real</b>. A test that only ever runs inside a fresh 15-day window cannot
 * tell a working window from one that always allows — so every refusal here is
 * asserted after winding the stored {@code expires_at} into the past with a
 * direct SQL update, which is the only way to reach that state without waiting
 * a fortnight. Each refusal is paired with a roster import in the same state:
 * "historical import is refused" only means something if "everything is
 * broken" has been ruled out.</p>
 */
class HistoricalImportWindowIT extends AbstractIntegrationTest {

    @Autowired
    private ImportService importService;
    @Autowired
    private HistoricalImportWindowService windowService;
    @Autowired
    private HistoricalImportWindowRepository windowRepository;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private StudentService studentService;
    @Autowired
    private AccountService accountService;
    @Autowired
    private JdbcTemplate jdbc;

    private static final AtomicInteger SEQ = new AtomicInteger();

    /**
     * The window is deliberately a one-way door, so nothing in the application
     * can put it back — which means the test class has to, or the first method
     * to expire it closes historical import for every method that runs after
     * it. (That is not hypothetical: it is exactly what happened on the first
     * run of this class, which is itself a small proof that the closure is
     * real and sticky rather than per-request.) A direct DELETE, not a service
     * call, because no service call for this exists on purpose.
     */
    @BeforeEach
    void resetTheWindow() {
        jdbc.update("DELETE FROM historical_import_window");
    }

    @Test
    void beforeAnyHistoricalImportTheWindowIsUnopenedAndTheConfiguredLengthIsVisible() {
        HistoricalImportWindowDto state = windowService.state();

        assertThat(state.state()).isEqualTo(HistoricalImportWindowDto.NOT_OPENED);
        assertThat(state.expiresAt()).isNull();
        assertThat(state.secondsRemaining()).isNull();
        // The screen's warning quotes this number before anything has happened,
        // so an unopened window still has to answer "how long will I get".
        assertThat(state.windowDays()).isEqualTo(15);
    }

    @Test
    void theFirstImportOpensAFifteenDayWindowAndASecondImportDoesNotExtendIt() {
        commitHistoricalImport();

        HistoricalImportWindow opened = window();
        assertThat(Duration.between(opened.getOpenedAt(), opened.getExpiresAt()).toDays()).isEqualTo(15);
        assertThat(opened.getWindowDays()).isEqualTo(15);
        assertThat(windowService.state().state()).isEqualTo(HistoricalImportWindowDto.OPEN);
        assertThat(windowService.state().secondsRemaining())
                .isBetween(Duration.ofDays(14).toSeconds(), Duration.ofDays(15).toSeconds());

        // Age the window by five days before importing again. Without this the
        // assertion below would be untestable: a second import that wrongly
        // reopened the window would land within milliseconds of the first
        // expiry, and "unchanged" could not be told from "rewritten".
        windBack(Duration.ofDays(5));
        Instant expiryBeforeSecondImport = window().getExpiresAt();
        String openedByFirstImport = window().getOpenedByJobId();

        commitHistoricalImport();

        HistoricalImportWindow after = window();
        assertThat(after.getExpiresAt()).isEqualTo(expiryBeforeSecondImport);
        assertThat(after.getOpenedByJobId()).isEqualTo(openedByFirstImport);
        assertThat(windowRepository.count()).isEqualTo(1);
        // Ten days in, not fifteen: the clock kept running through the second import.
        assertThat(windowService.state().secondsRemaining())
                .isBetween(Duration.ofDays(9).toSeconds(), Duration.ofDays(10).toSeconds());
    }

    @Test
    void onceExpiredHistoricalImportIsRefusedWhileRosterImportKeepsWorking() {
        commitHistoricalImport();
        expireTheWindow();

        assertThat(windowService.state().state()).isEqualTo(HistoricalImportWindowDto.EXPIRED);
        assertThat(windowService.state().secondsRemaining()).isNull();

        StudentDetailDto student = createStudent();
        assertThatThrownBy(() -> importService.create("serviceRecordsHistorical",
                historicalCsv(student), "usr_test_admin"))
                .isInstanceOf(ApiException.class)
                // Named, not guessed at: the operator is told the feature closed,
                // when, and that the only route back is the console utility.
                .hasMessageContaining("Historical import closed on")
                .hasMessageContaining("re-enable utility")
                .hasMessageContaining("server console");

        // …and the same server, in the same state, still accepts a roster
        // import. Without this the test above would also pass if the import
        // subsystem had simply fallen over.
        JobDto roster = importService.create("students", rosterCsv(), "usr_test_admin");
        importService.validate(roster.id(), "usr_test_admin");
        awaitStatus(roster.id(), "VALIDATED");
        assertThat(jobRepository.findById(roster.id()).orElseThrow().getValidRows()).isEqualTo(1);
        importService.commit(roster.id(), "usr_test_admin", "idem-roster-" + roster.id());
        awaitStatus(roster.id(), "COMMITTED");
        assertThat(jobRepository.findById(roster.id()).orElseThrow().getValidRows()).isEqualTo(1);
    }

    @Test
    void aJobValidatedBeforeExpiryCannotCommitAfterIt() {
        commitHistoricalImport();

        // Validated while the window is still open — the job is committable at
        // this point, and nothing about it changes except the clock.
        StudentDetailDto student = createStudent();
        JobDto inFlight = importService.create("serviceRecordsHistorical", historicalCsv(student), "usr_test_admin");
        importService.validate(inFlight.id(), "usr_test_admin");
        awaitStatus(inFlight.id(), "VALIDATED");
        assertThat(jobRepository.findById(inFlight.id()).orElseThrow().getValidRows()).isEqualTo(1);

        expireTheWindow();

        assertThatThrownBy(() -> importService.commit(inFlight.id(), "usr_test_admin", "idem-inflight-" + inFlight.id()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("can no longer be committed")
                .hasMessageContaining("re-enable utility");
        // The refusal is real, not cosmetic: nothing reached the ledger, and the
        // job never left VALIDATED.
        assertThat(accountService.getBalanceForStudent(student.id())).isZero();
        assertThat(jobRepository.findById(inFlight.id()).orElseThrow().getStatus()).isEqualTo("VALIDATED");
    }

    /** Runs one historical import end to end and returns once it is COMMITTED. */
    private void commitHistoricalImport() {
        StudentDetailDto student = createStudent();
        JobDto job = importService.create("serviceRecordsHistorical", historicalCsv(student), "usr_test_admin");
        importService.validate(job.id(), "usr_test_admin");
        awaitStatus(job.id(), "VALIDATED");
        importService.commit(job.id(), "usr_test_admin", "idem-window-" + job.id());
        awaitStatus(job.id(), "COMMITTED");
        assertThat(accountService.getBalanceForStudent(student.id())).isEqualTo(100);
    }

    private HistoricalImportWindow window() {
        return windowRepository.findById(HistoricalImportWindow.SINGLETON_ID).orElseThrow(
                () -> new AssertionError("No window row — the first committed historical import did not open one"));
    }

    /**
     * Moves the whole window into the past by SQL rather than by mutating the
     * entity, so the test exercises the same stored values production reads and
     * does not quietly depend on the JVM's clock or timezone handling.
     */
    private void windBack(Duration by) {
        long seconds = by.toSeconds();
        int updated = jdbc.update("UPDATE historical_import_window"
                + " SET opened_at = DATE_SUB(opened_at, INTERVAL ? SECOND),"
                + "     expires_at = DATE_SUB(expires_at, INTERVAL ? SECOND)", seconds, seconds);
        assertThat(updated).isEqualTo(1);
    }

    /** Past the fifteenth day, by a margin no clock skew is going to cover. */
    private void expireTheWindow() {
        windBack(Duration.ofDays(16));
        assertThat(window().getExpiresAt()).isBefore(Instant.now());
    }

    private MockMultipartFile historicalCsv(StudentDetailDto student) {
        String csv = "studentNumber,serviceDate,durationMinutes,description\n"
                + student.studentNumber() + ",2025-10-01,60,Paper ledger row\n";
        return new MockMultipartFile("file", "ledger.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
    }

    private MockMultipartFile rosterCsv() {
        String csv = "studentNumber,displayName,gradeId,classId,enrollmentAcademicYearId,graduationYear,joinedAt\n"
                + studentNumber() + ",Roster Student,grade_6,class_6A,ay_2025_2026,2031,2025-09-01\n";
        return new MockMultipartFile("file", "roster.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
    }

    private StudentDetailDto createStudent() {
        CreateStudentRequest request = new CreateStudentRequest(
                studentNumber(), "Window Student", "grade_6", "class_6A", "ay_2025_2026",
                2029, LocalDate.of(2025, 9, 1));
        return studentService.create(request, "usr_test_admin", "zh-CN");
    }

    /** Crockford base32, as import-rules.yaml requires of a roster row. */
    private String studentNumber() {
        return "WNDW" + String.format("%04d", SEQ.incrementAndGet());
    }

    private void awaitStatus(String jobId, String expectedStatus) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(25));
        while (Instant.now().isBefore(deadline)) {
            String status = jobRepository.findById(jobId).orElseThrow().getStatus();
            if (status.equals(expectedStatus)) return;
            if (status.endsWith("FAILED")) {
                throw new AssertionError("Job " + jobId + " reached " + status + " instead of " + expectedStatus
                        + ": " + jobRepository.findById(jobId).orElseThrow().getErrorMessage());
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        throw new AssertionError("Job " + jobId + " did not reach " + expectedStatus + " within timeout");
    }
}
