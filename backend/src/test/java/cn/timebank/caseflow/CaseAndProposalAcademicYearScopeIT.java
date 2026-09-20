package cn.timebank.caseflow;

import cn.timebank.caseflow.application.CaseService;
import cn.timebank.caseflow.application.dto.CaseDto;
import cn.timebank.common.error.ApiException;
import cn.timebank.iam.application.dto.AuthenticatedUserView;
import cn.timebank.proposal.application.ProposalService;
import cn.timebank.proposal.application.dto.ProposalDto;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.student.application.StudentService;
import cn.timebank.student.application.dto.StudentRequests.CreateStudentRequest;
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
 * Issue 115 — Case Review and Proposal Review scoped to an academic year.
 *
 * <p>The fixture puts one row of each kind in the current year and one in a past
 * year, because that is the only arrangement that can tell scoping from no
 * scoping: with everything in one year the filter passes whether or not it does
 * anything.</p>
 *
 * <p>Rows are written with JDBC rather than through the services. Both have real
 * creation paths with their own validation, and neither lets a caller choose
 * {@code created_at} — which is exactly the column under test for a case.
 * Building the rows directly is the only way to place one in a past year.</p>
 */
class CaseAndProposalAcademicYearScopeIT extends AbstractIntegrationTest {

    private static final String PAST_YEAR_ID = "ay_cp_scope_past";

    @Autowired
    private CaseService caseService;
    @Autowired
    private ProposalService proposalService;
    @Autowired
    private StudentService studentService;
    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private String currentYearId;
    private String thisYearCase;
    private String pastYearCase;
    private String thisYearProposal;
    private String pastYearProposal;

    @BeforeEach
    void seedOneOfEachInTwoYears() {
        currentYearId = jdbc.queryForObject(
                "select id from academic_year where is_current = 1 order by start_date desc limit 1",
                new MapSqlParameterSource(), String.class);
        LocalDate currentStart = jdbc.queryForObject(
                "select start_date from academic_year where id = :id",
                new MapSqlParameterSource("id", currentYearId), LocalDate.class);
        LocalDate pastStart = currentStart.minusYears(1);

        jdbc.update("""
                insert ignore into academic_year (id, code, start_date, end_date, is_current, created_at, updated_at)
                values (:id, :code, :start, :end, 0, now(3), now(3))
                """, new MapSqlParameterSource().addValue("id", PAST_YEAR_ID)
                // `code` is VARCHAR(16) and unique, so short and distinct rather
                // than a realistic "2025-2026" that base data already seeds.
                .addValue("code", "CP-SCOPE-PAST")
                .addValue("start", pastStart).addValue("end", currentStart.minusDays(1)));

        // A fresh IT schema carries base data but no students, so one is created
        // through the real service rather than hand-inserted.
        String studentId = studentService.create(new CreateStudentRequest(
                Long.toString(System.nanoTime(), 36).toUpperCase(java.util.Locale.ROOT).substring(0, 8),
                "CP Scope Student", "grade_8", "class_8A", "ay_2025_2026", 2026,
                LocalDate.of(2025, 9, 1)), "usr_test_admin", "zh-CN").id();

        thisYearCase = insertCase(studentId, at(currentStart.plusDays(30)));
        pastYearCase = insertCase(studentId, at(pastStart.plusDays(30)));
        thisYearProposal = insertProposal(studentId, at(currentStart.plusDays(30)));
        pastYearProposal = insertProposal(studentId, at(pastStart.plusDays(30)));
    }

    // ── cases ────────────────────────────────────────────────────────────

    /** Absent must not narrow: the same endpoint serves a student's own case history. */
    @Test
    void anAbsentYearReturnsEveryYearOfCases() {
        assertThat(caseIds(null)).contains(thisYearCase, pastYearCase);
    }

    @Test
    void casesNarrowToTheRequestedYearAndBackAgain() {
        assertThat(caseIds(currentYearId)).contains(thisYearCase).doesNotContain(pastYearCase);
        assertThat(caseIds(PAST_YEAR_ID))
                .as("a past year stays reachable, or the data is invisible with no way back")
                .contains(pastYearCase).doesNotContain(thisYearCase);
    }

    @Test
    void anUnknownYearIsRejectedRatherThanIgnoredForCases() {
        assertThatThrownBy(() -> caseIds("ay_does_not_exist"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("No academic year with id");
    }

    // ── proposals ────────────────────────────────────────────────────────

    @Test
    void anAbsentYearReturnsEveryYearOfProposals() {
        assertThat(proposalIds(null)).contains(thisYearProposal, pastYearProposal);
    }

    /**
     * Scoped by {@code plannedStartAt}, so a proposal lands in the same year as
     * the project its approval generates. The fixture's two rows have
     * <em>identical</em> {@code created_at} and differ only in the planned date —
     * so this fails if the implementation keys on creation instead.
     */
    @Test
    void proposalsAreScopedByTheirPlannedEventNotWhenTheyWereWritten() {
        assertThat(proposalIds(currentYearId)).contains(thisYearProposal).doesNotContain(pastYearProposal);
        assertThat(proposalIds(PAST_YEAR_ID)).contains(pastYearProposal).doesNotContain(thisYearProposal);
    }

    @Test
    void anUnknownYearIsRejectedRatherThanIgnoredForProposals() {
        assertThatThrownBy(() -> proposalIds("ay_does_not_exist"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("No academic year with id");
    }

    // ── fixture ──────────────────────────────────────────────────────────

    private List<String> caseIds(String academicYearId) {
        return caseService.list(PageRequest.of(0, 200), adminAuth(), null, null, null, academicYearId, null, "zh-CN")
                .getContent().stream().map(CaseDto::id).toList();
    }

    private List<String> proposalIds(String academicYearId) {
        return proposalService.list(PageRequest.of(0, 200), adminAuth(), null, null, null, academicYearId, null, "zh-CN")
                .getContent().stream().map(ProposalDto::id).toList();
    }

    private static Instant at(LocalDate day) {
        return day.atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant();
    }

    /** SUBMITTED, not DRAFT — a draft is hidden from staff and would pass for the wrong reason. */
    private String insertCase(String studentId, Instant createdAt) {
        String id = "case_cp_" + System.nanoTime();
        jdbc.update("""
                insert into case_record (id, type, student_id, grade_id, class_id, title, reason_code,
                                         status, version, created_at, submitted_at, updated_at)
                values (:id, 'APPEAL', :student, 'grade_8', 'class_8A', :title, 'SCOPE_FIXTURE',
                        'SUBMITTED', 1, :at, :at, :at)
                """, new MapSqlParameterSource().addValue("id", id).addValue("student", studentId)
                .addValue("title", "Scope case " + id).addValue("at", java.sql.Timestamp.from(createdAt)));
        return id;
    }

    /**
     * {@code created_at} is pinned to <em>now</em> for both rows on purpose, so
     * only {@code planned_start_at} distinguishes the two years.
     */
    private String insertProposal(String studentId, Instant plannedStartAt) {
        String id = "prop_cp_" + System.nanoTime();
        jdbc.update("""
                insert into event_proposal (id, proposer_student_id, grade_id, class_id, title, category_id,
                                            planned_start_at, planned_end_at, capacity, proposed_supervisor_id,
                                            proposed_recognized_minutes, status, current_revision, version,
                                            created_at, submitted_at, updated_at)
                values (:id, :student, 'grade_8', 'class_8A', :title, 'cat_campus',
                        :start, :end, 10, 'usr_test_admin', 120, 'SUBMITTED', 1, 1,
                        now(3), now(3), now(3))
                """, new MapSqlParameterSource().addValue("id", id).addValue("student", studentId)
                .addValue("title", "Scope proposal " + id)
                .addValue("start", java.sql.Timestamp.from(plannedStartAt))
                .addValue("end", java.sql.Timestamp.from(plannedStartAt.plusSeconds(7200))));
        return id;
    }

    private Authentication adminAuth() {
        AuthenticatedUserView view = new AuthenticatedUserView(
                "usr_test_admin", "usr_test_admin", "hash", "Test Admin", true, false, "zh-CN",
                Set.of("admin"), Set.of(), null, 480);
        CookieSessionUserDetails principal = new CookieSessionUserDetails(view);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
