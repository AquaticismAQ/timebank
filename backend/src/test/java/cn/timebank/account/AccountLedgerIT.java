package cn.timebank.account;

import cn.timebank.account.application.AccountService;
import cn.timebank.account.application.dto.AccountDto;
import cn.timebank.account.application.dto.AccountRequests.AdjustmentRequest;
import cn.timebank.account.application.dto.AccountRequests.FreezeRequest;
import cn.timebank.account.application.dto.AccountRequests.ReverseRequest;
import cn.timebank.account.application.dto.AccountTransactionDto;
import cn.timebank.common.OpaqueIds;
import cn.timebank.common.error.ApiException;
import cn.timebank.iam.application.UserService;
import cn.timebank.iam.application.dto.CreateUserRequest;
import cn.timebank.security.domain.TeacherScopeAssignment;
import cn.timebank.security.infrastructure.TeacherScopeAssignmentRepository;
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

import java.time.LocalDate;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B3.8/B3.10's exit criteria against real MySQL: invariants 1-2 (balance
 * changes only via an appended transaction; a transaction is never
 * updated/deleted — reversal appends an opposite entry and leaves the
 * original byte-identical), plus freeze blocking new credit.
 */
class AccountLedgerIT extends AbstractIntegrationTest {

    @Autowired
    private StudentService studentService;
    @Autowired
    private AccountService accountService;
    @Autowired
    private UserService userService;
    @Autowired
    private TeacherScopeAssignmentRepository scopeAssignmentRepository;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Test
    void creditAppendsATransactionAndUpdatesTheStoredBalance() {
        String studentId = createStudent();
        AccountTransactionDto txn = accountService.credit(studentId, "grade_6", "class_6A", 250,
                "test", "src_1", "SERVICE_APPROVED", "usr_test_admin");

        assertThat(txn.amount()).isEqualTo(250);
        assertThat(txn.balanceAfter()).isEqualTo(250);

        AccountDto account = accountService.getByStudentId(studentId, adminAuth());
        assertThat(account.balance()).isEqualTo(250);
    }

    @Test
    void secondCreditAccumulatesOnTheStoredBalance() {
        String studentId = createStudent();
        accountService.credit(studentId, "grade_6", "class_6A", 100, "test", "src_1", "SERVICE_APPROVED", "usr_test_admin");
        AccountTransactionDto second = accountService.credit(studentId, "grade_6", "class_6A", 150,
                "test", "src_2", "SERVICE_APPROVED", "usr_test_admin");

        assertThat(second.balanceAfter()).isEqualTo(250);
    }

    @Test
    void reversalAppendsAnOppositeEntryAndLeavesTheOriginalUntouched() {
        String studentId = createStudent();
        AccountTransactionDto original = accountService.credit(studentId, "grade_6", "class_6A", 300,
                "test", "src_1", "SERVICE_APPROVED", "usr_test_admin");
        AccountDto account = accountService.getByStudentId(studentId, adminAuth());

        AccountTransactionDto reversal = accountService.reverse(original.id(),
                new ReverseRequest("CORRECTION_ERROR", "Wrong duration"), "usr_test_admin", "idem-" + original.id());

        assertThat(reversal.amount()).isEqualTo(-300);
        assertThat(reversal.reversalOfTransactionId()).isEqualTo(original.id());

        AccountTransactionDto originalReread = accountService.getTransaction(original.id(), adminAuth());
        assertThat(originalReread.amount()).isEqualTo(300);
        assertThat(originalReread.reasonCode()).isEqualTo(original.reasonCode());
        assertThat(originalReread.reversalOfTransactionId()).isNull();

        AccountDto accountAfter = accountService.getByStudentId(studentId, adminAuth());
        assertThat(accountAfter.balance()).isEqualTo(account.balance() - 300);
    }

    @Test
    void adjustmentCanBeNegativeOrPositiveAndIsAlwaysAppended() {
        String studentId = createStudent();
        AccountTransactionDto credit = accountService.credit(studentId, "grade_6", "class_6A", 500,
                "test", "src_1", "SERVICE_APPROVED", "usr_test_admin");
        String accountId = accountService.getByStudentId(studentId, adminAuth()).id();

        AccountTransactionDto adjustment = accountService.adjust(accountId,
                new AdjustmentRequest(-100, "MANUAL_CORRECTION", "Overcounted"), "usr_test_admin", "idem-adjust-" + accountId);

        assertThat(adjustment.amount()).isEqualTo(-100);
        assertThat(adjustment.balanceAfter()).isEqualTo(credit.balanceAfter() - 100);
    }

    @Test
    void duplicateAdjustmentIdempotencyKeyReplaysInsteadOfDoublePosting() {
        String studentId = createStudent();
        accountService.credit(studentId, "grade_6", "class_6A", 500, "test", "src_1", "SERVICE_APPROVED", "usr_test_admin");
        String accountId = accountService.getByStudentId(studentId, adminAuth()).id();
        AdjustmentRequest request = new AdjustmentRequest(-100, "MANUAL_CORRECTION", "Overcounted");
        String key = "idem-dup-adjust-" + accountId;

        AccountTransactionDto first = accountService.adjust(accountId, request, "usr_test_admin", key);
        AccountTransactionDto replay = accountService.adjust(accountId, request, "usr_test_admin", key);

        assertThat(replay.id()).isEqualTo(first.id());
        AccountDto account = accountService.getByStudentId(studentId, adminAuth());
        assertThat(account.balance()).isEqualTo(400);
    }

    @Test
    void adjustmentWithoutIdempotencyKeyIsRejected() {
        String studentId = createStudent();
        accountService.credit(studentId, "grade_6", "class_6A", 500, "test", "src_1", "SERVICE_APPROVED", "usr_test_admin");
        String accountId = accountService.getByStudentId(studentId, adminAuth()).id();

        assertThatThrownBy(() -> accountService.adjust(accountId,
                new AdjustmentRequest(-100, "MANUAL_CORRECTION", "Overcounted"), "usr_test_admin", null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void duplicateReversalIdempotencyKeyReplaysInsteadOfDoubleReversing() {
        String studentId = createStudent();
        AccountTransactionDto original = accountService.credit(studentId, "grade_6", "class_6A", 300,
                "test", "src_1", "SERVICE_APPROVED", "usr_test_admin");
        ReverseRequest request = new ReverseRequest("CORRECTION_ERROR", "Wrong duration");
        String key = "idem-dup-reverse-" + original.id();

        AccountTransactionDto first = accountService.reverse(original.id(), request, "usr_test_admin", key);
        AccountTransactionDto replay = accountService.reverse(original.id(), request, "usr_test_admin", key);

        assertThat(replay.id()).isEqualTo(first.id());
        AccountDto account = accountService.getByStudentId(studentId, adminAuth());
        assertThat(account.balance()).isEqualTo(0);
    }

    @Test
    void freezeBlocksNewCreditsUntilUnfrozen() {
        String studentId = createStudent();
        accountService.credit(studentId, "grade_6", "class_6A", 100, "test", "src_1", "SERVICE_APPROVED", "usr_test_admin");
        String accountId = accountService.getByStudentId(studentId, adminAuth()).id();

        AccountDto frozen = accountService.freeze(accountId, new FreezeRequest("SUSPECTED_FRAUD", "Under review"), "usr_test_admin");
        assertThat(frozen.frozen()).isTrue();

        assertThatThrownBy(() -> accountService.credit(studentId, "grade_6", "class_6A", 50,
                "test", "src_2", "SERVICE_APPROVED", "usr_test_admin"))
                .isInstanceOf(ApiException.class);

        AccountDto unfrozen = accountService.unfreeze(accountId, new FreezeRequest("REVIEW_CLEARED", "Cleared"), "usr_test_admin");
        assertThat(unfrozen.frozen()).isFalse();

        AccountTransactionDto afterUnfreeze = accountService.credit(studentId, "grade_6", "class_6A", 50,
                "test", "src_3", "SERVICE_APPROVED", "usr_test_admin");
        assertThat(afterUnfreeze.amount()).isEqualTo(50);
    }

    @Test
    void reversalIsAllowedEvenOnAFrozenAccount() {
        String studentId = createStudent();
        AccountTransactionDto original = accountService.credit(studentId, "grade_6", "class_6A", 200,
                "test", "src_1", "SERVICE_APPROVED", "usr_test_admin");
        String accountId = accountService.getByStudentId(studentId, adminAuth()).id();
        accountService.freeze(accountId, new FreezeRequest("SUSPECTED_FRAUD", "Under review"), "usr_test_admin");

        AccountTransactionDto reversal = accountService.reverse(original.id(),
                new ReverseRequest("CORRECTION_ERROR", "Fixing while frozen"), "usr_test_admin", "idem-frozen-" + original.id());
        assertThat(reversal.amount()).isEqualTo(-200);
    }

    @Test
    void listFiltersByStudentIdForTheAccountsGetResolutionPath() {
        String studentId = createStudent();
        accountService.credit(studentId, "grade_6", "class_6A", 100, "test", "src_1", "SERVICE_APPROVED", "usr_test_admin");
        String otherStudentId = createStudent();
        accountService.credit(otherStudentId, "grade_6", "class_6A", 100, "test", "src_2", "SERVICE_APPROVED", "usr_test_admin");

        Page<AccountDto> filtered = accountService.list(PageRequest.of(0, 20), studentId, adminAuth());

        assertThat(filtered.getContent()).hasSize(1);
        assertThat(filtered.getContent().get(0).studentId()).isEqualTo(studentId);
    }

    /**
     * `APIContract.md` §11 acceptance scenario 8 described {@code teacher}
     * as grade-scoped — no longer true after 07-RoleFinetune.md §2.1 made
     * {@code teacher} a {@code hasFullDataScope()} role like {@code admin}/
     * {@code sysadmin}. This test used to assert the out-of-scope account
     * was invisible; that described the pre-07-RoleFinetune behavior §2.1
     * deliberately changed, not a regression to guard against.
     */
    @Test
    // 07-RoleFinetune.md §2.10 (2026-08-20): reverted from §2.1's "any non-student sees
    // everything" back to grade-scoped visibility for teacher — see hasSchoolWideVisibility().
    void scopedTeacherSeesOnlyInScopeAccounts() {
        String inScopeStudentId = createStudent(); // grade_6/class_6A
        String outOfScopeStudentId = createStudentInGrade9();
        accountService.credit(inScopeStudentId, "grade_6", "class_6A", 100, "test", "src_1", "SERVICE_APPROVED", "usr_test_admin");
        accountService.credit(outOfScopeStudentId, "grade_9", "class_9A", 100, "test", "src_2", "SERVICE_APPROVED", "usr_test_admin");

        Authentication teacherAuth = teacherScopedToGrade6();

        assertThat(accountService.getByStudentId(inScopeStudentId, teacherAuth).studentId()).isEqualTo(inScopeStudentId);
        assertThatThrownBy(() -> accountService.getByStudentId(outOfScopeStudentId, teacherAuth))
                .isInstanceOf(ApiException.class);
    }

    private String createStudent() {
        int n = SEQ.incrementAndGet();
        CreateStudentRequest request = new CreateStudentRequest(
                "stu_ledger_" + n, "Ledger Student " + n, "grade_6", "class_6A", "ay_2025_2026",
                2029, LocalDate.of(2025, 9, 1));
        StudentDetailDto student = studentService.create(request, "usr_test_admin", "zh-CN");
        return student.id();
    }

    private String createStudentInGrade9() {
        int n = SEQ.incrementAndGet();
        CreateStudentRequest request = new CreateStudentRequest(
                "stu_ledger9_" + n, "Ledger Student " + n, "grade_9", "class_9A", "ay_2025_2026",
                2026, LocalDate.of(2025, 9, 1));
        StudentDetailDto student = studentService.create(request, "usr_test_admin", "zh-CN");
        return student.id();
    }

    private Authentication teacherScopedToGrade6() {
        var teacher = userService.create(new CreateUserRequest(
                "acct_scope_teacher_" + OpaqueIds.generate("u"), "Scope Teacher", null,
                "teacher", "zh-CN"), "usr_test_admin");
        scopeAssignmentRepository.save(new TeacherScopeAssignment(OpaqueIds.generate("scope"), teacher.id(),
                "accounts", TeacherScopeAssignment.ScopeType.GRADE, "grade_6", java.time.Instant.now()));
        cn.timebank.iam.application.dto.AuthenticatedUserView view = new cn.timebank.iam.application.dto.AuthenticatedUserView(
                teacher.id(), teacher.id(), "hash", "Scope Teacher", true, false, "zh-CN",
                Set.of("teacher"), Set.of(), null, 480);
        cn.timebank.security.application.CookieSessionUserDetails principal =
                new cn.timebank.security.application.CookieSessionUserDetails(view);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private Authentication adminAuth() {
        cn.timebank.iam.application.dto.AuthenticatedUserView view = new cn.timebank.iam.application.dto.AuthenticatedUserView(
                "usr_test_admin", "usr_test_admin", "hash", "Test Admin", true, false, "zh-CN",
                Set.of("admin"), Set.of(), null, 480);
        cn.timebank.security.application.CookieSessionUserDetails principal =
                new cn.timebank.security.application.CookieSessionUserDetails(view);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
