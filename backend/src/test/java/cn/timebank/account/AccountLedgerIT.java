package cn.timebank.account;

import cn.timebank.account.application.AccountService;
import cn.timebank.account.application.dto.AccountDto;
import cn.timebank.account.application.dto.AccountRequests.AdjustmentRequest;
import cn.timebank.account.application.dto.AccountRequests.FreezeRequest;
import cn.timebank.account.application.dto.AccountRequests.ReverseRequest;
import cn.timebank.account.application.dto.AccountTransactionDto;
import cn.timebank.common.error.ApiException;
import cn.timebank.student.application.StudentService;
import cn.timebank.student.application.dto.StudentDetailDto;
import cn.timebank.student.application.dto.StudentRequests.CreateStudentRequest;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
                new ReverseRequest("CORRECTION_ERROR", "Wrong duration"), "usr_test_admin");

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
                new AdjustmentRequest(-100, "MANUAL_CORRECTION", "Overcounted"), "usr_test_admin");

        assertThat(adjustment.amount()).isEqualTo(-100);
        assertThat(adjustment.balanceAfter()).isEqualTo(credit.balanceAfter() - 100);
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
                new ReverseRequest("CORRECTION_ERROR", "Fixing while frozen"), "usr_test_admin");
        assertThat(reversal.amount()).isEqualTo(-200);
    }

    private String createStudent() {
        int n = SEQ.incrementAndGet();
        CreateStudentRequest request = new CreateStudentRequest(
                "stu_ledger_" + n, "Ledger Student " + n, null, "grade_6", "class_6A", "ay_2025_2026",
                2029, LocalDate.of(2025, 9, 1), null, null);
        StudentDetailDto student = studentService.create(request, "usr_test_admin", "zh-CN");
        return student.id();
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
