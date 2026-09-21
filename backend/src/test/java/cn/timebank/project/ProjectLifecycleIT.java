package cn.timebank.project;

import cn.timebank.audit.application.AuditLogQueryService;
import cn.timebank.audit.application.dto.AuditLogDto;
import cn.timebank.enrollment.application.EnrollmentService;
import cn.timebank.enrollment.application.dto.EnrollmentDto;
import cn.timebank.enrollment.application.dto.EnrollmentRequests.RegisterRequest;
import cn.timebank.iam.application.dto.AuthenticatedUserView;
import cn.timebank.organization.application.OrganizationService;
import cn.timebank.organization.application.dto.OrganizationDto;
import cn.timebank.organization.application.dto.OrganizationRequests;
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
import cn.timebank.project.domain.ProjectStatus;
import cn.timebank.project.infrastructure.ProjectRepository;
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
    @Autowired
    private OrganizationService organizationService;
    @Autowired
    private ProjectRepository projectRepository;

    @Test
    void registrationOpenArchivesAutomaticallyWithZeroRegistrantsInsteadOfClosing() {
        // The window has to be live at publish time — issue 130 refuses a draft
        // whose registration has already closed, because publishing one would
        // archive it on the next read with no error anywhere. So this reaches
        // the same state the way it actually happens: publish with a live
        // window, then let the window elapse with nobody signed up.
        Instant now = Instant.now();
        ProjectDto draft = createDraft(now.minusSeconds(60), now.plusSeconds(3600));
        ProjectDto published = projectService.publish(draft.id(), draft.version(), adminAuth(), "zh-CN");
        assertThat(published.status()).isEqualTo("PUBLISHED");

        UpdateProjectRequest closeItNow = new UpdateProjectRequest(
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, now.minusSeconds(10), published.version());
        projectService.updateDraft(published.id(), closeItNow, adminAuth(), "zh-CN");

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
        ProjectDto published = projectService.publish(draft.id(), draft.version(), adminAuth(), "zh-CN");
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
        ProjectDto updated = projectService.updateDraft(registrationOpen.id(), closeItNow, adminAuth(), "zh-CN");

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
        ProjectDto published = projectService.publish(draft.id(), draft.version(), adminAuth(), "zh-CN");
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
        ProjectDto updated = projectService.updateDraft(registrationOpen.id(), pushToPast, adminAuth(), "zh-CN");

        // Issue 120: the count the client needs travels as a coded field error,
        // not buried in an English sentence it would have to parse.
        assertThatThrownBy(() -> projectService.archive(updated.id(), updated.version(), "usr_test_admin", "zh-CN"))
                .isInstanceOf(cn.timebank.common.error.ApiException.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(cn.timebank.common.error.ApiException.class))
                .satisfies(ex -> {
                    assertThat(ex.fieldErrors()).extracting(cn.timebank.common.error.FieldErrorDetail::code)
                            .containsExactly("UNREVIEWED_RECORDS");
                    assertThat(ex.fieldErrors().get(0).message()).isEqualTo("1");
                    // The sentence must not name an action that no longer exists.
                    assertThat(ex.getMessage()).doesNotContain("Cancel and archive");
                });

        // The override does not re-check the gate.
        ProjectDto reread = projectService.getDetail(updated.id(), adminAuth(), "zh-CN");
        assertThat(reread.status()).isEqualTo("COMPLETED");
        ProjectDto cancelled = projectService.cancel(reread.id(),
                new CancelProjectRequest("Student never submitted evidence; abandoning review.", reread.version()),
                "usr_test_admin", "zh-CN");
        assertThat(cancelled.status()).isEqualTo("ARCHIVED");
    }

    /**
     * Issue 119. A completed project's recognised hours are already credited to
     * students' accounts, and an archived one is terminal — neither may be
     * edited. Before this, {@code updateDraft} had no status guard at all
     * despite its name, so both went through.
     */
    @Test
    void updateIsRejectedOnACompletedProject() {
        Instant now = Instant.now();
        CreateProjectRequest request = new CreateProjectRequest(
                "Frozen Test Project", "summary", "description", "cat_campus", null, null, "ON_CAMPUS",
                now.plusSeconds(7200), now.plusSeconds(10800), "Hall", 1, 100, null, null,
                now.minusSeconds(60), now.plusSeconds(3600));
        ProjectDto draft = projectService.createDraft(request, "usr_test_admin", false, "zh-CN");
        ProjectDto published = projectService.publish(draft.id(), draft.version(), adminAuth(), "zh-CN");
        ProjectDto registrationOpen = projectService.getDetail(published.id(), adminAuth(), "zh-CN");

        // A confirmed registrant is what makes the cascade end at COMPLETED
        // rather than self-archiving at the registration-close boundary.
        StudentDetailDto student = studentService.create(
                new CreateStudentRequest("stu_frozen_test", "Frozen Test Student", "grade_6", "class_6A",
                        "ay_2025_2026", 2029, LocalDate.of(2025, 9, 1)),
                "usr_test_admin", "zh-CN");
        enrollmentService.register(registrationOpen.id(), new RegisterRequest(null), studentAuth(student.id()), "zh-CN");

        // Still REGISTRATION_OPEN at this point, so this edit is allowed — the
        // guard reads the status *before* the new dates apply, which is what
        // lets a live project be corrected into the past at all.
        UpdateProjectRequest pushToPast = new UpdateProjectRequest(
                null, null, null, null, null, null, null,
                now.minusSeconds(40), now.minusSeconds(20), null, null, null, null, null,
                null, now.minusSeconds(50), registrationOpen.version());
        ProjectDto updated = projectService.updateDraft(registrationOpen.id(), pushToPast, adminAuth(), "zh-CN");
        ProjectDto completed = projectService.getDetail(updated.id(), adminAuth(), "zh-CN");
        assertThat(completed.status()).isEqualTo("COMPLETED");

        UpdateProjectRequest renameIt = new UpdateProjectRequest(
                "Renamed After Completion", null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, completed.version());
        assertThatThrownBy(() -> projectService.updateDraft(completed.id(), renameIt, adminAuth(), "zh-CN"))
                .isInstanceOf(cn.timebank.common.error.ApiException.class)
                .hasMessageContaining("cannot be edited");

        // ...and the title really is untouched, not merely reported as rejected.
        assertThat(projectService.getDetail(completed.id(), adminAuth(), "zh-CN").title())
                .isEqualTo("Frozen Test Project");
    }

    @Test
    void updateIsRejectedOnAnArchivedProject() {
        ProjectDto draft = createDraft("Archived Edit Test", Instant.now().plusSeconds(1000), Instant.now().plusSeconds(2000));
        ProjectDto archived = projectService.cancel(draft.id(),
                new CancelProjectRequest("Discarding this draft.", draft.version()), "usr_test_admin", "zh-CN");
        assertThat(archived.status()).isEqualTo("ARCHIVED");

        UpdateProjectRequest renameIt = new UpdateProjectRequest(
                "Renamed After Archiving", null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, archived.version());
        assertThatThrownBy(() -> projectService.updateDraft(archived.id(), renameIt, adminAuth(), "zh-CN"))
                .isInstanceOf(cn.timebank.common.error.ApiException.class)
                .hasMessageContaining("cannot be edited");
        assertThat(projectService.getDetail(archived.id(), adminAuth(), "zh-CN").title())
                .isEqualTo("Archived Edit Test");
    }

    /**
     * Issue 122. Saving a project without touching its partner organization used
     * to 500: `updateDraft` deleted the `project_organization` row and saved an
     * identical one, and Hibernate orders inserts before deletes at flush, so
     * the insert hit `uk_project_organization_role`. That is the most ordinary
     * edit there is — change a location, leave the partner alone.
     */
    @Test
    void savingAProjectTwiceWithoutChangingItsPartnerOrganizationSucceeds() {
        OrganizationDto partner = organizationService.create(
                new OrganizationRequests.CreateOrganizationRequest(
                        "Partner Flush Test Org", "COMMUNITY", "A partner", "Contact",
                        "contact@example-township-school.edu", "13800000000", "Address"),
                "usr_test_admin");

        Instant start = Instant.now().plus(5, ChronoUnit.DAYS);
        CreateProjectRequest request = new CreateProjectRequest(
                "Partner Flush Test Project", "summary", "description", "cat_campus", partner.id(), null, "OFF_CAMPUS",
                start, start.plusSeconds(7200), "Hall", 10, 100, null, null,
                Instant.now().plusSeconds(1000), Instant.now().plusSeconds(2000));
        ProjectDto draft = projectService.createDraft(request, "usr_test_admin", false, "zh-CN");

        // The same partner id, resent — which is exactly what the edit dialog does.
        UpdateProjectRequest moveIt = new UpdateProjectRequest(
                null, null, null, null, partner.id(), null, null,
                null, null, "Library, second floor", null, null, null, null, null, null, draft.version());
        ProjectDto updated = projectService.updateDraft(draft.id(), moveIt, adminAuth(), "zh-CN");
        assertThat(updated.location()).isEqualTo("Library, second floor");
        assertThat(updated.organizationId()).isEqualTo(partner.id());

        // And changing it really does still move the row, rather than the guard
        // above quietly skipping the write.
        OrganizationDto other = organizationService.create(
                new OrganizationRequests.CreateOrganizationRequest(
                        "Partner Flush Test Org Two", "COMMUNITY", "Another partner", "Contact",
                        "contact2@example-township-school.edu", "13800000001", "Address"),
                "usr_test_admin");
        UpdateProjectRequest swapPartner = new UpdateProjectRequest(
                null, null, null, null, other.id(), null, null,
                null, null, null, null, null, null, null, null, null, updated.version());
        ProjectDto swapped = projectService.updateDraft(draft.id(), swapPartner, adminAuth(), "zh-CN");
        assertThat(swapped.organizationId()).isEqualTo(other.id());
        assertThat(swapped.organizationName()).isEqualTo("Partner Flush Test Org Two");
    }

    /** The other half of issue 119's guard: a live project is still correctable. */
    @Test
    void updateIsStillAllowedOnAPublishedProject() {
        ProjectDto draft = createDraft("Editable While Live", Instant.now().plusSeconds(1000), Instant.now().plusSeconds(2000));
        ProjectDto published = projectService.publish(draft.id(), draft.version(), adminAuth(), "zh-CN");

        UpdateProjectRequest moveIt = new UpdateProjectRequest(
                null, null, null, null, null, null, null,
                null, null, "Library, second floor", null, null, null, null, null, null, published.version());
        ProjectDto updated = projectService.updateDraft(published.id(), moveIt, adminAuth(), "zh-CN");
        assertThat(updated.location()).isEqualTo("Library, second floor");
    }

    @Test
    void cancelArchivesALivePublishedProjectDirectlyWithoutPassingThroughClosed() {
        ProjectDto draft = createDraft(Instant.now().plusSeconds(1000), Instant.now().plusSeconds(2000));
        ProjectDto published = projectService.publish(draft.id(), draft.version(), adminAuth(), "zh-CN");

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
        ProjectDto published = projectService.publish(notDraft.id(), notDraft.version(), adminAuth(), "zh-CN");

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

    @Test
    void theSweepAdvancesAProjectNobodyHasReadAndLeavesAFutureOneAlone() {
        Instant now = Instant.now();

        // A project that has fallen behind the clock with nobody looking at it.
        // Published with a live window (issue 130 refuses a dead one), then the
        // close pushed into the past — which is the shape a real project reaches
        // when its deadline passes overnight.
        ProjectDto draft = createDraft("Sweep Stale Project", now.minusSeconds(60), now.plusSeconds(3600));
        ProjectDto published = projectService.publish(draft.id(), draft.version(), adminAuth(), "zh-CN");
        UpdateProjectRequest closeItNow = new UpdateProjectRequest(
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, now.minusSeconds(10), published.version());
        projectService.updateDraft(published.id(), closeItNow, adminAuth(), "zh-CN");

        // The control: everything still ahead of it, so the sweep must leave it
        // exactly where it is. (It would survive an over-broad query too — the
        // state machine is itself time-guarded — so what this actually pins is
        // that the sweep never advances a project early.)
        ProjectDto futureDraft = createDraft("Sweep Future Project", now.plusSeconds(3600), now.plusSeconds(7200));
        ProjectDto future = projectService.publish(futureDraft.id(), futureDraft.version(), adminAuth(), "zh-CN");

        // Read the rows, not the service: every service read syncs, so asking
        // `getDetail` whether the row was stale would be what fixed it.
        assertThat(storedStatus(published.id())).isNotEqualTo(ProjectStatus.ARCHIVED);
        assertThat(storedStatus(future.id())).isEqualTo(ProjectStatus.PUBLISHED);

        int advanced = projectService.reconcileTimeBasedStatuses();

        assertThat(advanced).isGreaterThanOrEqualTo(1);
        // Zero registrants at the close, so the same auto-archive the lazy sync
        // performs — reached here without anyone opening the project.
        assertThat(storedStatus(published.id())).isEqualTo(ProjectStatus.ARCHIVED);
        assertThat(storedStatus(future.id())).isEqualTo(ProjectStatus.PUBLISHED);
    }

    private ProjectStatus storedStatus(String projectId) {
        return projectRepository.findById(projectId).orElseThrow().getStatus();
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
