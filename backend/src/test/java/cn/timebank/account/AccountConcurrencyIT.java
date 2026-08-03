package cn.timebank.account;

import cn.timebank.account.application.AccountService;
import cn.timebank.account.application.dto.AccountDto;
import cn.timebank.account.application.dto.AccountTransactionDto;
import cn.timebank.student.application.StudentService;
import cn.timebank.student.application.dto.StudentDetailDto;
import cn.timebank.student.application.dto.StudentRequests.CreateStudentRequest;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B3.9's exit criterion: real threads, real MySQL row locking via {@code
 * SELECT ... FOR UPDATE} (`AccountRepository.findWithLockById`) — the
 * final balance must equal the exact sum of every concurrent credit, and
 * no two transactions may record the same {@code balanceAfter} (a lost
 * update would produce a duplicate).
 */
class AccountConcurrencyIT extends AbstractIntegrationTest {

    @Autowired
    private StudentService studentService;
    @Autowired
    private AccountService accountService;

    @Test
    void concurrentCreditsToTheSameAccountSerializeCorrectly() throws Exception {
        CreateStudentRequest request = new CreateStudentRequest(
                "stu_concurrency_1", "Concurrency Student", null, "grade_6", "class_6A", "ay_2025_2026",
                2029, LocalDate.of(2025, 9, 1), null, null);
        StudentDetailDto student = studentService.create(request, "usr_test_admin", "zh-CN");

        // Pre-create the account sequentially first — first-ever-credit account
        // creation has its own narrow, documented race (AccountServiceImpl's
        // getOrCreateLocked note) that is deliberately not this test's concern;
        // B3.9 is about locking an *existing* row under concurrent mutation.
        AccountTransactionDto seed = accountService.credit(student.id(), "grade_6", "class_6A", 0,
                "test", "src_seed", "SEED", "usr_test_admin");
        int baseline = seed.balanceAfter();

        int threadCount = 20;
        int amountPerCredit = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            List<Callable<AccountTransactionDto>> tasks = IntStream.range(0, threadCount)
                    .<Callable<AccountTransactionDto>>mapToObj(i -> () -> accountService.credit(
                            student.id(), "grade_6", "class_6A", amountPerCredit,
                            "test", "src_" + i, "SERVICE_APPROVED", "usr_test_admin"))
                    .toList();

            List<Future<AccountTransactionDto>> futures = executor.invokeAll(tasks);
            List<AccountTransactionDto> results = new java.util.ArrayList<>();
            for (Future<AccountTransactionDto> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }

            AccountDto finalAccount = accountService.getByStudentId(student.id(), adminAuth());
            assertThat(finalAccount.balance()).isEqualTo(baseline + threadCount * amountPerCredit);

            List<Integer> balancesAfter = results.stream().map(AccountTransactionDto::balanceAfter).toList();
            assertThat(balancesAfter).doesNotHaveDuplicates();
            List<Integer> expected = IntStream.rangeClosed(1, threadCount)
                    .map(i -> baseline + i * amountPerCredit).boxed().collect(Collectors.toList());
            assertThat(balancesAfter).containsExactlyInAnyOrderElementsOf(expected);
        } finally {
            executor.shutdown();
        }
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
