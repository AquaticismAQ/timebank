package cn.timebank.caseflow.application;

import cn.timebank.account.application.AccountService;
import cn.timebank.account.application.dto.AccountDto;
import cn.timebank.account.application.dto.AccountTransactionDto;
import cn.timebank.caseflow.application.dto.CaseDto;
import cn.timebank.caseflow.application.dto.CaseRequests.CreateCaseRequest;
import cn.timebank.caseflow.application.dto.CaseRequests.DecideRequest;
import cn.timebank.caseflow.application.dto.TimelineEntryDto;
import cn.timebank.common.error.ApiException;
import cn.timebank.common.OpaqueIds;
import cn.timebank.iam.application.dto.AuthenticatedUserView;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.security.domain.TeacherScopeAssignment;
import cn.timebank.security.infrastructure.TeacherScopeAssignmentRepository;
import cn.timebank.iam.application.UserService;
import cn.timebank.iam.application.dto.CreateUserRequest;
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
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B5.2's exit criteria against real MySQL: a manual-correction resolution
 * appends exactly one ledger transaction, an appeal/supplement resolution
 * never touches the ledger, a duplicate `Idempotency-Key` replays instead
 * of double-adjusting, and out-of-scope teachers get an invisible 404 on a
 * case — the concrete test B4's phase doc admitted was missing for the
 * identical pattern.
 */
class CaseDecisionTransactionIT extends AbstractIntegrationTest {

    @Autowired
    private StudentService studentService;
    @Autowired
    private CaseService caseService;
    @Autowired
    private AccountService accountService;
    @Autowired
    private UserService userService;
    @Autowired
    private TeacherScopeAssignmentRepository scopeAssignmentRepository;
    @Autowired
    private cn.timebank.audit.infrastructure.AuditLogRepository auditLogRepository;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Test
    void manualCorrectionResolveGeneratesExactlyOneAccountTransaction() {
        StudentDetailDto student = createStudent();
        CaseDto submitted = createAndSubmitManualCorrection(student, 500);

        CaseDto resolved = caseService.decide(submitted.id(),
                new DecideRequest("resolve", "APPROVED_CORRECTION", "Verified with the supervisor", submitted.version()),
                staffAuth("admin"), "idem-case-resolve-" + submitted.id(), "zh-CN");

        assertThat(resolved.status()).isEqualTo("RESOLVED");
        AccountDto account = accountService.getByStudentId(student.id(), staffAuth("admin"));
        assertThat(account.balance()).isEqualTo(500);
        var transactions = accountService.listTransactions(account.id(), PageRequest.of(0, 10), staffAuth("admin"));
        assertThat(transactions.getTotalElements()).isEqualTo(1);
        AccountTransactionDto transaction = transactions.getContent().get(0);
        assertThat(transaction.amount()).isEqualTo(500);
        assertThat(transaction.type()).isEqualTo("CORRECTION");
    }

    /**
     * Issue 077: the decision itself used to leave no audit trace at all — only the
     * student's create/submit were logged, while the reviewer's resolve/reject/
     * request-information were not. Matters most for MANUAL_CORRECTION, which moves
     * time coins in this same transaction.
     */
    @Test
    void aDecisionIsWrittenToTheAuditLog() {
        StudentDetailDto student = createStudent();
        CaseDto submitted = createAndSubmitAppeal(student);

        caseService.decide(submitted.id(),
                new DecideRequest("reject", "INSUFFICIENT_EVIDENCE", "Not evidenced", submitted.version()),
                staffAuth("admin"), "idem-audit-" + submitted.id(), "zh-CN");

        var entries = auditLogRepository.findByResourceTypeAndResourceId("case", submitted.id(),
                PageRequest.of(0, 20));
        assertThat(entries.getContent()).extracting(e -> e.getAction()).contains("case.decision");
        var decision = entries.getContent().stream().filter(e -> "case.decision".equals(e.getAction())).findFirst().orElseThrow();
        // AuditServiceImpl JSON-serializes the summaries, so a plain String is stored
        // quoted ("REJECTED") — same for every other caller, hence `contains`.
        assertThat(decision.getAfterSummary()).contains("REJECTED");
        assertThat(decision.getBeforeSummary()).contains("SUBMITTED");
        assertThat(decision.getReasonCode()).isEqualTo("INSUFFICIENT_EVIDENCE");
    }

    @Test
    void appealResolveDoesNotTouchTheLedger() {
        StudentDetailDto student = createStudent();
        CaseDto submitted = createAndSubmitAppeal(student);

        CaseDto resolved = caseService.decide(submitted.id(),
                new DecideRequest("resolve", "REVIEWED", "No further action needed", submitted.version()),
                staffAuth("admin"), "idem-appeal-resolve-" + submitted.id(), "zh-CN");

        assertThat(resolved.status()).isEqualTo("RESOLVED");
        assertThatThrownBy(() -> accountService.getByStudentId(student.id(), staffAuth("admin")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void appealTimelineRendersACompleteReadableSequence() {
        StudentDetailDto student = createStudent();
        CaseDto submitted = createAndSubmitAppeal(student);

        caseService.decide(submitted.id(),
                new DecideRequest("resolve", "REVIEWED", "Confirmed with the supervisor by phone", submitted.version()),
                staffAuth("admin"), "idem-appeal-timeline-" + submitted.id(), "zh-CN");

        List<TimelineEntryDto> timeline = caseService.timeline(submitted.id(), staffAuth("admin"));

        assertThat(timeline).hasSize(3);
        assertThat(timeline).isSortedAccordingTo(java.util.Comparator.comparing(TimelineEntryDto::occurredAt));
        assertThat(timeline.get(0).type()).isEqualTo("created");
        assertThat(timeline.get(0).actorId()).isEqualTo(student.id());
        assertThat(timeline.get(1).type()).isEqualTo("submitted");
        assertThat(timeline.get(1).actorId()).isEqualTo(student.id());
        TimelineEntryDto decisionEntry = timeline.get(2);
        assertThat(decisionEntry.type()).isEqualTo("decision.resolve");
        assertThat(decisionEntry.description()).isEqualTo("Confirmed with the supervisor by phone");
        assertThat(decisionEntry.actorId()).isEqualTo("usr_admin_actor");
    }

    @Test
    void duplicateIdempotencyKeyReplaysInsteadOfDoubleAdjusting() {
        StudentDetailDto student = createStudent();
        CaseDto submitted = createAndSubmitManualCorrection(student, 300);
        String key = "idem-case-dup-" + submitted.id();
        DecideRequest request = new DecideRequest("resolve", "APPROVED_CORRECTION", null, submitted.version());

        caseService.decide(submitted.id(), request, staffAuth("admin"), key, "zh-CN");
        caseService.decide(submitted.id(), request, staffAuth("admin"), key, "zh-CN");

        AccountDto account = accountService.getByStudentId(student.id(), staffAuth("admin"));
        assertThat(account.balance()).isEqualTo(300);
        var transactions = accountService.listTransactions(account.id(), PageRequest.of(0, 10), staffAuth("admin"));
        assertThat(transactions.getTotalElements()).isEqualTo(1);
    }

    /**
     * 07-RoleFinetune.md §2.1 made {@code teacher} a {@code
     * hasFullDataScope()} role like {@code admin}/{@code sysadmin} — a
     * {@code teacher_scope_assignment} row no longer restricts anything.
     * This test used to assert the case was invisible to an out-of-scope
     * teacher; that described the pre-07-RoleFinetune behavior §2.1
     * deliberately changed, not a regression to guard against.
     */
    @Test
    // 07-RoleFinetune.md §2.10 (2026-08-20): reverted from §2.1's "any non-student sees
    // everything" back to grade-scoped visibility for teacher — see hasSchoolWideVisibility().
    void scopedTeacherCannotSeeAnOutOfScopeCase() {
        StudentDetailDto student = createStudent(); // grade_6/class_6A
        CaseDto submitted = createAndSubmitAppeal(student);

        var teacher = userService.create(new CreateUserRequest(
                "case_scope_teacher_" + OpaqueIds.generate("u"), "InitialChoice123!", "Scope Teacher", null,
                "teacher", "zh-CN"), "usr_test_admin");
        scopeAssignmentRepository.save(new TeacherScopeAssignment(OpaqueIds.generate("scope"), teacher.id(),
                "cases", TeacherScopeAssignment.ScopeType.GRADE, "grade_9", Instant.now()));
        Authentication teacherAuth = authFor(teacher.id(), null, Set.of("teacher"));

        assertThatThrownBy(() -> caseService.getDetail(submitted.id(), teacherAuth, "zh-CN"))
                .isInstanceOf(ApiException.class);
    }

    private CaseDto createAndSubmitManualCorrection(StudentDetailDto student, int amountCentiCoins) {
        CreateCaseRequest request = new CreateCaseRequest(student.id(), "manualCorrection", "serviceRecord", null,
                "Missed hours from last term", "Supervisor never signed off at the time", amountCentiCoins, "LATE_SIGNOFF");
        CaseDto draft = caseService.createDraft(request, staffAuth("teacher"), "zh-CN");
        return caseService.submit(draft.id(), staffAuth("teacher"), "idem-case-submit-" + draft.id(), "zh-CN");
    }

    /**
     * Issue 081: the student's own lifecycle actions. Withdraw is owner-only and
     * only before a reviewer has claimed the case; delete is drafts only.
     */
    @Test
    void aStudentCanWithdrawTheirCaseUntilAReviewerClaimsIt() {
        StudentDetailDto student = createStudent();
        CaseDto submitted = createAndSubmitAppeal(student);

        CaseDto withdrawn = caseService.withdraw(submitted.id(), studentAuth(student.id()), "en");

        assertThat(withdrawn.status()).isEqualTo("DRAFT");
        assertThat(withdrawn.submittedAt()).isNull();

        // Resubmitted, then claimed by the first decision — now it is too late.
        CaseDto resubmitted = caseService.submit(withdrawn.id(), studentAuth(student.id()),
                "idem-resubmit-" + withdrawn.id(), "en");
        caseService.decide(resubmitted.id(),
                new DecideRequest("requestInformation", "MORE_DETAIL", "Which session?", resubmitted.version()),
                staffAuth("admin"), "idem-claim-" + resubmitted.id(), "en");

        assertThatThrownBy(() -> caseService.withdraw(resubmitted.id(), studentAuth(student.id()), "en"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void onlyADraftCaseCanBeDeleted() {
        StudentDetailDto student = createStudent();
        CaseDto submitted = createAndSubmitAppeal(student);

        assertThatThrownBy(() -> caseService.deleteDraft(submitted.id(), studentAuth(student.id())))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("draft");

        caseService.withdraw(submitted.id(), studentAuth(student.id()), "en");
        caseService.deleteDraft(submitted.id(), studentAuth(student.id()));

        assertThatThrownBy(() -> caseService.getDetail(submitted.id(), studentAuth(student.id()), "en"))
                .isInstanceOf(ApiException.class);
    }

    /** A draft edited at least once has revision rows FK'd to it — they must not block the delete. */
    @Test
    void aDraftWithRevisionsCanStillBeDeleted() {
        StudentDetailDto student = createStudent();
        CreateCaseRequest request = new CreateCaseRequest(null, "supplement", null, null,
                "Draft to delete", "First description", null, "STUDENT_SUPPLEMENT");
        CaseDto draft = caseService.createDraft(request, studentAuth(student.id()), "en");
        CaseDto edited = caseService.updateDraft(draft.id(),
                new cn.timebank.caseflow.application.dto.CaseRequests.UpdateCaseRequest(
                        null, "Second description", null, null, draft.version()),
                studentAuth(student.id()), "en");

        caseService.deleteDraft(edited.id(), studentAuth(student.id()));

        assertThatThrownBy(() -> caseService.getDetail(edited.id(), studentAuth(student.id()), "en"))
                .isInstanceOf(ApiException.class);
    }

    private CaseDto createAndSubmitAppeal(StudentDetailDto student) {
        CreateCaseRequest request = new CreateCaseRequest(null, "appeal", "serviceRecord", null,
                "My record was rejected unfairly", "The supervisor did confirm my hours by phone", null, "DISPUTE_REJECTION");
        CaseDto draft = caseService.createDraft(request, studentAuth(student.id()), "zh-CN");
        return caseService.submit(draft.id(), studentAuth(student.id()), "idem-case-submit-" + draft.id(), "zh-CN");
    }

    private StudentDetailDto createStudent() {
        int n = SEQ.incrementAndGet();
        CreateStudentRequest request = new CreateStudentRequest(
                "stu_case_" + n, "Case Student " + n, "grade_6", "class_6A", "ay_2025_2026",
                2029, LocalDate.of(2025, 9, 1));
        return studentService.create(request, "usr_test_admin", "zh-CN");
    }

    /**
     * A DRAFT case is the student's own unsubmitted work, so a reviewer must not
     * see it — not in the list an admin loads by default, and not by fetching the
     * id directly. Before this rule, data scope was the only filter, and a
     * school-wide admin is in scope for every case, so the default Case Review
     * list returned students' drafts with their descriptions attached.
     *
     * <p>The owner control is the part that makes this non-vacuous: it asserts the
     * same draft IS visible to the student who wrote it, so a change that simply
     * excluded DRAFT for everyone would fail here rather than pass.</p>
     */
    @Test
    void aDraftCaseIsVisibleToItsOwnerButNeverToAReviewer() {
        StudentDetailDto student = createStudent();
        CaseDto draft = caseService.createDraft(
                new CreateCaseRequest(null, "appeal", "serviceRecord", null,
                        "Draft title only the student should see", "Draft body", null, "DISPUTE_HOURS"),
                studentAuth(student.id()), "zh-CN");
        assertThat(draft.status()).isEqualTo("DRAFT");

        // owner: sees it in their own list, and can fetch it
        var ownerList = caseService.list(PageRequest.of(0, 50), studentAuth(student.id()),
                null, null, null, null, "zh-CN");
        assertThat(ownerList.getContent()).extracting(CaseDto::id).contains(draft.id());
        assertThat(caseService.getDetail(draft.id(), studentAuth(student.id()), "zh-CN").id())
                .isEqualTo(draft.id());

        // admin: absent from the unfiltered list a reviewer actually loads
        var adminList = caseService.list(PageRequest.of(0, 50), staffAuth("admin"),
                null, null, null, null, "zh-CN");
        assertThat(adminList.getContent()).extracting(CaseDto::id).doesNotContain(draft.id());

        // admin: and not reachable by id either
        assertThatThrownBy(() -> caseService.getDetail(draft.id(), staffAuth("admin"), "zh-CN"))
                .isInstanceOf(ApiException.class);
    }

    private Authentication studentAuth(String studentId) {
        return authFor("usr_" + studentId.substring(4), studentId, Set.of("student"));
    }

    private Authentication staffAuth(String role) {
        return authFor("usr_" + role + "_actor", null, Set.of(role));
    }

    private Authentication authFor(String userId, String studentId, Set<String> roleCodes) {
        AuthenticatedUserView view = new AuthenticatedUserView(
                userId, userId, "hash", "Test " + userId, true, false, "zh-CN", roleCodes, Set.of(), studentId, 480);
        CookieSessionUserDetails principal = new CookieSessionUserDetails(view);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
