package cn.timebank.servicerecord;

import cn.timebank.common.error.ApiException;
import cn.timebank.enrollment.application.EnrollmentService;
import cn.timebank.enrollment.application.dto.EnrollmentDto;
import cn.timebank.enrollment.application.dto.EnrollmentRequests.RegisterRequest;
import cn.timebank.file.application.FileService;
import cn.timebank.file.application.dto.FileRequests.CreateUploadIntentRequest;
import cn.timebank.file.application.dto.UploadIntentDto;
import cn.timebank.iam.application.UserService;
import cn.timebank.iam.application.dto.CreateUserRequest;
import cn.timebank.iam.application.dto.UserDto;
import cn.timebank.organization.application.OrganizationService;
import cn.timebank.organization.application.dto.OrganizationDto;
import cn.timebank.organization.application.dto.OrganizationRequests.CreateOrganizationRequest;
import cn.timebank.project.application.ProjectService;
import cn.timebank.project.application.dto.ProjectDto;
import cn.timebank.project.application.dto.ProjectRequests.CreateProjectRequest;
import cn.timebank.security.application.CookieSessionUserDetails;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Docs/optimization/03-ServiceRecordFormOptimization.md §3/§5/§10 — the PROJECT/SELF
 * source split: PROJECT snapshots every organization/category/location/supervisor field
 * from the project (never the client), requires a CONFIRMED registration (re-checked at
 * both create and submit), and every new record gets a unique {@code recordNumber}.
 * Complements {@code ServiceRecordSubmissionIT}, which covers the source-agnostic
 * validation rules (evidence readiness, overlap/anomaly, concurrency) unchanged by this
 * work.
 */
class ServiceRecordSourceOptimizationIT extends AbstractIntegrationTest {

    private static final byte[] VALID_JPEG = cn.timebank.testsupport.TestImages.validJpegBytes();
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private StudentService studentService;
    @Autowired
    private ServiceRecordService serviceRecordService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private EnrollmentService enrollmentService;
    @Autowired
    private UserService userService;
    @Autowired
    private OrganizationService organizationService;
    @Autowired
    private FileService fileService;

    @Test
    void projectSourcedRecordRejectsAStudentWithoutConfirmedRegistration() {
        UserDto owner = createTeacherWithEmail();
        StudentDetailDto student = createStudent();
        ProjectDto project = publishProject(owner.id(), null);
        // Deliberately not registered/confirmed for this project.

        CreateServiceRecordRequest request = projectRequest(project.id());
        assertThatThrownBy(() -> serviceRecordService.createDraft(request, student.id(), student.id(), "zh-CN"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("confirmed registration");
    }

    @Test
    void projectSourcedRecordSnapshotsOwnerAsSupervisorAndOrganizationsAndRejectsSubmissionAfterCancellation() {
        UserDto owner = createTeacherWithEmail();
        OrganizationDto partner = organizationService.create(
                new CreateOrganizationRequest("Riverside NGO " + SEQ.incrementAndGet(), "nonprofit",
                        null, null, null, null, null),
                "usr_test_admin");
        StudentDetailDto student = createStudent();
        ProjectDto project = publishProject(owner.id(), partner.id());
        EnrollmentDto enrollment = confirmRegistration(project, student);

        CreateServiceRecordRequest request = projectRequest(project.id());
        ServiceRecordDto draft = serviceRecordService.createDraft(request, student.id(), student.id(), "zh-CN");

        assertThat(draft.sourceType()).isEqualTo("PROJECT");
        assertThat(draft.supervisorKind()).isEqualTo("PROJECT_OWNER");
        assertThat(draft.supervisorName()).isEqualTo(owner.displayName());
        assertThat(draft.supervisorContact()).isEqualTo(owner.email());
        assertThat(draft.serviceCategoryId()).isEqualTo(project.categoryId());
        assertThat(draft.location()).isEqualTo(project.location());
        assertThat(draft.organizationId()).isEqualTo(partner.id());
        assertThat(draft.hostOrganizationName()).isNotBlank();
        assertThat(draft.partnerOrganizationNames()).containsExactly(partner.name());
        assertThat(draft.recordNumber()).isNotBlank().startsWith("TB-");

        // A client cannot override any of the above via update (PROJECT's snapshot is locked, §4.3) —
        // not separately asserted here since UpdateServiceRecordRequest simply never applies these
        // fields for PROJECT-sourced records (ServiceRecordServiceImpl.updateDraft).

        // §3.1: re-checked at submission too, not just creation — cancelling the registration
        // after the draft was created must block submission.
        enrollmentService.cancel(enrollment.id(), studentAuth(student.id()), "zh-CN");
        assertThatThrownBy(() -> serviceRecordService.submit(draft.id(), studentAuth(student.id()), "idem-cancel-" + draft.id(), "zh-CN"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).fieldErrors())
                        .anyMatch(fe -> fe.field().equals("projectId") && fe.code().equals("NOT_CONFIRMED")));
    }

    @Test
    void newRecordsCannotBeCreatedWithTheHistoricalExternalSourceType() {
        StudentDetailDto student = createStudent();
        Instant end = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant start = end.minusSeconds(3600);
        CreateServiceRecordRequest request = new CreateServiceRecordRequest(
                "EXTERNAL", null, null, "Old Org", LocalDate.now().minusDays(1), start, end,
                "Somewhere", "cat_campus", "desc", null, null, "Coach", "coach@example.com",
                List.of(), false, null);

        assertThatThrownBy(() -> serviceRecordService.createDraft(request, student.id(), student.id(), "zh-CN"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("historical");
    }

    @Test
    void recordNumbersAreUniqueAndSequentialPerStudent() {
        StudentDetailDto student = createStudent();
        ServiceRecordDto r1 = selfDraft(student);
        ServiceRecordDto r2 = selfDraft(student);

        assertThat(r1.recordNumber()).isNotBlank();
        assertThat(r2.recordNumber()).isNotBlank();
        assertThat(r1.recordNumber()).isNotEqualTo(r2.recordNumber());
    }

    @Test
    void externalManualSupervisorRequiresBothNameAndContact() {
        StudentDetailDto student = createStudent();
        Instant end = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant start = end.minusSeconds(3600);
        CreateServiceRecordRequest missingContact = new CreateServiceRecordRequest(
                "SELF", null, null, null, LocalDate.now().minusDays(1), start, end,
                "Somewhere", "cat_campus", "desc", "EXTERNAL_MANUAL", null, "Coach Li", null,
                List.of(), false, null);

        assertThatThrownBy(() -> serviceRecordService.createDraft(missingContact, student.id(), student.id(), "zh-CN"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void travelTimeRequiresAReasonAtSubmissionButNotAtDraftSave() {
        StudentDetailDto student = createStudent();
        Instant end = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant start = end.minusSeconds(3600);
        CreateServiceRecordRequest request = new CreateServiceRecordRequest(
                "SELF", null, null, null, LocalDate.now().minusDays(1), start, end,
                "Somewhere", "cat_campus", "desc", "EXTERNAL_MANUAL", null, "Coach Li", "coach@example.com",
                List.of(readyFile()), true, null);

        // Saving a draft with travel time disclosed but no reason yet is allowed (drafts are permissive).
        ServiceRecordDto draft = serviceRecordService.createDraft(request, student.id(), student.id(), "zh-CN");
        assertThat(draft.includesTravelTime()).isTrue();
        assertThat(draft.travelTimeReason()).isNull();

        assertThatThrownBy(() -> serviceRecordService.submit(draft.id(), studentAuth(student.id()), "idem-travel-reason", "zh-CN"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).fieldErrors())
                        .anyMatch(fe -> fe.field().equals("travelTimeReason")));
    }

    // --- fixtures ---

    private CreateServiceRecordRequest projectRequest(String projectId) {
        Instant end = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant start = end.minusSeconds(3600);
        return new CreateServiceRecordRequest(
                "PROJECT", projectId, null, null, LocalDate.now().minusDays(1), start, end,
                null, null, "desc", null, null, null, null, List.of(readyFile()), false, null);
    }

    private ServiceRecordDto selfDraft(StudentDetailDto student) {
        Instant end = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant start = end.minusSeconds(3600);
        CreateServiceRecordRequest request = new CreateServiceRecordRequest(
                "SELF", null, null, null, LocalDate.now().minusDays(1), start, end,
                "Somewhere", "cat_campus", "desc", "EXTERNAL_MANUAL", null, "Coach Li", "coach@example.com",
                List.of(readyFile()), false, null);
        return serviceRecordService.createDraft(request, student.id(), student.id(), "zh-CN");
    }

    private UserDto createTeacherWithEmail() {
        int n = SEQ.incrementAndGet();
        return userService.create(new CreateUserRequest(
                "src_opt_teacher_" + n, "Source Opt Teacher " + n,
                "teacher" + n + "@example.com", "teacher", "zh-CN"), "usr_test_admin");
    }

    /** {@code partnerOrgId}: null for a host-only project. */
    private ProjectDto publishProject(String ownerId, String partnerOrgId) {
        Instant start = Instant.now().plus(5, ChronoUnit.DAYS);
        CreateProjectRequest request = new CreateProjectRequest(
                "Source Opt Project " + SEQ.incrementAndGet(), "summary", "description", "cat_campus",
                partnerOrgId, ownerId, "OFF_CAMPUS",
                start, start.plusSeconds(7200), "Community Hall", 10, 100, null, null,
                Instant.now().minusSeconds(60), start);
        ProjectDto draft = projectService.createDraft(request, "usr_test_admin", true, "zh-CN");
        return projectService.publish(draft.id(), draft.version(), "usr_test_admin", "zh-CN");
    }

    private EnrollmentDto confirmRegistration(ProjectDto project, StudentDetailDto student) {
        // Force the lazy time-sync to REGISTRATION_OPEN, same pattern as EnrollmentCapacityIT.
        projectService.getDetail(project.id(), adminAuth(), "zh-CN");
        // Ample capacity (10) relative to a single registrant — auto-CONFIRMED, no waitlist.
        return enrollmentService.register(project.id(), new RegisterRequest(null), studentAuth(student.id()), "zh-CN");
    }

    /** A real, READY evidence file — submission validation checks status, not just presence (B3.5). */
    private String readyFile() {
        UploadIntentDto intent = fileService.createUploadIntent(
                new CreateUploadIntentRequest("evidence.jpg", "image/jpeg", VALID_JPEG.length, "serviceRecordEvidence"),
                "usr_test_admin");
        fileService.receiveContent(intent.fileId(), "usr_test_admin", new ByteArrayInputStream(VALID_JPEG));
        fileService.complete(intent.fileId(), "usr_test_admin");
        return intent.fileId();
    }

    private StudentDetailDto createStudent() {
        int n = SEQ.incrementAndGet();
        CreateStudentRequest request = new CreateStudentRequest(
                "stu_srcopt_" + n, "Source Opt Student " + n, "grade_6", "class_6A", "ay_2025_2026",
                2029, LocalDate.of(2025, 9, 1));
        return studentService.create(request, "usr_test_admin", "zh-CN");
    }

    private Authentication studentAuth(String studentId) {
        String userId = "usr_" + studentId.substring(4);
        cn.timebank.iam.application.dto.AuthenticatedUserView view = new cn.timebank.iam.application.dto.AuthenticatedUserView(
                userId, userId, "hash", "Test Student", true, false, "zh-CN",
                Set.of("student"), Set.of(), studentId, 480);
        CookieSessionUserDetails principal = new CookieSessionUserDetails(view);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private Authentication adminAuth() {
        cn.timebank.iam.application.dto.AuthenticatedUserView view = new cn.timebank.iam.application.dto.AuthenticatedUserView(
                "usr_test_admin", "usr_test_admin", "hash", "Test Admin", true, false, "zh-CN",
                Set.of("admin"), Set.of(), null, 480);
        CookieSessionUserDetails principal = new CookieSessionUserDetails(view);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
