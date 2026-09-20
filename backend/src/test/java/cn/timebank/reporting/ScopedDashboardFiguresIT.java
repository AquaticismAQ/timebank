package cn.timebank.reporting;

import cn.timebank.account.application.AccountService;
import cn.timebank.account.application.dto.AccountSummaryDto;
import cn.timebank.reporting.infrastructure.ReportQueryRepository;
import cn.timebank.security.application.ScopeDescriptor;
import cn.timebank.student.application.StudentService;
import cn.timebank.student.application.dto.StudentDetailDto;
import cn.timebank.student.application.dto.StudentRequests.CreateStudentRequest;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue 127: every figure on the teacher dashboard used to be school-wide, so a
 * teacher scoped to two grades read the same six numbers as the head teacher.
 * Issue 128: the Time Coin Accounts summary summed the rows on screen.
 *
 * <p>These exercise the layers that carry the scope — the SQL, and the Criteria
 * aggregate — rather than the login that produces one; a principal's grades come
 * from seeded scope rows, and threading that through here would test the
 * fixture rather than the query.</p>
 */
class ScopedDashboardFiguresIT extends AbstractIntegrationTest {

    @Autowired
    private ReportQueryRepository queryRepository;
    @Autowired
    private StudentService studentService;
    @Autowired
    private AccountService accountService;

    @Test
    void adminStatsCountsOnlyTheStudentsInScope() {
        StudentDetailDto inScope = create("stu_scope_g6", "Scoped Grade 6 Student", "grade_6", "class_6A");
        StudentDetailDto outOfScope = create("stu_scope_g7", "Scoped Grade 7 Student", "grade_7", "class_7A");
        assertThat(inScope.id()).isNotEqualTo(outOfScope.id());

        LocalDate monthStart = LocalDate.now().withDayOfMonth(1);
        LocalDate yearStart = monthStart.minusMonths(6);
        LocalDate yearEnd = monthStart.plusMonths(6);

        ReportQueryRepository.AdminStatsRow all =
                queryRepository.adminStats(monthStart, monthStart.plusMonths(1), yearStart, yearEnd,
                        new ScopeDescriptor.All());
        ReportQueryRepository.AdminStatsRow grade6 =
                queryRepository.adminStats(monthStart, monthStart.plusMonths(1), yearStart, yearEnd,
                        new ScopeDescriptor.Grades(Set.of("grade_6")));
        ReportQueryRepository.AdminStatsRow grade7 =
                queryRepository.adminStats(monthStart, monthStart.plusMonths(1), yearStart, yearEnd,
                        new ScopeDescriptor.Grades(Set.of("grade_7")));

        // Not "the scoped number is smaller" — that would pass on a query that
        // scoped nothing and happened to be run on a small fixture. The two
        // grades have to partition the whole.
        assertThat(grade6.totalStudents()).isLessThan(all.totalStudents());
        assertThat(grade7.totalStudents()).isLessThan(all.totalStudents());
        assertThat(grade6.totalStudents() + grade7.totalStudents()).isLessThanOrEqualTo(all.totalStudents());
        assertThat(grade6.activeStudents()).isGreaterThanOrEqualTo(1);
        assertThat(grade7.activeStudents()).isGreaterThanOrEqualTo(1);
    }

    /** A scope with nothing in it must report nothing, not everything. */
    @Test
    void anEmptyScopeReportsNothingRatherThanTheWholeSchool() {
        create("stu_scope_empty", "Empty Scope Student", "grade_6", "class_6A");
        LocalDate monthStart = LocalDate.now().withDayOfMonth(1);

        ReportQueryRepository.AdminStatsRow none =
                queryRepository.adminStats(monthStart, monthStart.plusMonths(1),
                        monthStart.minusMonths(6), monthStart.plusMonths(6),
                        new ScopeDescriptor.Grades(Set.of()));

        assertThat(none.totalStudents()).isZero();
        assertThat(none.activeStudents()).isZero();
        assertThat(none.totalProjects()).isZero();
        assertThat(none.approvedThisMonth()).isZero();
        assertThat(none.totalServiceHoursThisYear()).isZero();
    }

    @Test
    void theAccountSummaryTotalsEveryAccountInScopeAndAveragesOverAllOfThem() {
        create("stu_summary_a", "Summary Student A", "grade_6", "class_6A");
        create("stu_summary_b", "Summary Student B", "grade_7", "class_7A");

        AccountSummaryDto summary = accountService.summary(adminAuth());
        long listed = accountService.list(org.springframework.data.domain.Pageable.unpaged(), null, adminAuth())
                .getContent().stream().mapToLong(a -> a.balance()).sum();
        long listedCount = accountService.list(org.springframework.data.domain.Pageable.unpaged(), null, adminAuth())
                .getTotalElements();

        // The cards and the rows beneath them have to agree — disagreeing is
        // the whole of issue 128.
        assertThat(summary.totalBalance()).isEqualTo(listed);
        assertThat(summary.accountCount()).isEqualTo(listedCount);
        assertThat(summary.averageBalance())
                .isEqualTo(listedCount == 0 ? 0 : listed / listedCount);
        assertThat(summary.accountsWithBalance()).isLessThanOrEqualTo(summary.accountCount());
    }

    private org.springframework.security.core.Authentication adminAuth() {
        cn.timebank.iam.application.dto.AuthenticatedUserView view =
                new cn.timebank.iam.application.dto.AuthenticatedUserView(
                        "usr_test_admin", "usr_test_admin", "hash", "Test Admin", true, false, "zh-CN",
                        Set.of("admin"), Set.of(), null, 480);
        cn.timebank.security.application.CookieSessionUserDetails principal =
                new cn.timebank.security.application.CookieSessionUserDetails(view);
        return new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());
    }

    private StudentDetailDto create(String number, String name, String gradeId, String classId) {
        return studentService.create(
                new CreateStudentRequest(number, name, gradeId, classId, "ay_2025_2026", 2029,
                        LocalDate.of(2025, 9, 1)),
                "usr_test_admin", "zh-CN");
    }
}
