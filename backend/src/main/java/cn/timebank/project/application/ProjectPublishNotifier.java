package cn.timebank.project.application;

import cn.timebank.iam.application.UserService;
import cn.timebank.notification.application.NotificationService;
import cn.timebank.security.application.TeacherScopeAssignmentService;
import cn.timebank.student.application.StudentService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Announces a newly published project to the year groups it is open to
 * (issue 137).
 *
 * <p>Split out of {@code ProjectServiceImpl} rather than added to it: publishing
 * is one line of state change and this is a fan-out across three other modules,
 * and that constructor is long enough already.</p>
 *
 * <h2>Who hears about it</h2>
 *
 * <p><b>Every active student in an eligible grade</b>, and <b>every staff account
 * assigned to one of those grades</b>. Not administrators: they are school-wide,
 * so "the corresponding grade" is every grade, and a notification on every
 * publish is noise rather than news. The project's owner is not special-cased
 * either — if they teach one of the grades they hear about it like anyone else,
 * and if they do not, they already know.</p>
 *
 * <p>The grade assignment is read from the {@code serviceRecords} scope
 * namespace, which is where a teacher's grades actually live — the same
 * borrowing the dashboard, the reports and the project edit rule all make.
 * There is one set of grades a teacher is responsible for, not one per
 * resource.</p>
 *
 * <h2>What it does not do</h2>
 *
 * <p>No de-duplication against a previous publish, because a project can only be
 * published once — {@code ProjectStateMachine} allows {@code DRAFT → PUBLISHED}
 * and nothing returns to {@code DRAFT}.</p>
 *
 * <p>A student with no account yet is silently skipped; a pending-activation one
 * is not, so the notification is waiting when they first log in.</p>
 *
 * <p><b>Sizing.</b> This runs inside the publish transaction and writes one row
 * per recipient — a few hundred for a whole-school project. That is deliberate:
 * the alternative is an outbox nothing else in this codebase has, and the school
 * this is built for has fewer than two hundred students. Revisit if a publish
 * ever fans out to thousands.</p>
 */
@Component
class ProjectPublishNotifier {

    private static final String TEMPLATE_CODE = "project.published";
    private static final String SCOPE_RESOURCE_TYPE = "serviceRecords";

    private final StudentService studentService;
    private final UserService userService;
    private final TeacherScopeAssignmentService teacherScopeAssignmentService;
    private final NotificationService notificationService;

    ProjectPublishNotifier(StudentService studentService, UserService userService,
                           TeacherScopeAssignmentService teacherScopeAssignmentService,
                           NotificationService notificationService) {
        this.studentService = studentService;
        this.userService = userService;
        this.teacherScopeAssignmentService = teacherScopeAssignmentService;
        this.notificationService = notificationService;
    }

    void announce(String projectId, List<String> eligibleGradeIds) {
        if (eligibleGradeIds == null || eligibleGradeIds.isEmpty()) {
            // A project open to no particular grade has no "corresponding
            // grade" to tell. It is visible to every student (issue 129 §2),
            // but announcing it to the whole school on a technicality is not
            // what was asked for.
            return;
        }

        // A set, because a teacher assigned to two of the project's grades is
        // still one person, and LinkedHashSet so the order is reproducible in
        // a test rather than whatever the hash gives.
        Set<String> recipients = new LinkedHashSet<>();

        List<String> studentIds = studentService.activeStudentIdsInGrades(eligibleGradeIds);
        for (UserService.StudentAccount account : userService.studentAccountsOf(studentIds)) {
            recipients.add(account.userId());
        }
        for (String gradeId : eligibleGradeIds) {
            recipients.addAll(teacherScopeAssignmentService.userIdsAssignedToGrade(SCOPE_RESOURCE_TYPE, gradeId));
        }

        for (String userId : recipients) {
            notificationService.notify(userId, TEMPLATE_CODE, "project", projectId);
        }
    }
}
