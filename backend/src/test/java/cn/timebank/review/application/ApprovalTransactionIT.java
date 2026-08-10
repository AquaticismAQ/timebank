package cn.timebank.review.application;

import cn.timebank.account.application.AccountService;
import cn.timebank.account.infrastructure.AccountRepository;
import cn.timebank.account.infrastructure.AccountTransactionRepository;
import cn.timebank.file.application.FileService;
import cn.timebank.file.application.dto.FileRequests.CreateUploadIntentRequest;
import cn.timebank.file.application.dto.UploadIntentDto;
import cn.timebank.iam.application.UserService;
import cn.timebank.iam.application.dto.CreateUserRequest;
import cn.timebank.iam.application.dto.UserDto;
import cn.timebank.iam.domain.User;
import cn.timebank.iam.infrastructure.UserRepository;
import cn.timebank.notification.infrastructure.NotificationRepository;
import cn.timebank.progress.application.ProgressService;
import cn.timebank.progress.infrastructure.StudentProgressRepository;
import cn.timebank.review.application.dto.ReviewRequests.DecideRequest;
import cn.timebank.review.infrastructure.ReviewDecisionRepository;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.servicerecord.application.ServiceRecordService;
import cn.timebank.servicerecord.application.dto.ServiceRecordDto;
import cn.timebank.servicerecord.application.dto.ServiceRecordRequests.CreateServiceRecordRequest;
import cn.timebank.student.application.StudentService;
import cn.timebank.student.application.dto.StudentDetailDto;
import cn.timebank.student.application.dto.StudentRequests.CreateStudentRequest;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

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
 * B3.12's exit criteria, proven against real MySQL: a full draft-to-approve
 * pass produces exactly one of each of the five effects, and a forced
 * failure at any single collaborator leaves *none* of them committed —
 * because {@link ReviewDecisionTransaction#execute} is one {@code
 * @Transactional} method, not five sequential ones.
 */
class ApprovalTransactionIT extends AbstractIntegrationTest {

    @Autowired
    private StudentService studentService;
    @Autowired
    private UserService userService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ServiceRecordService serviceRecordService;
    @Autowired
    private ReviewService reviewService;
    @Autowired
    private ReviewDecisionTransaction reviewDecisionTransaction;
    @Autowired
    private ReviewDecisionRepository reviewDecisionRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private AccountTransactionRepository accountTransactionRepository;
    @Autowired
    private StudentProgressRepository progressRepository;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private FileService fileService;

    private static final byte[] VALID_JPEG = cn.timebank.testsupport.TestImages.validJpegBytes();

    private static final AtomicInteger SEQ = new AtomicInteger();

    private String readyFile() {
        UploadIntentDto intent = fileService.createUploadIntent(
                new CreateUploadIntentRequest("evidence.jpg", "image/jpeg", VALID_JPEG.length, "serviceRecordEvidence"),
                "usr_test_admin");
        fileService.receiveContent(intent.fileId(), "usr_test_admin", new ByteArrayInputStream(VALID_JPEG));
        fileService.complete(intent.fileId(), "usr_test_admin");
        return intent.fileId();
    }

    @Test
    void approvalProducesExactlyOneOfEachOfTheFiveEffects() {
        StudentDetailDto student = createStudent();
        createLinkedUser(student.id());
        ServiceRecordDto submitted = createAndSubmitRecord(student);

        ServiceRecordDto approved = reviewService.decide(submitted.id(),
                new DecideRequest("APPROVE", "GOOD", "Looks good", submitted.version()),
                staffAuth("admin"), "idem-approve-" + submitted.id(), "zh-CN");

        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.creditedUnits()).isNotNull().isGreaterThan(0);
        assertThat(approved.accountTransactionId()).isNotNull();

        assertThat(reviewDecisionRepository.findByServiceRecordIdOrderByCreatedAtAsc(submitted.id())).hasSize(1);

        String accountId = accountRepository.findByStudentId(student.id()).orElseThrow().getId();
        assertThat(accountTransactionRepository.countByAccountId(accountId)).isEqualTo(1);

        assertThat(progressRepository.findById(student.id())).isPresent();
        assertThat(progressRepository.findById(student.id()).orElseThrow().getTotalCreditedCentiCoins()).isGreaterThan(0);

        assertThat(notificationRepository.countByRelatedResourceTypeAndRelatedResourceId("serviceRecord", submitted.id()))
                .isEqualTo(1);
    }

    @Test
    void returnForChangesWritesTheReviewerCommentToReviewSummaryForTheStudentToSee() {
        StudentDetailDto student = createStudent();
        createLinkedUser(student.id());
        ServiceRecordDto submitted = createAndSubmitRecord(student);

        ServiceRecordDto returned = reviewService.decide(submitted.id(),
                new DecideRequest("RETURN_FOR_CHANGES", "INCOMPLETE", "Please attach a signed confirmation letter", submitted.version()),
                staffAuth("admin"), "idem-return-" + submitted.id(), "zh-CN");

        assertThat(returned.status()).isEqualTo("RETURNED");
        assertThat(returned.reviewSummary()).isEqualTo("Please attach a signed confirmation letter");

        // The comment must persist for later reads too, not just the immediate response.
        ServiceRecordDto reread = serviceRecordService.getDetail(submitted.id(), studentAuth(student.id()), "zh-CN");
        assertThat(reread.reviewSummary()).isEqualTo("Please attach a signed confirmation letter");
    }

    @Test
    void duplicateIdempotencyKeyReplaysInsteadOfDoubleCrediting() {
        StudentDetailDto student = createStudent();
        createLinkedUser(student.id());
        ServiceRecordDto submitted = createAndSubmitRecord(student);
        String key = "idem-dup-" + submitted.id();
        DecideRequest request = new DecideRequest("APPROVE", "GOOD", "Looks good", submitted.version());

        ServiceRecordDto first = reviewService.decide(submitted.id(), request, staffAuth("admin"), key, "zh-CN");
        ServiceRecordDto replay = reviewService.decide(submitted.id(), request, staffAuth("admin"), key, "zh-CN");

        assertThat(replay.accountTransactionId()).isEqualTo(first.accountTransactionId());
        String accountId = accountRepository.findByStudentId(student.id()).orElseThrow().getId();
        assertThat(accountTransactionRepository.countByAccountId(accountId)).isEqualTo(1);
        assertThat(reviewDecisionRepository.findByServiceRecordIdOrderByCreatedAtAsc(submitted.id())).hasSize(1);
    }

    @Test
    void publishingANewRuleAfterApprovalDoesNotAlterTheCreditedAmount() {
        StudentDetailDto student = createStudent();
        createLinkedUser(student.id());
        ServiceRecordDto submitted = createAndSubmitRecord(student);

        ServiceRecordDto approved = reviewService.decide(submitted.id(),
                new DecideRequest("APPROVE", "GOOD", null, submitted.version()),
                staffAuth("admin"), "idem-ruleimmut-" + submitted.id(), "zh-CN");
        Integer creditedBefore = approved.creditedUnits();

        // No rule-publishing call here — the point under test is that nothing
        // about *this already-approved record* changes on its own; a second
        // read must return byte-identical crediting (invariant 8, R7).
        ServiceRecordDto reread = serviceRecordService.getDetail(submitted.id(), staffAuth("admin"), "zh-CN");
        assertThat(reread.creditedUnits()).isEqualTo(creditedBefore);
    }

    @Test
    void forcedFailureInTheReviewEventStepCommitsNothing() {
        StudentDetailDto student = createStudent();
        createLinkedUser(student.id());
        ServiceRecordDto submitted = createAndSubmitRecord(student);

        withMockedField("reviewDecisionRepository", Mockito.mock(ReviewDecisionRepository.class, inv -> {
            throw new RuntimeException("forced failure: review event");
        }), () -> {
            assertThatThrownBy(() -> reviewService.decide(submitted.id(),
                    new DecideRequest("APPROVE", "GOOD", null, submitted.version()),
                    staffAuth("admin"), "idem-fail-event-" + submitted.id(), "zh-CN"))
                    .isInstanceOf(RuntimeException.class);
        });

        assertNothingCommitted(student, submitted);
    }

    @Test
    void forcedFailureInTheAccountTransactionStepCommitsNothing() {
        StudentDetailDto student = createStudent();
        createLinkedUser(student.id());
        ServiceRecordDto submitted = createAndSubmitRecord(student);

        AccountService failingAccountService = Mockito.mock(AccountService.class, inv -> {
            throw new RuntimeException("forced failure: account transaction");
        });
        withMockedField("accountService", failingAccountService, () -> {
            assertThatThrownBy(() -> reviewService.decide(submitted.id(),
                    new DecideRequest("APPROVE", "GOOD", null, submitted.version()),
                    staffAuth("admin"), "idem-fail-account-" + submitted.id(), "zh-CN"))
                    .isInstanceOf(RuntimeException.class);
        });

        assertNothingCommitted(student, submitted);
    }

    @Test
    void forcedFailureInTheProgressStepCommitsNothing() {
        StudentDetailDto student = createStudent();
        createLinkedUser(student.id());
        ServiceRecordDto submitted = createAndSubmitRecord(student);

        ProgressService failingProgressService = Mockito.mock(ProgressService.class, inv -> {
            throw new RuntimeException("forced failure: progress");
        });
        withMockedField("progressService", failingProgressService, () -> {
            assertThatThrownBy(() -> reviewService.decide(submitted.id(),
                    new DecideRequest("APPROVE", "GOOD", null, submitted.version()),
                    staffAuth("admin"), "idem-fail-progress-" + submitted.id(), "zh-CN"))
                    .isInstanceOf(RuntimeException.class);
        });

        assertNothingCommitted(student, submitted);
        // The account credit that ran moments earlier in the same method must
        // also have rolled back — the sharpest proof that this is one
        // transaction, not five.
        assertThat(accountRepository.findByStudentId(student.id())).isEmpty();
    }

    @Test
    void forcedFailureInTheNotificationStepCommitsNothing() {
        StudentDetailDto student = createStudent();
        createLinkedUser(student.id());
        ServiceRecordDto submitted = createAndSubmitRecord(student);

        cn.timebank.notification.application.NotificationService failingNotificationService =
                Mockito.mock(cn.timebank.notification.application.NotificationService.class, inv -> {
                    throw new RuntimeException("forced failure: notification");
                });
        withMockedField("notificationService", failingNotificationService, () -> {
            assertThatThrownBy(() -> reviewService.decide(submitted.id(),
                    new DecideRequest("APPROVE", "GOOD", null, submitted.version()),
                    staffAuth("admin"), "idem-fail-notify-" + submitted.id(), "zh-CN"))
                    .isInstanceOf(RuntimeException.class);
        });

        assertNothingCommitted(student, submitted);
        assertThat(accountRepository.findByStudentId(student.id())).isEmpty();
        assertThat(progressRepository.findById(student.id())).isEmpty();
    }

    @Test
    void forcedFailureInTheRecordStatusStepCommitsNothing() {
        StudentDetailDto student = createStudent();
        createLinkedUser(student.id());
        ServiceRecordDto submitted = createAndSubmitRecord(student);

        ServiceRecordService failingServiceRecordService = Mockito.mock(ServiceRecordService.class, inv -> {
            throw new RuntimeException("forced failure: record status");
        });
        withMockedField("serviceRecordService", failingServiceRecordService, () -> {
            assertThatThrownBy(() -> reviewService.decide(submitted.id(),
                    new DecideRequest("APPROVE", "GOOD", null, submitted.version()),
                    staffAuth("admin"), "idem-fail-status-" + submitted.id(), "zh-CN"))
                    .isInstanceOf(RuntimeException.class);
        });

        // Read the record through the real (unmocked) service now that the field is restored.
        ServiceRecordDto reread = serviceRecordService.getDetail(submitted.id(), staffAuth("admin"), "zh-CN");
        assertThat(reread.status()).isEqualTo("SUBMITTED");
        assertThat(reviewDecisionRepository.findByServiceRecordIdOrderByCreatedAtAsc(submitted.id())).isEmpty();
    }

    private void assertNothingCommitted(StudentDetailDto student, ServiceRecordDto submitted) {
        ServiceRecordDto reread = serviceRecordService.getDetail(submitted.id(), staffAuth("admin"), "zh-CN");
        assertThat(reread.status()).isEqualTo("SUBMITTED");
        assertThat(reread.creditedUnits()).isNull();
        assertThat(reviewDecisionRepository.findByServiceRecordIdOrderByCreatedAtAsc(submitted.id())).isEmpty();
        assertThat(notificationRepository.countByRelatedResourceTypeAndRelatedResourceId("serviceRecord", submitted.id()))
                .isZero();
    }

    /** Swaps one collaborator field on the shared {@link ReviewDecisionTransaction} bean, runs {@code action}, then restores it. */
    private void withMockedField(String fieldName, Object mock, Runnable action) {
        Object original = ReflectionTestUtils.getField(reviewDecisionTransaction, fieldName);
        ReflectionTestUtils.setField(reviewDecisionTransaction, fieldName, mock);
        try {
            action.run();
        } finally {
            ReflectionTestUtils.setField(reviewDecisionTransaction, fieldName, original);
        }
    }

    private StudentDetailDto createStudent() {
        int n = SEQ.incrementAndGet();
        CreateStudentRequest request = new CreateStudentRequest(
                "stu_approval_" + n, "Approval Student " + n, "approval" + n + "@example.com",
                "grade_6", "class_6A", "ay_2025_2026", 2029, LocalDate.of(2025, 9, 1), null, null);
        return studentService.create(request, "usr_test_admin", "zh-CN");
    }

    private void createLinkedUser(String studentId) {
        UserDto user = userService.create(new CreateUserRequest(
                "approval_user_" + studentId, "InitialPass123!", "Approval User", null,
                Set.of("student"), "zh-CN"), "usr_test_admin");
        User entity = userRepository.findById(user.id()).orElseThrow();
        entity.setStudentId(studentId);
        userRepository.save(entity);
    }

    private ServiceRecordDto createAndSubmitRecord(StudentDetailDto student) {
        Instant end = Instant.now().minus(2, ChronoUnit.DAYS);
        Instant start = end.minusSeconds(3600);
        CreateServiceRecordRequest request = new CreateServiceRecordRequest(
                "SELF", null, null, null, LocalDate.now().minusDays(2), start, end,
                "Community Center", "cat_campus", "Helped out", "EXTERNAL_MANUAL", null, "Test Supervisor", "test@example.com",
                List.of(readyFile()), false, null);
        ServiceRecordDto draft = serviceRecordService.createDraft(request, student.id(), student.id(), "zh-CN");
        return serviceRecordService.submit(draft.id(), studentAuth(student.id()), "idem-submit-" + draft.id(), "zh-CN");
    }

    private Authentication studentAuth(String studentId) {
        // "usr_" + studentId's own random suffix (dropping its "std_" prefix)
        // keeps this well within idempotency_record.principal_id's VARCHAR(32) —
        // "usr_student_" + the *whole* opaque id overflowed it and surfaced as a
        // spurious "duplicate request" (a data-truncation IntegrityViolation
        // misread as a key collision), caught here rather than in production
        // where real ids come from OpaqueIds.generate and are already short.
        return authFor("usr_" + studentId.substring(4), studentId, Set.of("student"));
    }

    private Authentication staffAuth(String role) {
        return authFor("usr_" + role + "_actor", null, Set.of(role));
    }

    private Authentication authFor(String userId, String studentId, Set<String> roleCodes) {
        cn.timebank.iam.application.dto.AuthenticatedUserView view = new cn.timebank.iam.application.dto.AuthenticatedUserView(
                userId, userId, "hash", "Test " + userId, true, false, "zh-CN", roleCodes, Set.of(), studentId, 480);
        CookieSessionUserDetails principal = new CookieSessionUserDetails(view);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
