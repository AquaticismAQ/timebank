package cn.timebank.project;

import cn.timebank.audit.application.AuditLogQueryService;
import cn.timebank.audit.application.dto.AuditLogDto;
import cn.timebank.enrollment.application.EnrollmentService;
import cn.timebank.enrollment.application.dto.EnrollmentDto;
import cn.timebank.enrollment.application.dto.EnrollmentRequests.RegisterRequest;
import cn.timebank.iam.application.dto.AuthenticatedUserView;
import cn.timebank.project.application.ProjectService;
import cn.timebank.project.application.dto.ProjectDto;
import cn.timebank.project.application.dto.ProjectRequests.CancelProjectRequest;
import cn.timebank.project.application.dto.ProjectRequests.CreateProjectRequest;
import cn.timebank.project.application.dto.ProjectRequests.UpdateProjectRequest;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.student.application.StudentService;
import cn.timebank.student.application.dto.StudentDetailDto;
import cn.timebank.student.application.dto.StudentRequests.CreateStudentRequest;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B4's exit criteria: action endpoints only for status (a `PATCH` cannot
 * carry `status` at all — {@code UpdateProjectRequest} has no such field,
 * a compile-time guarantee, not merely a runtime check), and time-derived
 * statuses sync lazily on read/action access. Reworked per
 * `Docs/optimization/02-PorjectOrganization.md` §2: {@code CLOSED} is now
 * purely time-derived (registration window elapsed) sitting between {@code
 * REGISTRATION_OPEN} and {@code IN_PROGRESS}, the normal {@code archive()}
 * path is gated from {@code COMPLETED} on every confirmed student's record
 * being reviewed, and {@code cancel()} is the separate, reason-required
 * manager override that bypasses that gate.
 */
class ProjectLifecycleIT extends AbstractIntegrationTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private StudentService studentService;
    @Autowired
    private EnrollmentService enrollmentService;
    @Autowired
    private AuditLogQueryService auditLogQueryService;

    @Test
    void registrationOpenArchivesAutomaticallyWithZeroRegistrantsInsteadOfClosing() {
        // registrationOpenAt already past; registrationCloseAt past too, but startAt
        // (createDraft's default, 5 days out) is not — with nobody registered, the
        // registration-close boundary should archive directly rather than land on
        // CLOSED (which would otherwise just sit unused until startAt).
        ProjectDto draft = createDraft(Instant.now().minusSeconds(60), Instant.now().minusSeconds(10));
        ProjectDto published = projectService.publish(draft.id(), draft.version(), "usr_test_admin", "zh-CN");
        assertThat(published.status()).isEqualTo("PUBLISHED");

        ProjectDto reread = projectService.getDetail(published.id(), adminAuth(), "zh-CN");
        assertThat(reread.status()).isEqualTo("ARCHIVED");

        Page<AuditLogDto> logs = auditLogQueryService.search(null, null, "project", reread.id(), null, null, null, PageRequest.of(0, 10));
        assertThat(logs.getContent()).extracting(AuditLogDto::action).contains("project.autoArchiveNoRegistrants");
        assertThat(logs.getContent()).extracting(AuditLogDto::reasonCode).contains("NO_REGISTRANTS_AT_REGISTRATION_CLOSE");
    }

    @Test
    void registrationOpenClosesNormallyInsteadOfArchivingWhenThereIsAConfirmedRegistrant() {
        Instant now = Instant.now();
        // Real future registration window, long enough to register a student in real time.
        CreateProjectRequest request = new CreateProjectRequest(
                "Has Registrant Test Project", "summary", "description", "cat_campus", null, null, "ON_CAMPUS",
                now.plusSeconds(7200), now.plusSeconds(10800), "Hall", 5, 100, null, null,
                now.minusSeconds(60), now.plusSeconds(3600));
        ProjectDto draft = projectService.createDraft(request, "usr_test_admin", false, "zh-CN");
        ProjectDto published = projectService.publish(draft.id(), draft.version(), "usr_test_admin", "zh-CN");
        ProjectDto registrationOpen = projectService.getDetail(published.id(), adminAuth(), "zh-CN");
        assertThat(registrationOpen.status()).isEqualTo("REGISTRATION_OPEN");

        StudentDetailDto student = studentService.create(
                new CreateStudentRequest("stu_has_reg_test", "Has Registrant Test Student", "grade_6", "class_6A",
                        "ay_2025_2026", 2029, LocalDate.of(2025, 9, 1)),
                "usr_test_admin", "zh-CN");
        EnrollmentDto enrollment = enrollmentService.register(
                registrationOpen.id(), new RegisterRequest(null), studentAuth(student.id()), "zh-CN");
        assertThat(enrollment.status()).isEqualTo("CONFIRMED");

        // Admin pushes registrationCloseAt into the past (startAt stays in the future) —
        // with a real confirmed registrant now, the next sync must land on CLOSED, not ARCHIVED.
        UpdateProjectRequest closeItNow = new UpdateProjectRequest(
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, now.minusSeconds(10), registrationOpen.version());
        ProjectDto updated = projectService.updateDraft(registrationOpen.id(), closeItNow, "usr_test_admin", true, "zh-CN");

        ProjectDto reread = projectService.getDetail(updated.id(), adminAuth(), "zh-CN");
        assertThat(reread.status()).isEqualTo("CLOSED");
    }

    @Test
    void archivingADraftDirectlyIsRejected() {
        ProjectDto draft = createDraft(Instant.now().plusSeconds(1000), Instant.now().plusSeconds(2000));
        assertThatThrownBy(() -> projectService.archive(draft.id(), draft.version(), "usr_test_admin", "zh-CN"))
                .isInstanceOf(cn.timebank.common.error.ApiException.class);
    }

    @Test
    void cancelArchivesADraftDirectly() {
        ProjectDto draft = createDraft(Instant.now().plusSeconds(1000), Instant.now().plusSeconds(2000));
        ProjectDto cancelled = projectService.cancel(draft.id(),
                new CancelProjectRequest("Unwanted draft, discarding it.", draft.version()),
                "usr_test_admin", "zh-CN");
        assertThat(cancelled.status()).isEqualTo("ARCHIVED");
    }

    @Test
    void archiveRejectsACompletedProjectWithAConfirmedButUnreviewedStudentAndCancelOverridesIt() {
        Instant now = Instant.now();
        // Registration window open long enough to register a student in real time.
        CreateProjectRequest request = new CreateProjectRequest(
                "Gate Test Project", "summary", "description", "cat_campus", null, null, "ON_CAMPUS",
                now.plusSeconds(7200), now.plusSeconds(10800), "Hall", 1, 100, null, null,
                now.minusSeconds(60), now.plusSeconds(3600));
        ProjectDto draft = projectService.createDraft(request, "usr_test_admin", false, "zh-CN");
        ProjectDto published = projectService.publish(draft.id(), draft.version(), "usr_test_admin", "zh-CN");
        ProjectDto registrationOpen = projectService.getDetail(published.id(), adminAuth(), "zh-CN");
        assertThat(registrationOpen.status()).isEqualTo("REGISTRATION_OPEN");

        StudentDetailDto student = studentService.create(
                new CreateStudentRequest("stu_gate_test", "Gate Test Student", "grade_6", "class_6A",
                        "ay_2025_2026", 2029, LocalDate.of(2025, 9, 1)),
                "usr_test_admin", "zh-CN");
        EnrollmentDto enrollment = enrollmentService.register(
                registrationOpen.id(), new RegisterRequest(null), studentAuth(student.id()), "zh-CN");
        assertThat(enrollment.status()).isEqualTo("CONFIRMED");

        // Admin pushes every remaining deadline into the past (registrationOpenAt
        // stays at its original now-60s, left null/unchanged here) so the next sync
        // cascades all the way to COMPLETED — no record was ever submitted for
        // the confirmed student, so the archive gate must reject.
        UpdateProjectRequest pushToPast = new UpdateProjectRequest(
                null, null, null, null, null, null, null,
                now.minusSeconds(40), now.minusSeconds(20), null, null, null, null, null,
                null, now.minusSeconds(50), registrationOpen.version());
        ProjectDto updated = projectService.updateDraft(registrationOpen.id(), pushToPast, "usr_test_admin", true, "zh-CN");

        assertThatThrownBy(() -> projectService.archive(updated.id(), updated.version(), "usr_test_admin", "zh-CN"))
                .isInstanceOf(cn.timebank.common.error.ApiException.class);

        // The override does not re-check the gate.
        ProjectDto reread = projectService.getDetail(updated.id(), adminAuth(), "zh-CN");
        assertThat(reread.status()).isEqualTo("COMPLETED");
        ProjectDto cancelled = projectService.cancel(reread.id(),
                new CancelProjectRequest("Student never submitted evidence; abandoning review.", reread.version()),
                "usr_test_admin", "zh-CN");
        assertThat(cancelled.status()).isEqualTo("ARCHIVED");
    }

    @Test
    void cancelArchivesALivePublishedProjectDirectlyWithoutPassingThroughClosed() {
        ProjectDto draft = createDraft(Instant.now().plusSeconds(1000), Instant.now().plusSeconds(2000));
        ProjectDto published = projectService.publish(draft.id(), draft.version(), "usr_test_admin", "zh-CN");

        ProjectDto cancelled = projectService.cancel(published.id(),
                new CancelProjectRequest("Partner organization withdrew.", published.version()),
                "usr_test_admin", "zh-CN");
        assertThat(cancelled.status()).isEqualTo("ARCHIVED");
    }

    @Test
    void listFiltersByStatusAndSearchesByTitleOnly() {
        ProjectDto draftProject = createDraft("Filter Test Draft Alpha",
                Instant.now().plusSeconds(1000), Instant.now().plusSeconds(2000));
        ProjectDto notDraft = createDraft("Filter Test Draft Beta",
                Instant.now().plusSeconds(1000), Instant.now().plusSeconds(2000));
        ProjectDto published = projectService.publish(notDraft.id(), notDraft.version(), "usr_test_admin", "zh-CN");

        // status filter narrows results: the still-draft project is included, the published one is not.
        Page<ProjectDto> draftOnly = projectService.list(PageRequest.of(0, 100), "DRAFT", null, null, null, null, adminAuth(), "zh-CN");
        assertThat(draftOnly.getContent()).extracting(ProjectDto::id).contains(draftProject.id());
        assertThat(draftOnly.getContent()).extracting(ProjectDto::id).doesNotContain(published.id());

        // search matches title.
        Page<ProjectDto> searchMatch = projectService.list(PageRequest.of(0, 100), null, null, null, null,
                "Filter Test Draft Alpha", adminAuth(), "zh-CN");
        assertThat(searchMatch.getContent()).extracting(ProjectDto::id).containsExactly(draftProject.id());

        // an unmatched search returns an empty page, not an error.
        Page<ProjectDto> searchNoMatch = projectService.list(PageRequest.of(0, 100), null, null, null, null,
                "NoSuchProjectTitleXYZ", adminAuth(), "zh-CN");
        assertThat(searchNoMatch.getContent()).isEmpty();
    }

    private ProjectDto createDraft(Instant registrationOpenAt, Instant registrationCloseAt) {
        return createDraft("Test Project", registrationOpenAt, registrationCloseAt);
    }

    private ProjectDto createDraft(String title, Instant registrationOpenAt, Instant registrationCloseAt) {
        Instant start = Instant.now().plus(5, ChronoUnit.DAYS);
        Instant end = start.plusSeconds(7200);
        CreateProjectRequest request = new CreateProjectRequest(
                title, "A summary", "A description", "cat_campus", null, null, "ON_CAMPUS",
                start, end, "Main Hall", 10, 100, null, null, registrationOpenAt, registrationCloseAt);
        return projectService.createDraft(request, "usr_test_admin", false, "zh-CN");
    }

    private Authentication studentAuth(String studentId) {
        AuthenticatedUserView view = new AuthenticatedUserView(
                "usr_" + studentId.substring(4), "usr_" + studentId.substring(4), "hash", "Test Student",
                true, false, "zh-CN", Set.of("student"), Set.of(), studentId, 480);
        CookieSessionUserDetails principal = new CookieSessionUserDetails(view);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private Authentication adminAuth() {
        AuthenticatedUserView view = new AuthenticatedUserView(
                "usr_test_admin", "usr_test_admin", "hash", "Test Admin", true, false, "zh-CN",
                Set.of("admin"), Set.of(), null, 480);
        CookieSessionUserDetails principal = new CookieSessionUserDetails(view);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
