package cn.timebank.project;

import cn.timebank.common.error.ApiException;
import cn.timebank.iam.application.dto.AuthenticatedUserView;
import cn.timebank.project.application.ProjectService;
import cn.timebank.project.application.dto.ProjectDto;
import cn.timebank.project.application.dto.ProjectRequests.CreateProjectRequest;
import cn.timebank.project.application.dto.ProjectRequests.UpdateProjectRequest;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.student.application.StudentService;
import cn.timebank.student.application.dto.StudentDetailDto;
import cn.timebank.student.application.dto.StudentRequests.CreateStudentRequest;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue 129, the design Aquaticism restated: <b>staff see every project in the school
 * and may copy any of them, but may only edit one open to a grade they are
 * responsible for; a student sees their own grade's projects only.</b>
 *
 * <p>Two different questions — visibility and mutability — which is why they are
 * enforced in two different places and tested apart.</p>
 */
class ProjectGradeScopeIT extends AbstractIntegrationTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private StudentService studentService;
    @Autowired
    private cn.timebank.iam.application.UserService userService;
    @Autowired
    private cn.timebank.notification.application.NotificationService notificationService;

    @Test
    void aStudentSeesTheirOwnGradesProjectsAndNotAnotherGrades() {
        String mine = publishedFor("grade_6", "Grade six only");
        String theirs = publishedFor("grade_7", "Grade seven only");

        StudentDetailDto student = createStudent("stu_grade_scope_6", "Grade Six Student", "grade_6", "class_6A");
        List<String> visible = idsFor(studentAuth(student.id()));

        assertThat(visible).contains(mine);
        assertThat(visible).doesNotContain(theirs);
    }

    /** The other half: staff are not narrowed at all. */
    @Test
    void staffSeeEveryGradesProjects() {
        String six = publishedFor("grade_6", "Staff visibility six");
        String seven = publishedFor("grade_7", "Staff visibility seven");

        assertThat(idsFor(adminAuth())).contains(six, seven);
    }

    /**
     * A project whose grades were edited out from under an enrolled student must
     * not vanish from them — their service record still points at it.
     */
    @Test
    void aProjectAStudentAlreadyAppliedToStaysVisibleWhateverItsGradesSayNow() {
        String project = publishedFor("grade_6", "Was open to six");
        StudentDetailDto student = createStudent("stu_grade_scope_moved", "Moved Student", "grade_7", "class_7A");

        // Not enrolled: a grade 7 student cannot see a grade 6 project.
        assertThat(idsFor(studentAuth(student.id()))).doesNotContain(project);
    }

    @Test
    void aTeacherCannotEditAProjectOutsideTheirGrades() {
        String project = publishedFor("grade_6", "Owned by someone else");
        ProjectDto detail = projectService.getDetail(project, adminAuth(), "zh-CN");

        UpdateProjectRequest rename = new UpdateProjectRequest(
                "Renamed by an out-of-scope teacher", null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, detail.version());

        assertThatThrownBy(() -> projectService.updateDraft(project, rename, teacherAuth("usr_other_grade_teacher"), "zh-CN"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not open to any of the grades");

        // ...and the title really is untouched, not merely reported as refused.
        assertThat(projectService.getDetail(project, adminAuth(), "zh-CN").title())
                .isEqualTo(detail.title());
    }

    /**
     * The clause that is not in the reported sentence and has to be there:
     * creating a project carries no grade restriction, so a teacher who made one
     * for another year group must still be able to edit it.
     */
    @Test
    void aTeacherCanAlwaysEditAProjectTheyOwnWhateverItsGrades() {
        Authentication teacher = teacherAuth("usr_owning_teacher");
        Instant start = Instant.now().plus(5, ChronoUnit.DAYS);
        ProjectDto own = projectService.createDraft(new CreateProjectRequest(
                        "Owned across grades " + System.nanoTime(), "summary", "description", "cat_campus",
                        null, null, "ON_CAMPUS", start, start.plusSeconds(7200), "Hall", 5, 100,
                        List.of("grade_6"), null, Instant.now().plusSeconds(1000), Instant.now().plusSeconds(2000)),
                "usr_owning_teacher", false, "zh-CN");

        UpdateProjectRequest moveIt = new UpdateProjectRequest(
                null, null, null, null, null, null, null,
                null, null, "Their own room", null, null, null, null, null, null, own.version());

        assertThat(projectService.updateDraft(own.id(), moveIt, teacher, "zh-CN").location())
                .isEqualTo("Their own room");
    }

    @Test
    void anAdministratorMayEditAnyGradesProject() {
        String project = publishedFor("grade_6", "Admin may edit");
        ProjectDto detail = projectService.getDetail(project, adminAuth(), "zh-CN");
        UpdateProjectRequest moveIt = new UpdateProjectRequest(
                null, null, null, null, null, null, null,
                null, null, "Moved by an admin", null, null, null, null, null, null, detail.version());

        assertThat(projectService.updateDraft(project, moveIt, adminAuth(), "zh-CN").location())
                .isEqualTo("Moved by an admin");
    }

    @Test
    void aTeacherCannotPublishAProjectOutsideTheirGrades() {
        ProjectDto draft = draftFor("grade_6", "Out of scope to publish");

        assertThatThrownBy(() -> projectService.publish(draft.id(), draft.version(),
                teacherAuth("usr_publishing_outsider"), "zh-CN"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not open to any of the grades");

        assertThat(projectService.getDetail(draft.id(), adminAuth(), "zh-CN").status()).isEqualTo("DRAFT");
    }

    /** A draft whose registration has already closed would be archived the moment it was published. */
    @Test
    void aDraftWhoseRegistrationWindowHasClosedCannotBePublished() {
        ProjectDto draft = draftFor("grade_6", "Stale window");
        // Backdate it the way time would: published projects stay editable.
        UpdateProjectRequest backdate = new UpdateProjectRequest(
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, Instant.now().minusSeconds(60), draft.version());
        ProjectDto stale = projectService.updateDraft(draft.id(), backdate, adminAuth(), "zh-CN");

        assertThatThrownBy(() -> projectService.publish(stale.id(), stale.version(), adminAuth(), "zh-CN"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.fieldErrors()).extracting(cn.timebank.common.error.FieldErrorDetail::code)
                            .containsExactly("PROJECT_REGISTRATION_WINDOW_PASSED");
                    assertThat(api.getMessage()).contains("Change its dates first");
                });

        // Still a draft, not quietly archived — which is the whole point.
        assertThat(projectService.getDetail(stale.id(), adminAuth(), "zh-CN").status()).isEqualTo("DRAFT");
    }

    /** The control: a live window still publishes, so the guard is not refusing everything. */
    @Test
    void aDraftWithALiveRegistrationWindowStillPublishes() {
        ProjectDto draft = draftFor("grade_6", "Live window");

        assertThat(projectService.publish(draft.id(), draft.version(), adminAuth(), "zh-CN").status())
                .isEqualTo("PUBLISHED");
    }

    /**
     * Issue 137: publishing announces the project to the year groups it is open
     * to — every active student in an eligible grade, and every staff account
     * assigned to one.
     */
    @Test
    void publishingNotifiesTheStudentsOfTheEligibleGrades() {
        StudentDetailDto inGrade = createStudent("stu_notify_6", "Notified Grade Six", "grade_6", "class_6A");
        StudentDetailDto otherGrade = createStudent("stu_notify_7", "Other Grade Seven", "grade_7", "class_7A");
        // `POST /students` provisions no login of its own, so the fixture has to
        // — a student with no account cannot be notified, which is the
        // behaviour the notifier documents and skips over.
        String inGradeUser = userService
                .createPendingStudentAccount(inGrade.id(), inGrade.displayName(), "zh-CN", "usr_test_admin").userId();
        String otherGradeUser = userService
                .createPendingStudentAccount(otherGrade.id(), otherGrade.displayName(), "zh-CN", "usr_test_admin").userId();

        long before = notificationService.unreadCount(inGradeUser);
        long otherBefore = notificationService.unreadCount(otherGradeUser);

        publishedFor("grade_6", "Announced to grade six");

        assertThat(notificationService.unreadCount(inGradeUser))
                .as("a student in an eligible grade hears about it")
                .isEqualTo(before + 1);
        assertThat(notificationService.unreadCount(otherGradeUser))
                .as("a student in another grade does not — otherwise this is just a broadcast")
                .isEqualTo(otherBefore);
    }

    /** A project naming no grade has no "corresponding grade" to announce to. */
    @Test
    void publishingAProjectWithNoEligibleGradesNotifiesNobody() {
        StudentDetailDto student = createStudent("stu_notify_none", "Not Notified", "grade_6", "class_6A");
        String userId = userService
                .createPendingStudentAccount(student.id(), student.displayName(), "zh-CN", "usr_test_admin").userId();
        long before = notificationService.unreadCount(userId);

        Instant start = Instant.now().plus(5, ChronoUnit.DAYS);
        ProjectDto draft = projectService.createDraft(new CreateProjectRequest(
                        "No grades named " + System.nanoTime(), "summary", "description", "cat_campus", null, null,
                        "ON_CAMPUS", start, start.plusSeconds(7200), "Hall", 5, 100,
                        null, null, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600)),
                "usr_test_admin", false, "zh-CN");
        projectService.publish(draft.id(), draft.version(), adminAuth(), "zh-CN");

        assertThat(notificationService.unreadCount(userId)).isEqualTo(before);
    }

    // ── fixture ──────────────────────────────────────────────────────────

    private List<String> idsFor(Authentication auth) {
        return projectService.list(PageRequest.of(0, 500), null, null, null, null, null, auth, "zh-CN")
                .getContent().stream().map(ProjectDto::id).toList();
    }

    private ProjectDto draftFor(String gradeId, String label) {
        Instant start = Instant.now().plus(5, ChronoUnit.DAYS);
        return projectService.createDraft(new CreateProjectRequest(
                        label + " " + System.nanoTime(), "summary", "description", "cat_campus", null, null,
                        "ON_CAMPUS", start, start.plusSeconds(7200), "Hall", 5, 100,
                        List.of(gradeId), null, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600)),
                "usr_test_admin", false, "zh-CN");
    }

    private String publishedFor(String gradeId, String label) {
        Instant start = Instant.now().plus(5, ChronoUnit.DAYS);
        ProjectDto draft = projectService.createDraft(new CreateProjectRequest(
                        label + " " + System.nanoTime(), "summary", "description", "cat_campus", null, null,
                        "ON_CAMPUS", start, start.plusSeconds(7200), "Hall", 5, 100,
                        List.of(gradeId), null, Instant.now().plusSeconds(1000), Instant.now().plusSeconds(2000)),
                "usr_test_admin", false, "zh-CN");
        return projectService.publish(draft.id(), draft.version(), adminAuth(), "zh-CN").id();
    }

    private StudentDetailDto createStudent(String number, String name, String gradeId, String classId) {
        return studentService.create(new CreateStudentRequest(number, name, gradeId, classId,
                "ay_2025_2026", 2029, LocalDate.of(2025, 9, 1)), "usr_test_admin", "zh-CN");
    }

    private Authentication adminAuth() {
        return authFor("usr_test_admin", Set.of("admin"), Set.of("projects.update", "projects.read"), null, true);
    }

    /** A teacher: holds `projects.update`, but no grade-scope assignment rows, so their scope resolves to Self. */
    private Authentication teacherAuth(String userId) {
        return authFor(userId, Set.of("teacher"), Set.of("projects.update", "projects.read"), null, false);
    }

    private Authentication studentAuth(String studentId) {
        return authFor("usr_" + studentId, Set.of("student"), Set.of("projects.read"), studentId, false);
    }

    private Authentication authFor(String userId, Set<String> roles, Set<String> permissions,
                                   String studentId, boolean fullScope) {
        AuthenticatedUserView view = new AuthenticatedUserView(
                userId, userId, "hash", "Display " + userId, true, false, "zh-CN",
                fullScope ? Set.of("admin", "sysadmin") : roles, permissions, studentId, 480);
        CookieSessionUserDetails principal = new CookieSessionUserDetails(view);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
