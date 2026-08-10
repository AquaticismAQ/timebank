package cn.timebank.reporting;

import cn.timebank.file.application.FileService;
import cn.timebank.file.application.dto.FileRequests.CreateUploadIntentRequest;
import cn.timebank.file.application.dto.UploadIntentDto;
import cn.timebank.iam.application.UserService;
import cn.timebank.iam.application.dto.AuthenticatedUserView;
import cn.timebank.iam.application.dto.CreateUserRequest;
import cn.timebank.reporting.application.DashboardService;
import cn.timebank.reporting.application.ReportService;
import cn.timebank.reporting.application.dto.CompletionReportDto;
import cn.timebank.reporting.application.dto.DashboardAdminDto;
import cn.timebank.reporting.application.dto.DashboardStudentDto;
import cn.timebank.reporting.application.dto.DashboardTeacherDto;
import cn.timebank.reporting.application.dto.ParticipationReportDto;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

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
        submitRecord(participant1, 60);
        submitRecord(participant2, 60);
        submitRecord(participant3, 60);
        // nonParticipant has no record at all.

        ParticipationReportDto report = reportService.participation("zh-CN");

        assertThat(report.generatedAt()).isNotNull();
        ParticipationReportDto.GradeFigure grade10 = report.byGrade().stream()
                .filter(g -> g.gradeId().equals("grade_10")).findFirst().orElseThrow();
        assertThat(grade10.totalStudents()).isEqualTo(4);
        assertThat(grade10.participatedStudents()).isEqualTo(3);
        assertThat(grade10.participationRate()).isEqualTo(0.75);
    }

    @Test
    void completionReportMatchesHandComputedFiguresForASeededGrade() {
        // grade_11, likewise untouched elsewhere. Default seeded rule requires
        // 4,500 annual minutes (75h) — see R__seed_base_data.sql.
        StudentDetailDto compliant = createStudent("grade_11", "class_11A");
        StudentDetailDto shortfall = createStudent("grade_11", "class_11A");
        submitAndApproveRecord(compliant, 5000);
        submitAndApproveRecord(shortfall, 1000);

        CompletionReportDto report = reportService.completion("zh-CN");

        assertThat(report.generatedAt()).isNotNull();
        assertThat(report.requiredMinutes()).isEqualTo(4500);
        CompletionReportDto.GradeFigure grade11 = report.byGrade().stream()
                .filter(g -> g.gradeId().equals("grade_11")).findFirst().orElseThrow();
        assertThat(grade11.totalStudents()).isEqualTo(2);
        assertThat(grade11.completedStudents()).isEqualTo(1);
        assertThat(grade11.completionRate()).isEqualTo(0.5);
    }

    @Test
    void teacherDashboardsAreScopedPerPrincipalAndCarryGeneratedAt() {
        createStudent("grade_6", "class_6A");
        createStudent("grade_6", "class_6A");
        createStudent("grade_7", "class_7A");
        createStudent("grade_7", "class_7A");
        createStudent("grade_7", "class_7A");

        var teacherA = userService.create(new CreateUserRequest(
                "dash_teacher_a_" + SEQ.incrementAndGet(), "InitialPass123!", "Teacher A", null, Set.of("teacher"), "zh-CN"), "usr_test_admin");
        var teacherB = userService.create(new CreateUserRequest(
                "dash_teacher_b_" + SEQ.incrementAndGet(), "InitialPass123!", "Teacher B", null, Set.of("teacher"), "zh-CN"), "usr_test_admin");
        scopeAssignmentRepository.save(new TeacherScopeAssignment(
                "scope_a_" + SEQ.incrementAndGet(), teacherA.id(), "serviceRecords", TeacherScopeAssignment.ScopeType.GRADE, "grade_6", Instant.now()));
        scopeAssignmentRepository.save(new TeacherScopeAssignment(
                "scope_b_" + SEQ.incrementAndGet(), teacherB.id(), "serviceRecords", TeacherScopeAssignment.ScopeType.GRADE, "grade_7", Instant.now()));

        DashboardTeacherDto dashboardA = dashboardService.teacher(authFor(teacherA.id(), null, "teacher"), "zh-CN");
        DashboardTeacherDto dashboardB = dashboardService.teacher(authFor(teacherB.id(), null, "teacher"), "zh-CN");

        assertThat(dashboardA.generatedAt()).isNotNull();
        assertThat(dashboardB.generatedAt()).isNotNull();
        assertThat(dashboardA.scopedStudentCount()).isEqualTo(2);
        assertThat(dashboardB.scopedStudentCount()).isEqualTo(3);
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

    private void submitRecord(StudentDetailDto student, int durationMinutes) {
        Instant end = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant start = end.minus(durationMinutes, ChronoUnit.MINUTES);
        CreateServiceRecordRequest request = new CreateServiceRecordRequest(
                "SELF", null, null, null, LocalDate.now().minusDays(1), start, end,
                "Somewhere", "cat_campus", "desc", "EXTERNAL_MANUAL", null, "Test Supervisor", "test@example.com",
                List.of(readyFile()), false, null);
        ServiceRecordDto draft = serviceRecordService.createDraft(request, student.id(), student.id(), "zh-CN");
        serviceRecordService.submit(draft.id(), studentAuth(student.id()), "idem-report-submit-" + draft.id(), "zh-CN");
    }

    private void submitAndApproveRecord(StudentDetailDto student, int durationMinutes) {
        Instant end = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant start = end.minus(durationMinutes, ChronoUnit.MINUTES);
        CreateServiceRecordRequest request = new CreateServiceRecordRequest(
                "SELF", null, null, null, LocalDate.now().minusDays(1), start, end,
                "Somewhere", "cat_campus", "desc", "EXTERNAL_MANUAL", null, "Test Supervisor", "test@example.com",
                List.of(readyFile()), false, null);
        ServiceRecordDto draft = serviceRecordService.createDraft(request, student.id(), student.id(), "zh-CN");
        ServiceRecordDto submitted = serviceRecordService.submit(draft.id(), studentAuth(student.id()), "idem-report-submit-" + draft.id(), "zh-CN");
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
                "stu_dash_" + n, "Dashboard Student " + n, null, gradeId, classId, "ay_2025_2026",
                2029, LocalDate.of(2025, 9, 1), null, null);
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
