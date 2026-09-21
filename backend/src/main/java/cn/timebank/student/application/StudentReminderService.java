package cn.timebank.student.application;

import cn.timebank.audit.application.AuditEntry;
import cn.timebank.audit.application.AuditService;
import cn.timebank.common.error.ApiException;
import cn.timebank.common.error.FieldErrorDetail;
import cn.timebank.iam.application.UserService;
import cn.timebank.notification.application.NotificationService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Sends one student a nudge about their service-hour progress (issue 155).
 *
 * <p>Student Management has had a <i>Remind</i> button since the mock era whose
 * entire implementation was {@code toast.success(...)}. It never sent anything.
 * That is worse than a missing button: a teacher who clicks it believes they
 * have nudged a student who is behind, and stops worrying about them.</p>
 *
 * <h2>Why this is not a method on StudentServiceImpl</h2>
 *
 * <p>Finding the student's login account needs {@link UserService}, and {@code
 * UserServiceImpl} already injects {@link StudentService}. A student-module
 * service that both injected {@code UserService} and was injected by {@code
 * StudentServiceImpl} would close a bean cycle and the application would refuse
 * to start. Sitting beside {@code StudentService} rather than inside it, called
 * straight from the controller, keeps the graph a graph:
 * {@code StudentController → this → UserService → StudentService}.</p>
 *
 * <p>The same shape as {@link cn.timebank.project.application.ProjectPublishNotifier}
 * — a notifier living in the module that triggers the notification, reaching
 * other modules only through their application interfaces.</p>
 *
 * <h2>Refusing rather than pretending</h2>
 *
 * <p>A reminder to an account that cannot be logged into arrives nowhere, and
 * reporting success for it would reproduce the original bug in a new place. So
 * a student with no account — {@code POST /students} provisions none, only the
 * roster import does — or one whose account is still pending activation, locked
 * or deactivated, is a refusal with a code the client has wording for, not a
 * silently dropped message.</p>
 *
 * <p>Clicking twice sends twice. There is no de-duplication window, because two
 * clicks a week apart are two real reminders and the server cannot tell them
 * from two clicks a second apart without inventing a policy nobody asked for.</p>
 */
@Service
public class StudentReminderService {

    private static final String TEMPLATE_CODE = "student.serviceProgressReminder";

    private final StudentService studentService;
    private final UserService userService;
    private final NotificationService notificationService;
    private final AuditService auditService;

    StudentReminderService(StudentService studentService, UserService userService,
                           NotificationService notificationService, AuditService auditService) {
        this.studentService = studentService;
        this.userService = userService;
        this.notificationService = notificationService;
        this.auditService = auditService;
    }

    /**
     * @param resolvedLanguage only forwarded to the scope-checked read; the
     *        notification itself stores a template code and is rendered in the
     *        <i>reader's</i> language when they open it, never the sender's.
     */
    @Transactional
    public void remind(String studentId, Authentication auth, String actorId, String resolvedLanguage) {
        // Existence and data scope in one call: the same read the detail page
        // makes, so a teacher cannot remind a student they cannot see, and an
        // out-of-scope id 404s rather than reporting who exists.
        studentService.getDetail(studentId, auth, resolvedLanguage);

        UserService.StudentAccount account = accountOf(studentId);
        notificationService.notify(account.userId(), TEMPLATE_CODE, "student", studentId);
        auditService.record(new AuditEntry(actorId, "students.read", "student.remind",
                "student", studentId, null, null, null));
    }

    private UserService.StudentAccount accountOf(String studentId) {
        Optional<UserService.StudentAccount> account =
                userService.studentAccountsOf(List.of(studentId)).stream().findFirst();
        if (account.isEmpty()) {
            throw ApiException.stateConflict("This student has no login account yet.",
                    List.of(new FieldErrorDetail("studentId", "NO_LOGIN_ACCOUNT", studentId)));
        }
        if (!"ACTIVE".equals(account.get().status())) {
            throw ApiException.stateConflict("This student's account cannot receive notifications.",
                    List.of(new FieldErrorDetail("studentId", "ACCOUNT_NOT_ACTIVE", studentId)));
        }
        return account.get();
    }
}
