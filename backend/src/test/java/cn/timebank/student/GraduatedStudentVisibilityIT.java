package cn.timebank.student;

import cn.timebank.iam.application.dto.AuthenticatedUserView;
import cn.timebank.lifecycle.application.SchoolLifecycleConfigService;
import cn.timebank.lifecycle.application.dto.SchoolLifecycleConfigDto;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.student.application.StudentService;
import cn.timebank.student.application.dto.StudentDetailDto;
import cn.timebank.student.application.dto.StudentListItemDto;
import cn.timebank.student.application.dto.StudentRequests.CreateStudentRequest;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue 114 — graduates are hidden from the roster by default and reachable on
 * request, and the exit grade levels are published rather than copied.
 *
 * <p>The fixture puts a graduated student and a current student <b>in the same
 * grade</b>, because that is the collision the change exists for: graduation
 * leaves {@code gradeId} alone (`13-AnnualRollover.md` §3.1), so from the first
 * rollover onward "grade 8" names two cohorts at once. A test with the graduate
 * in a grade of its own would pass whether or not the default filter does
 * anything.</p>
 */
class GraduatedStudentVisibilityIT extends AbstractIntegrationTest {

    @Autowired
    private StudentService studentService;
    @Autowired
    private SchoolLifecycleConfigService lifecycleConfig;
    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private String graduateId;
    private String currentId;

    @BeforeEach
    void seedOneGraduateAndOneCurrentStudentInTheSameGrade() {
        currentId = createInGrade8("stu_grad_current").id();
        graduateId = createInGrade8("stu_grad_past").id();
        // Nothing but the annual rollover ever writes GRADUATED, and running it
        // here would graduate every grade-8 and grade-12 row in the schema as a
        // side effect. The status is set directly instead; what is under test is
        // how the list treats the value, not who wrote it.
        jdbc.update("update student set status = 'GRADUATED' where id = :id",
                new MapSqlParameterSource("id", graduateId));
    }

    /** The default is the roster: current students, not everyone who ever attended. */
    @Test
    void anAbsentStatusFilterHidesGraduates() {
        assertThat(ids(list(null, null)))
                .contains(currentId)
                .doesNotContain(graduateId);
    }

    /**
     * The collision this exists to prevent: before the change, filtering to the
     * grade returned both cohorts and the count was wrong with nothing saying so.
     */
    @Test
    void filteringToTheGradeDoesNotMixTheTwoCohorts() {
        assertThat(ids(list("grade_8", null)))
                .as("a graduate keeps grade_8 forever — it must not count as this year's grade 8")
                .contains(currentId)
                .doesNotContain(graduateId);
    }

    /** Hidden by default, never unreachable — asking by name still finds them. */
    @Test
    void namingTheStatusReachesTheGraduates() {
        assertThat(ids(list(null, "GRADUATED")))
                .contains(graduateId)
                .doesNotContain(currentId);

        assertThat(ids(list("grade_8", "GRADUATED")))
                .as("grade and status combine, which is what one dropdown option has to send")
                .contains(graduateId)
                .doesNotContain(currentId);
    }

    /** {@code ACTIVE} must still mean ACTIVE, not "everything except graduates". */
    @Test
    void anExplicitActiveFilterIsUnchanged() {
        assertThat(ids(list(null, "ACTIVE")))
                .contains(currentId)
                .doesNotContain(graduateId);
    }

    /**
     * The point of the endpoint: a client offering "grade 8 graduates" reads the
     * levels from the same constant the rollover graduates on, instead of
     * carrying its own pair of literals that nothing keeps in step.
     */
    @Test
    void theConfigEndpointPublishesTheExitLevelsAndTheTurnoverDate() {
        SchoolLifecycleConfigDto config = lifecycleConfig.get();

        assertThat(config.exitGradeLevels()).containsExactly(8, 12);
        assertThat(config.academicYearStart())
                .as("MM-DD, the form an operator types in the env file")
                .matches("\\d{2}-\\d{2}");
    }

    // ── fixture ──────────────────────────────────────────────────────────

    private Page<StudentListItemDto> list(String gradeId, String status) {
        return studentService.list(PageRequest.of(0, 200), adminAuth(), gradeId, null, status, null, "zh-CN");
    }

    private static java.util.List<String> ids(Page<StudentListItemDto> page) {
        return page.getContent().stream().map(StudentListItemDto::id).toList();
    }

    /**
     * {@code student_number} is short and unique (the real ones are 8 characters),
     * so the uniquifier has to be short too — a readable prefix plus a nanotime
     * suffix overruns the column and fails as a data-truncation error rather than
     * anything to do with what is under test.
     */
    private StudentDetailDto createInGrade8(String label) {
        String number = Long.toString(System.nanoTime(), 36).toUpperCase(java.util.Locale.ROOT);
        number = number.substring(number.length() - 8);
        return studentService.create(new CreateStudentRequest(
                number, "Grad Test " + label,
                "grade_8", "class_8A", "ay_2025_2026", 2026, LocalDate.of(2025, 9, 1)),
                "usr_test_admin", "zh-CN");
    }

    private Authentication adminAuth() {
        AuthenticatedUserView view = new AuthenticatedUserView(
                "usr_test_admin", "usr_test_admin", "hash", "Test Admin", true, false, "zh-CN",
                Set.of("admin"), Set.of(), null, 480);
        CookieSessionUserDetails principal = new CookieSessionUserDetails(view);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
