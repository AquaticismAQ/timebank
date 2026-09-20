package cn.timebank.reporting;

import cn.timebank.common.cache.CacheConfig;
import cn.timebank.common.error.ApiErrorCode;
import cn.timebank.common.error.ApiException;
import cn.timebank.enrollment.application.EnrollmentService;
import cn.timebank.enrollment.application.dto.EnrollmentDto;
import cn.timebank.enrollment.application.dto.EnrollmentRequests.RegisterRequest;
import cn.timebank.file.application.FileService;
import cn.timebank.file.application.dto.FileRequests.CreateUploadIntentRequest;
import cn.timebank.file.application.dto.UploadIntentDto;
import cn.timebank.iam.application.UserService;
import cn.timebank.iam.application.dto.AuthenticatedUserView;
import cn.timebank.iam.application.dto.CreateUserRequest;
import cn.timebank.project.application.ProjectService;
import cn.timebank.project.application.dto.ProjectDto;
import cn.timebank.project.application.dto.ProjectRequests.CreateProjectRequest;
import cn.timebank.reporting.application.DashboardService;
import cn.timebank.reporting.application.ReportService;
import cn.timebank.reporting.application.dto.CompletionReportDto;
import cn.timebank.reporting.application.dto.DashboardAdminDto;
import cn.timebank.reporting.application.dto.DashboardStudentDto;
import cn.timebank.reporting.application.dto.DashboardTeacherDto;
import cn.timebank.reporting.application.dto.ParticipationReportDto;
import cn.timebank.reporting.application.dto.ServiceHoursReportDto;
import cn.timebank.reporting.application.dto.TopProjectsReportDto;
import cn.timebank.review.application.ReviewService;
import cn.timebank.review.application.dto.ReviewRequests.DecideRequest;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.security.domain.TeacherScopeAssignment;
import cn.timebank.security.infrastructure.TeacherScopeAssignmentRepository;
import cn.timebank.servicerecord.application.ServiceRecordService;
import cn.timebank.servicerecord.application.dto.ServiceRecordDto;
import cn.timebank.servicerecord.application.dto.ServiceRecordRequests.CreateServiceRecordRequest;
import cn.timebank.student.application.StudentService;
import cn.timebank.student.application.dto.StudentDetailDto;
import cn.timebank.student.application.dto.StudentRequests.CreateStudentRequest;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B5.7/B5.8's exit criteria: report figures for a seeded grade match a
 * hand-computed expectation (not merely "plausible"), and dashboards
 * return {@code generatedAt} and are never shared across principals
 * (Design.md §16.2's named privacy risk for viewer-scoped caching).
 */
class DashboardReportingIT extends AbstractIntegrationTest {

    @Autowired
    private StudentService studentService;
    @Autowired
    private ServiceRecordService serviceRecordService;
    @Autowired
    private ReviewService reviewService;
    @Autowired
    private FileService fileService;
    @Autowired
    private UserService userService;
    @Autowired
    private TeacherScopeAssignmentRepository scopeAssignmentRepository;
    @Autowired
    private ReportService reportService;
    @Autowired
    private DashboardService dashboardService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private EnrollmentService enrollmentService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private CacheManager cacheManager;

    /** The academic year `R__seed_base_data.sql` seeds and marks current: 2025-09-01..2026-08-31. */
    private static final String SEEDED_YEAR_ID = "ay_2025_2026";
    private static final String LATER_YEAR_ID = "ay_it_later";
    private static final String AROUND_NOW_YEAR_ID = "ay_it_around_now";
    private static final String LONG_PAST_YEAR_ID = "ay_it_long_past";
    /** Both in the past for good (a future service date is rejected) and each inside exactly one of the two years. */
    private static final LocalDate IN_SEEDED_YEAR = LocalDate.of(2025, 10, 15);
    private static final LocalDate IN_LATER_YEAR = LocalDate.of(2026, 9, 10);

    private static final byte[] VALID_JPEG = cn.timebank.testsupport.TestImages.validJpegBytes();

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Test
    void participationReportMatchesHandComputedFiguresForASeededGrade() {
        // grade_10 is untouched by any other IT fixture, so "total" (every
        // ACTIVE student in the grade) is exactly the four created here.
        StudentDetailDto participant1 = createStudent("grade_10", "class_10A");
        StudentDetailDto participant2 = createStudent("grade_10", "class_10A");
        StudentDetailDto participant3 = createStudent("grade_10", "class_10A");
        StudentDetailDto nonParticipant = createStudent("grade_10", "class_10A");
        // Dated into the seeded academic year explicitly. These three calls used
        // to say `LocalDate.now().minusDays(1)`, which silently stopped being
        // inside the seeded window (..2026-08-31) once the wall clock passed it —
        // the report would then correctly count nobody and this test would fail
        // for a reason that has nothing to do with the report.
        submitRecord(participant1, IN_SEEDED_YEAR, 60);
        submitRecord(participant2, IN_SEEDED_YEAR, 60);
        submitRecord(participant3, IN_SEEDED_YEAR, 60);
        // nonParticipant has no record at all.

        ParticipationReportDto report = reportService.participation("zh-CN", null);

        assertThat(report.generatedAt()).isNotNull();
        ParticipationReportDto.GradeFigure grade10 = report.byGrade().stream()
                .filter(g -> g.gradeId().equals("grade_10")).findFirst().orElseThrow();
        assertThat(grade10.totalStudents()).isEqualTo(4);
        assertThat(grade10.participatedStudents()).isEqualTo(3);
        assertThat(grade10.participationRate()).isEqualTo(0.75);
    }

    /**
     * Design/chinese/01-TimeBankDesign.md §6: middle school (grade_7) and
     * high school (grade_11) have different annual requirements — 1,500
     * minutes (25h) vs. 4,500 minutes (75h), per the default seeded rule
     * (R__seed_base_data.sql). A student with a duration between the two
     * thresholds must be compliant for their own grade's band but not the
     * other's, proving the completion report picks the right one per grade
     * rather than applying one flat number school-wide.
     */
    @Test
    void completionReportUsesTheRightGradeBandsRequirement() {
        StudentDetailDto highCompliant = createStudent("grade_11", "class_11A");
        StudentDetailDto highShortfall = createStudent("grade_11", "class_11A");
        submitAndApproveRecordOn(highCompliant, IN_SEEDED_YEAR, 5000);
        submitAndApproveRecordOn(highShortfall, IN_SEEDED_YEAR, 1000);

        StudentDetailDto middleCompliant = createStudent("grade_7", "class_7A");
        submitAndApproveRecordOn(middleCompliant, IN_SEEDED_YEAR, 2000); // above middle school's 1,500 but below high school's 4,500

        CompletionReportDto report = reportService.completion("zh-CN", null);

        assertThat(report.generatedAt()).isNotNull();
        CompletionReportDto.GradeFigure grade11 = report.byGrade().stream()
                .filter(g -> g.gradeId().equals("grade_11")).findFirst().orElseThrow();
        assertThat(grade11.requiredMinutes()).isEqualTo(4500);
        assertThat(grade11.totalStudents()).isEqualTo(2);
        assertThat(grade11.completedStudents()).isEqualTo(1);
        assertThat(grade11.completionRate()).isEqualTo(0.5);

        CompletionReportDto.GradeFigure grade7 = report.byGrade().stream()
                .filter(g -> g.gradeId().equals("grade_7")).findFirst().orElseThrow();
        assertThat(grade7.requiredMinutes()).isEqualTo(1500);
        assertThat(grade7.completedStudents()).isGreaterThanOrEqualTo(1);
    }

    /**
     * 07-RoleFinetune.md §2.1: {@code teacher} became a {@code
     * hasFullDataScope()} role (like {@code admin}/{@code sysadmin}), so its
     * {@code teacher_scope_assignment} grade bond no longer restricts
     * dashboard visibility — two teachers with *different* grade scopes now
     * see the *same* school-wide student count. This test used to assert the
     * opposite (2 vs. 3, scoped per grade); that assertion described the
     * pre-07-RoleFinetune behavior this doc's §2.1 deliberately changed, not
     * a regression to guard against.
     */
    @Test
    // 07-RoleFinetune.md §2.10 (2026-08-20): reverted from §2.1's "any non-student sees
    // everything" back to grade-scoped visibility for teacher — see hasSchoolWideVisibility().
    // Was teacherDashboardsSeeTheSameSchoolWideCountRegardlessOfGradeScopeAndCarryGeneratedAt,
    // asserting the two teachers' counts were equal (both school-wide); now their scopes
    // genuinely differ, so each is asserted against only its own grade's fresh students.
    void teacherDashboardsAreScopedToTheirOwnGradeAndCarryGeneratedAt() {
        createStudent("grade_6", "class_6A");
        createStudent("grade_6", "class_6A");
        createStudent("grade_7", "class_7A");
        createStudent("grade_7", "class_7A");
        createStudent("grade_7", "class_7A");

        var teacherA = userService.create(new CreateUserRequest(
                "dash_teacher_a_" + SEQ.incrementAndGet(), "Teacher A", null, "teacher", "zh-CN"), "usr_test_admin");
        var teacherB = userService.create(new CreateUserRequest(
                "dash_teacher_b_" + SEQ.incrementAndGet(), "Teacher B", null, "teacher", "zh-CN"), "usr_test_admin");
        scopeAssignmentRepository.save(new TeacherScopeAssignment(
                "scope_a_" + SEQ.incrementAndGet(), teacherA.id(), "serviceRecords", TeacherScopeAssignment.ScopeType.GRADE, "grade_6", Instant.now()));
        scopeAssignmentRepository.save(new TeacherScopeAssignment(
                "scope_b_" + SEQ.incrementAndGet(), teacherB.id(), "serviceRecords", TeacherScopeAssignment.ScopeType.GRADE, "grade_7", Instant.now()));

        DashboardTeacherDto dashboardA = dashboardService.teacher(authFor(teacherA.id(), null, "teacher"), "zh-CN");
        DashboardTeacherDto dashboardB = dashboardService.teacher(authFor(teacherB.id(), null, "teacher"), "zh-CN");

        assertThat(dashboardA.generatedAt()).isNotNull();
        assertThat(dashboardB.generatedAt()).isNotNull();
        // Not hardcoded totals, since each grade may also carry students seeded by
        // earlier tests in this class — just at least the fresh students just created,
        // and grade_7's teacher sees strictly more than grade_6's (3 vs. 2 fresh students).
        assertThat(dashboardA.scopedStudentCount()).isGreaterThanOrEqualTo(2);
        assertThat(dashboardB.scopedStudentCount()).isGreaterThanOrEqualTo(3);
        assertThat(dashboardB.scopedStudentCount()).isGreaterThan(dashboardA.scopedStudentCount());
    }

    @Test
    void studentAndAdminDashboardsReturnGeneratedAt() {
        StudentDetailDto student = createStudent("grade_8", "class_8A");

        DashboardStudentDto studentDashboard = dashboardService.student(studentAuth(student.id()), "zh-CN");
        DashboardAdminDto adminDashboard = dashboardService.admin(authFor("usr_dash_admin", null, "admin"), "zh-CN");

        assertThat(studentDashboard.generatedAt()).isNotNull();
        assertThat(studentDashboard.progress().studentId()).isEqualTo(student.id());
        assertThat(adminDashboard.generatedAt()).isNotNull();
        assertThat(adminDashboard.stats().totalStudents()).isGreaterThanOrEqualTo(1);
    }

    /**
     * Issue 093 §1, against the real SQL rather than the service wiring that
     * {@code ReportYearScopeTest} covers. Two academic years, the same three
     * students, and service dated into one year or the other — if the {@code
     * between :start and :end} predicates stopped being fed the selected
     * year's window, the two calls would agree and every assertion below
     * would fail.
     *
     * <p>The dates are fixed literals, not offsets from {@code now()}: the
     * seeded current year is itself a fixed window (2025-09-01..2026-08-31 in
     * {@code R__seed_base_data.sql}), so a relative date would drift out of
     * it. Both are in the past and stay there.</p>
     */
    @Test
    void reportsScopedToDifferentAcademicYearsReturnDifferentFigures() {
        insertAcademicYear(LATER_YEAR_ID, "IT-later", LocalDate.of(2026, 9, 1), LocalDate.of(2027, 8, 31));

        // grade_9 is untouched by every other fixture in this class.
        StudentDetailDto compliantInSeededYear = createStudent("grade_9", "class_9A");
        StudentDetailDto shortfallInSeededYear = createStudent("grade_9", "class_9A");
        StudentDetailDto servedInLaterYear = createStudent("grade_9", "class_9A");
        submitAndApproveRecordOn(compliantInSeededYear, IN_SEEDED_YEAR, 5000); // grade_9 needs 4,500
        submitAndApproveRecordOn(shortfallInSeededYear, IN_SEEDED_YEAR, 1000);
        submitAndApproveRecordOn(servedInLaterYear, IN_LATER_YEAR, 1000);

        ParticipationReportDto seededYear = reportService.participation("zh-CN", SEEDED_YEAR_ID);
        ParticipationReportDto laterYear = reportService.participation("zh-CN", LATER_YEAR_ID);
        assertThat(seededYear.academicYearId()).isEqualTo(SEEDED_YEAR_ID);
        assertThat(laterYear.academicYearId()).isEqualTo(LATER_YEAR_ID);
        // Same denominator — all three were already enrolled — different numerator.
        assertThat(grade9(seededYear).totalStudents()).isEqualTo(3);
        assertThat(grade9(laterYear).totalStudents()).isEqualTo(3);
        assertThat(grade9(seededYear).participatedStudents()).isEqualTo(2);
        assertThat(grade9(laterYear).participatedStudents()).isEqualTo(1);

        CompletionReportDto seededCompletion = reportService.completion("zh-CN", SEEDED_YEAR_ID);
        CompletionReportDto laterCompletion = reportService.completion("zh-CN", LATER_YEAR_ID);
        assertThat(grade9Completion(seededCompletion).completedStudents()).isEqualTo(1);
        assertThat(grade9Completion(laterCompletion).completedStudents()).isZero();

        // School-wide, so other fixtures in this class contribute months too —
        // asserted by which month appears in which year, not by exact totals.
        ServiceHoursReportDto seededHours = reportService.serviceHours(SEEDED_YEAR_ID);
        ServiceHoursReportDto laterHours = reportService.serviceHours(LATER_YEAR_ID);
        assertThat(months(seededHours)).contains("2025-10").doesNotContain("2026-09");
        assertThat(months(laterHours)).contains("2026-09").doesNotContain("2025-10");
    }

    /**
     * Issue 093 §1: top-projects is the one report with no service date to
     * bound — it is scoped by {@code project.start_at}, a predicate that
     * exists nowhere else and so is worth proving against the real query.
     * Both years here are relative to {@code now()} because a project's own
     * schedule has to be in the future for it to be published and registered
     * against.
     */
    @Test
    void topProjectsListsOnlyProjectsScheduledInsideTheYearAsked() {
        LocalDate today = LocalDate.now();
        insertAcademicYear(AROUND_NOW_YEAR_ID, "IT-now", today.minusDays(30), today.plusDays(30));
        insertAcademicYear(LONG_PAST_YEAR_ID, "IT-past", LocalDate.of(2020, 9, 1), LocalDate.of(2021, 8, 31));

        Instant now = Instant.now();
        CreateProjectRequest request = new CreateProjectRequest(
                "Year Scope Test Project", "summary", "description", "cat_campus", null, null, "ON_CAMPUS",
                now.plusSeconds(7200), now.plusSeconds(10800), "Hall", 5, 100, null, null,
                now.minusSeconds(60), now.plusSeconds(3600));
        ProjectDto draft = projectService.createDraft(request, "usr_test_admin", false, "zh-CN");
        ProjectDto published = projectService.publish(draft.id(), draft.version(), authFor("usr_dash_admin", null, "admin"), "zh-CN");
        ProjectDto registrationOpen = projectService.getDetail(published.id(), authFor("usr_dash_admin", null, "admin"), "zh-CN");
        // grade_8, not grade_9: the year-scoping test above asserts an exact grade_9
        // head count, and test methods in a class share one schema.
        StudentDetailDto student = createStudent("grade_8", "class_8A");
        EnrollmentDto enrollment = enrollmentService.register(
                registrationOpen.id(), new RegisterRequest(null), studentAuth(student.id()), "zh-CN");
        assertThat(enrollment.status()).isEqualTo("CONFIRMED");

        TopProjectsReportDto aroundNow = reportService.topProjects(AROUND_NOW_YEAR_ID);
        TopProjectsReportDto longPast = reportService.topProjects(LONG_PAST_YEAR_ID);

        assertThat(aroundNow.topProjects()).extracting(TopProjectsReportDto.ProjectFigure::projectId)
                .contains(published.id());
        assertThat(longPast.topProjects()).extracting(TopProjectsReportDto.ProjectFigure::projectId)
                .doesNotContain(published.id());
        assertThat(aroundNow.academicYearId()).isEqualTo(AROUND_NOW_YEAR_ID);
        assertThat(longPast.academicYearId()).isEqualTo(LONG_PAST_YEAR_ID);
    }

    @Test
    void anUnknownAcademicYearIsRejectedRatherThanScopedToTheCurrentOne() {
        assertThatThrownBy(() -> reportService.participation("zh-CN", "ay_does_not_exist"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ApiErrorCode.RESOURCE_NOT_FOUND));
    }

    private static ParticipationReportDto.GradeFigure grade9(ParticipationReportDto report) {
        return report.byGrade().stream().filter(g -> g.gradeId().equals("grade_9")).findFirst().orElseThrow();
    }

    private static CompletionReportDto.GradeFigure grade9Completion(CompletionReportDto report) {
        return report.byGrade().stream().filter(g -> g.gradeId().equals("grade_9")).findFirst().orElseThrow();
    }

    private static List<String> months(ServiceHoursReportDto report) {
        return report.byMonth().stream().map(ServiceHoursReportDto.MonthFigure::month).toList();
    }

    /** Straight JDBC: {@code AcademicYear} is a read-only entity with no public constructor, and the dictionary read is cached. */
    private void insertAcademicYear(String id, String code, LocalDate start, LocalDate end) {
        jdbcTemplate.update("""
                insert ignore into academic_year (id, code, start_date, end_date, is_current, created_at, updated_at)
                values (?, ?, ?, ?, 0, now(3), now(3))
                """, id, code, start, end);
        Cache dictionaries = cacheManager.getCache(CacheConfig.DICTIONARIES);
        if (dictionaries != null) {
            dictionaries.clear();
        }
    }

    private ServiceRecordDto submitRecord(StudentDetailDto student, LocalDate serviceDate, int durationMinutes) {
        Instant end = serviceDate.atTime(12, 0).toInstant(ZoneOffset.UTC);
        Instant start = end.minus(durationMinutes, ChronoUnit.MINUTES);
        CreateServiceRecordRequest request = new CreateServiceRecordRequest(
                "SELF", null, null, null, serviceDate, start, end,
                "Somewhere", "cat_campus", "desc", "EXTERNAL_MANUAL", null, "Test Supervisor", "test@example.com",
                List.of(readyFile()), false, null);
        ServiceRecordDto draft = serviceRecordService.createDraft(request, student.id(), student.id(), "zh-CN");
        return serviceRecordService.submit(draft.id(), studentAuth(student.id()), "idem-report-submit-" + draft.id(), "zh-CN");
    }

    private void submitAndApproveRecordOn(StudentDetailDto student, LocalDate serviceDate, int durationMinutes) {
        ServiceRecordDto submitted = submitRecord(student, serviceDate, durationMinutes);
        reviewService.decide(submitted.id(), new DecideRequest("APPROVE", "GOOD", null, submitted.version()),
                authFor("usr_dash_admin", null, "admin"), "idem-report-decide-" + submitted.id(), "zh-CN");
    }

    private String readyFile() {
        UploadIntentDto intent = fileService.createUploadIntent(
                new CreateUploadIntentRequest("evidence.jpg", "image/jpeg", VALID_JPEG.length, "serviceRecordEvidence"),
                "usr_test_admin");
        fileService.receiveContent(intent.fileId(), "usr_test_admin", new ByteArrayInputStream(VALID_JPEG));
        fileService.complete(intent.fileId(), "usr_test_admin");
        return intent.fileId();
    }

    private StudentDetailDto createStudent(String gradeId, String classId) {
        int n = SEQ.incrementAndGet();
        CreateStudentRequest request = new CreateStudentRequest(
                "stu_dash_" + n, "Dashboard Student " + n, gradeId, classId, "ay_2025_2026",
                2029, LocalDate.of(2025, 9, 1));
        return studentService.create(request, "usr_test_admin", "zh-CN");
    }

    private Authentication studentAuth(String studentId) {
        return authFor("usr_" + studentId.substring(4), studentId, "student");
    }

    private Authentication authFor(String userId, String studentId, String roleCode) {
        AuthenticatedUserView view = new AuthenticatedUserView(
                userId, userId, "hash", "Test " + userId, true, false, "zh-CN", Set.of(roleCode), Set.of(), studentId, 480);
        CookieSessionUserDetails principal = new CookieSessionUserDetails(view);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
