package cn.timebank.account.application;

import cn.timebank.account.application.dto.AccountDto;
import cn.timebank.account.application.dto.AccountRequests.AdjustmentRequest;
import cn.timebank.account.application.dto.AccountRequests.FreezeRequest;
import cn.timebank.account.application.dto.AccountRequests.ReverseRequest;
import cn.timebank.account.application.dto.AccountTransactionDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

/** The `account` module's published interface (Design.md §6). */
public interface AccountService {

    /** {@code studentId} is contract §13.1's account-by-student resolution path (frontend `accounts.get`). */
    Page<AccountDto> list(Pageable pageable, String studentId, Authentication auth);

    AccountDto getById(String id, Authentication auth);

    AccountDto getByStudentId(String studentId, Authentication auth);

    /**
     * B3.11: the progress endpoint's {@code timeCoinBalance} field. No
     * {@link Authentication}/scope check — the caller (progress) has
     * already authorized the read for its own resource; this is an
     * internal cross-module trust call, the same shape as {@code
     * ServiceRecordService.summarizeForProgress}. Returns 0 for a student
     * who has never earned (no account row created yet).
     */
    int getBalanceForStudent(String studentId);

    Page<AccountTransactionDto> listTransactions(String accountId, Pageable pageable, Authentication auth);

    AccountTransactionDto getTransaction(String id, Authentication auth);

    /**
     * B3.9/B3.12: the only path that ever changes a balance (invariant 1).
     * Creates the account lazily on first credit if the student has never
     * earned before — {@code gradeId}/{@code classId} are supplied by the
     * caller (already denormalized on the source record) rather than
     * looked up here, so `account` never depends on `student` (Design.md §6).
     *
     * @throws cn.timebank.common.error.ApiException stateConflict if the account is frozen
     */
    AccountTransactionDto credit(String studentId, String gradeId, String classId, int amountCentiCoins,
                                  String sourceType, String sourceId, String reasonCode, String operatorId);

    /**
     * Contract §2.8/§13.4: money-moving, so {@code idempotencyKey} is
     * mandatory (unlike {@link #adjustForStudent}, an internal call with no
     * HTTP request of its own to key off).
     */
    AccountTransactionDto adjust(String accountId, AdjustmentRequest request, String actorId, String idempotencyKey);

    /**
     * B5.2: a manual correction may be the first-ever credit for a student
     * (that is the point of a retroactive correction) — unlike {@link
     * #adjust}, which requires an already-existing account, this lazily
     * creates one exactly the way {@link #credit} does, so a caseflow
     * resolution never fails with "account not found" for a student who
     * has never earned before.
     */
    AccountTransactionDto adjustForStudent(String studentId, String gradeId, String classId,
                                            AdjustmentRequest request, String actorId);

    /**
     * Creates an opposite-signed transaction referencing the original; the
     * original row is never touched. {@code idempotencyKey} is mandatory,
     * same reasoning as {@link #adjust}.
     */
    AccountTransactionDto reverse(String transactionId, ReverseRequest request, String actorId, String idempotencyKey);

    AccountDto freeze(String accountId, FreezeRequest request, String actorId);

    AccountDto unfreeze(String accountId, FreezeRequest request, String actorId);
}
