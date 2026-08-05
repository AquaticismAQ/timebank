package cn.timebank.account.application;

import cn.timebank.account.application.dto.AccountDto;
import cn.timebank.account.application.dto.AccountRequests.AdjustmentRequest;
import cn.timebank.account.application.dto.AccountRequests.FreezeRequest;
import cn.timebank.account.application.dto.AccountRequests.ReverseRequest;
import cn.timebank.account.application.dto.AccountTransactionDto;
import cn.timebank.account.domain.Account;
import cn.timebank.account.domain.AccountTransaction;
import cn.timebank.account.domain.TransactionType;
import cn.timebank.account.infrastructure.AccountRepository;
import cn.timebank.account.infrastructure.AccountTransactionRepository;
import cn.timebank.audit.application.AuditEntry;
import cn.timebank.audit.application.AuditService;
import cn.timebank.common.OpaqueIds;
import cn.timebank.common.error.ApiException;
import cn.timebank.common.idempotency.IdempotencyService;
import cn.timebank.common.pagination.FilterSpecifications;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.security.application.DataScopeResolver;
import cn.timebank.security.application.DataScopeSpecifications;
import cn.timebank.security.application.ScopeDescriptor;
import cn.timebank.security.application.TimeBankPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
class AccountServiceImpl implements AccountService {

    private static final String RESOURCE_TYPE = "accounts";

    private final AccountRepository accountRepository;
    private final AccountTransactionRepository transactionRepository;
    private final AuditService auditService;
    private final DataScopeResolver dataScopeResolver;
    private final IdempotencyService idempotencyService;
    private final AccountScopeFields scopeFields = new AccountScopeFields();

    AccountServiceImpl(AccountRepository accountRepository, AccountTransactionRepository transactionRepository,
                        AuditService auditService, DataScopeResolver dataScopeResolver,
                        IdempotencyService idempotencyService) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.auditService = auditService;
        this.dataScopeResolver = dataScopeResolver;
        this.idempotencyService = idempotencyService;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AccountDto> list(Pageable pageable, String studentId, Authentication auth) {
        Specification<Account> scopeSpec = scopeSpecification(auth);
        Specification<Account> filterSpec = FilterSpecifications.equalTo("studentId", studentId);
        Specification<Account> combined = FilterSpecifications.and(scopeSpec, filterSpec);
        return accountRepository.findAll(combined, pageable).map(this::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public AccountDto getById(String id, Authentication auth) {
        return toDto(findInScopeOrThrow((root, query, cb) -> cb.equal(root.get("id"), id), auth));
    }

    @Override
    @Transactional(readOnly = true)
    public AccountDto getByStudentId(String studentId, Authentication auth) {
        return toDto(findInScopeOrThrow((root, query, cb) -> cb.equal(root.get("studentId"), studentId), auth));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AccountTransactionDto> listTransactions(String accountId, Pageable pageable, Authentication auth) {
        findInScopeOrThrow((root, query, cb) -> cb.equal(root.get("id"), accountId), auth);
        return transactionRepository.findByAccountIdOrderByCreatedAtDesc(accountId, pageable).map(this::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public AccountTransactionDto getTransaction(String id, Authentication auth) {
        AccountTransaction transaction = transactionRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Transaction not found."));
        findInScopeOrThrow((root, query, cb) -> cb.equal(root.get("id"), transaction.getAccountId()), auth);
        return toDto(transaction);
    }

    @Override
    @Transactional(readOnly = true)
    public int getBalanceForStudent(String studentId) {
        return accountRepository.findByStudentId(studentId).map(Account::getBalance).orElse(0);
    }

    @Override
    @Transactional
    public AccountTransactionDto credit(String studentId, String gradeId, String classId, int amountCentiCoins,
                                         String sourceType, String sourceId, String reasonCode, String operatorId) {
        Account account = getOrCreateLocked(studentId, gradeId, classId);
        if (account.isFrozen()) {
            throw ApiException.stateConflict("Account is frozen; cannot credit.");
        }
        return appendTransaction(account, TransactionType.SERVICE_CREDIT, amountCentiCoins, sourceType, sourceId,
                reasonCode, operatorId, null);
    }

    @Override
    @Transactional
    public AccountTransactionDto adjust(String accountId, AdjustmentRequest request, String actorId, String idempotencyKey) {
        return idempotencyService.execute(idempotencyKey, actorId, "POST /accounts/" + accountId + "/adjustments",
                request, AccountTransactionDto.class, () -> doAdjust(accountId, request, actorId));
    }

    private AccountTransactionDto doAdjust(String accountId, AdjustmentRequest request, String actorId) {
        Account account = lockedOrThrow(accountId);
        if (account.isFrozen()) {
            throw ApiException.stateConflict("Account is frozen; cannot adjust.");
        }
        return appendTransaction(account, TransactionType.CORRECTION, request.amount(), "adjustment", null,
                request.reasonCode(), actorId, null);
    }

    @Override
    @Transactional
    public AccountTransactionDto adjustForStudent(String studentId, String gradeId, String classId,
                                                    AdjustmentRequest request, String actorId) {
        Account account = getOrCreateLocked(studentId, gradeId, classId);
        if (account.isFrozen()) {
            throw ApiException.stateConflict("Account is frozen; cannot adjust.");
        }
        return appendTransaction(account, TransactionType.CORRECTION, request.amount(), "adjustment", null,
                request.reasonCode(), actorId, null);
    }

    @Override
    @Transactional
    public AccountTransactionDto reverse(String transactionId, ReverseRequest request, String actorId, String idempotencyKey) {
        return idempotencyService.execute(idempotencyKey, actorId, "POST /account-transactions/" + transactionId + "/reverse",
                request, AccountTransactionDto.class, () -> doReverse(transactionId, request, actorId));
    }

    private AccountTransactionDto doReverse(String transactionId, ReverseRequest request, String actorId) {
        AccountTransaction original = transactionRepository.findById(transactionId)
                .orElseThrow(() -> ApiException.notFound("Transaction not found."));
        Account account = lockedOrThrow(original.getAccountId());
        // The original row is never touched (invariant 2) — only a new,
        // opposite-signed entry referencing it is appended. Reversal is
        // deliberately allowed even on a frozen account (fixing a mistake is
        // not "new earning"), unlike credit/adjust above.
        return appendTransaction(account, TransactionType.CORRECTION, -original.getAmount(), "reversal",
                original.getId(), request.reasonCode(), actorId, original.getId());
    }

    @Override
    @Transactional
    public AccountDto freeze(String accountId, FreezeRequest request, String actorId) {
        Account account = lockedOrThrow(accountId);
        if (account.isFrozen()) {
            throw ApiException.stateConflict("Account is already frozen.");
        }
        account.setFrozen(true);
        account.setFrozenReason(request.reasonCode());
        account.setFrozenAt(Instant.now());
        appendTransaction(account, TransactionType.FREEZE, 0, "freeze", null, request.reasonCode(), actorId, null);
        return toDto(account);
    }

    @Override
    @Transactional
    public AccountDto unfreeze(String accountId, FreezeRequest request, String actorId) {
        Account account = lockedOrThrow(accountId);
        if (!account.isFrozen()) {
            throw ApiException.stateConflict("Account is not frozen.");
        }
        account.setFrozen(false);
        account.setFrozenReason(null);
        account.setFrozenAt(null);
        appendTransaction(account, TransactionType.UNFREEZE, 0, "unfreeze", null, request.reasonCode(), actorId, null);
        return toDto(account);
    }

    private Account getOrCreateLocked(String studentId, String gradeId, String classId) {
        // A single locked query for the common (already-exists) case — see
        // findWithLockByStudentId's Javadoc for why a prior unlocked
        // existence-check-then-locked-read here was a real, caught bug.
        return accountRepository.findWithLockByStudentId(studentId)
                .orElseGet(() -> {
                    // Narrow, documented race: two concurrent *first-ever* credits for
                    // the same student could both reach here and attempt to insert —
                    // the unique constraint on student_id (V5 migration) prevents a
                    // duplicate account row, but the loser's transaction fails rather
                    // than falling back to the winner's row. Acceptable at this
                    // volume/likelihood; B3.9's own concurrency proof pre-creates the
                    // account so it is not the scenario under test. Revisit with a
                    // REQUIRES_NEW insert-then-lock pattern (matching
                    // IdempotencyTransactions) if this is ever observed in practice.
                    Account account = new Account(OpaqueIds.generate("acct"), studentId, gradeId, classId, Instant.now());
                    accountRepository.saveAndFlush(account);
                    return lockedOrThrow(account.getId());
                });
    }

    private Account lockedOrThrow(String id) {
        return accountRepository.findWithLockById(id).orElseThrow(() -> ApiException.notFound("Account not found."));
    }

    private AccountTransactionDto appendTransaction(Account account, TransactionType type, int amount,
                                                     String sourceType, String sourceId, String reasonCode,
                                                     String operatorId, String reversalOfTransactionId) {
        int newBalance = account.getBalance() + amount;
        AccountTransaction transaction = new AccountTransaction(OpaqueIds.generate("txn"), account.getId(), type,
                amount, newBalance, sourceType, sourceId, reasonCode, operatorId, reversalOfTransactionId, Instant.now());
        transactionRepository.save(transaction);
        account.setBalance(newBalance);
        account.setUpdatedAt(Instant.now());

        auditService.record(new AuditEntry(operatorId, "accounts." + permissionSuffix(type),
                "account." + type.name().toLowerCase(), "account", account.getId(), null, toDto(transaction), reasonCode));
        return toDto(transaction);
    }

    private String permissionSuffix(TransactionType type) {
        return switch (type) {
            case FREEZE, UNFREEZE -> "freeze";
            case CORRECTION -> "adjust";
            default -> "read";
        };
    }

    private Account findInScopeOrThrow(Specification<Account> idSpec, Authentication auth) {
        Specification<Account> scopeSpec = scopeSpecification(auth);
        Specification<Account> combined = scopeSpec == null ? idSpec : idSpec.and(scopeSpec);
        return accountRepository.findOne(combined).orElseThrow(() -> ApiException.notFound("Account not found."));
    }

    private Specification<Account> scopeSpecification(Authentication auth) {
        TimeBankPrincipal principal = (CookieSessionUserDetails) auth.getPrincipal();
        boolean grantAll = principal.roleCodes().contains("admin") || principal.roleCodes().contains("sysadmin");
        ScopeDescriptor scope = dataScopeResolver.resolve(principal, RESOURCE_TYPE, grantAll);
        return DataScopeSpecifications.apply(scope, scopeFields, principal.studentId());
    }

    private AccountDto toDto(Account a) {
        return new AccountDto(a.getId(), a.getStudentId(), a.getBalance(), a.isFrozen(), a.getFrozenReason(),
                a.getVersion(), a.getCreatedAt(), a.getUpdatedAt());
    }

    private AccountTransactionDto toDto(AccountTransaction t) {
        return new AccountTransactionDto(t.getId(), t.getAccountId(), t.getType().name(), t.getAmount(),
                t.getBalanceAfter(), t.getSourceType(), t.getSourceId(), t.getReasonCode(), t.getOperatorId(),
                t.getReversalOfTransactionId(), t.getCreatedAt());
    }
}
