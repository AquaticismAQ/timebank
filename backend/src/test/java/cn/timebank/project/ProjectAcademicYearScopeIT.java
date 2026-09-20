package cn.timebank.project;

import cn.timebank.common.error.ApiException;
import cn.timebank.iam.application.dto.AuthenticatedUserView;
import cn.timebank.project.application.ProjectService;
import cn.timebank.project.application.dto.ProjectDto;
import cn.timebank.project.application.dto.ProjectRequests.CreateProjectRequest;
import cn.timebank.project.application.dto.ProjectRequests.UpdateProjectRequest;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue 112 — `GET /projects` scoped to an academic year.
 *
 * <p>The fixture puts one project in the current year and one in a past year,
 * because that is the only arrangement that can tell scoping from no scoping. A
 * test with all its projects in one year passes whether or not the filter does
 * anything.</p>
 */
class ProjectAcademicYearScopeIT extends AbstractIntegrationTest {

    private static final String PAST_YEAR_ID = "ay_proj_scope_past";

    @Autowired
    private ProjectService projectService;
    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private String currentYearId;
    private String thisYearProject;
    private String pastYearProject;

    @BeforeEach
    void seedTwoYearsOfProjects() {
        // Projects are not deleted between tests: `project_organization` has a
        // foreign key to them, and every assertion below names specific ids, so
        // rows left by an earlier test cannot change an outcome.
        currentYearId = jdbc.queryForObject(
                "select id from academic_year where is_current = 1 order by start_date desc limit 1",
                new MapSqlParameterSource(), String.class);
        LocalDate currentStart = jdbc.queryForObject(
                "select start_date from academic_year where id = :id",
                new MapSqlParameterSource("id", currentYearId), LocalDate.class);

        // A year that ends the day before the current one begins, so the two abut
        // exactly and no project can fall in both or neither.
        LocalDate pastStart = currentStart.minusYears(1);
        jdbc.update("""
                insert ignore into academic_year (id, code, start_date, end_date, is_current, created_at, updated_at)
                values (:id, :code, :start, :end, 0, now(3), now(3))
                """, new MapSqlParameterSource().addValue("id", PAST_YEAR_ID)
                // `code` is VARCHAR(16) and unique, so this is short and distinct
                // rather than a realistic "2025-2026" that would collide with the
                // year base data already seeds.
                .addValue("code", "SCOPE-IT-PAST")
                .addValue("start", pastStart).addValue("end", currentStart.minusDays(1)));

        thisYearProject = publishedProjectStarting(currentStart.plusDays(30), "current");
        pastYearProject = publishedProjectStarting(pastStart.plusDays(30), "past");
    }

    /** Staff asking for nothing get every year — the parameter's absence must not narrow a shared endpoint. */
    @Test
    void absentMeansEveryYearForStaff() {
        List<String> ids = idsFor(adminAuth(), null);

        assertThat(ids).contains(thisYearProject, pastYearProject);
    }

    @Test
    void aStaffMemberCanNarrowToOneYearAndBackAgain() {
        assertThat(idsFor(adminAuth(), currentYearId))
                .contains(thisYearProject)
                .doesNotContain(pastYearProject);

        assertThat(idsFor(adminAuth(), PAST_YEAR_ID))
                .as("a past year is still reachable, or the data is invisible with no way back")
                .contains(pastYearProject)
                .doesNotContain(thisYearProject);
    }

    /**
     * The requirement Aquaticism stated: the Project Center shows the current year only.
     * Enforced in the query, so asking for another year cannot widen it.
     */
    @Test
    void aStudentSeesOnlyTheCurrentYearEvenWhenAskingForAnother() {
        assertThat(idsFor(studentAuth(), null))
                .contains(thisYearProject)
                .doesNotContain(pastYearProject);

        assertThat(idsFor(studentAuth(), PAST_YEAR_ID))
                .as("a student asking for last year is still answered with this year")
                .contains(thisYearProject)
                .doesNotContain(pastYearProject);
    }

    /** An unknown year is a 404, never another year's projects wearing the wrong label. */
    @Test
    void anUnknownAcademicYearIsRejectedRatherThanIgnored() {
        assertThatThrownBy(() -> idsFor(adminAuth(), "ay_does_not_exist"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("No academic year with id");
    }

    // ── fixture ──────────────────────────────────────────────────────────

    private List<String> idsFor(Authentication auth, String academicYearId) {
        return projectService.list(PageRequest.of(0, 200), null, null, null, academicYearId, null, auth, "zh-CN")
                .getContent().stream().map(ProjectDto::id).toList();
    }

    /**
     * A published project starting on a given date. Published rather than draft so
     * it is visible to a student — a draft is filtered out by the visibility rule
     * and would make this test pass for the wrong reason.
     */
    private String publishedProjectStarting(LocalDate startDay, String label) {
        Instant start = startDay.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant();
        // Created with a live registration window and dated afterwards: issue
        // 130 refuses to publish a draft whose registration has already closed,
        // and a past year's project has by definition. Publishing first and
        // then moving the dates is how such a row genuinely comes to exist —
        // it was published when its window was open, and time passed.
        Instant soon = Instant.now().plusSeconds(3600);
        CreateProjectRequest request = new CreateProjectRequest(
                "Scope IT " + label + " " + System.nanoTime(), "summary", "description",
                "cat_campus", null, null, "ON_CAMPUS",
                soon.plusSeconds(7200), soon.plusSeconds(14400), "Hall", 5, 100, null, null,
                Instant.now().minusSeconds(60), soon);
        ProjectDto draft = projectService.createDraft(request, "usr_test_admin", false, "zh-CN");
        ProjectDto published = projectService.publish(draft.id(), draft.version(), adminAuth(), "zh-CN");

        UpdateProjectRequest backdate = new UpdateProjectRequest(
                null, null, null, null, null, null, null,
                start, start.plusSeconds(7200), null, null, null, null, null,
                start.minusSeconds(86400), start.minusSeconds(60), published.version());
        return projectService.updateDraft(published.id(), backdate, adminAuth(), "zh-CN").id();
    }

    private Authentication adminAuth() {
        return authFor("usr_test_admin", "admin", null);
    }

    private Authentication studentAuth() {
        return authFor("usr_scope_student", "student", "stu_scope_student");
    }

    private Authentication authFor(String userId, String role, String studentId) {
        AuthenticatedUserView view = new AuthenticatedUserView(
                userId, userId, "hash", "Display " + userId, true, false, "zh-CN",
                Set.of(role), Set.of("projects.read"), studentId, 480);
        CookieSessionUserDetails principal = new CookieSessionUserDetails(view);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
