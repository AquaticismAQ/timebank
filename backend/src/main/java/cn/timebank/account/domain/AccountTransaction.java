package cn.timebank.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Immutable ledger entry (invariant 2) — once written, never updated or
 * deleted. {@link cn.timebank.account.infrastructure.AccountTransactionRepository}
 * enforces this structurally, the same way {@code AuditLogRepository} does
 * (extends the bare {@code Repository} marker, no derivable update/delete).
 *
 * {@code amount} is signed centi-coins, integer only (§22.1, B3.13) — 0 for
 * a {@code FREEZE}/{@code UNFREEZE} marker entry, which exists purely so
 * the account's timeline shows the event.
 */
@Entity
@Table(name = "account_transaction")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccountTransaction {

    @Id
    @Column(name = "id", length = 32, nullable = false, updatable = false)
    private String id;

    @Column(name = "account_id", length = 32, nullable = false, updatable = false)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 16, nullable = false, updatable = false)
    private TransactionType type;

    @Column(name = "amount", nullable = false, updatable = false)
    private int amount;

    @Column(name = "balance_after", nullable = false, updatable = false)
    private int balanceAfter;

    @Column(name = "source_type", length = 32, updatable = false)
    private String sourceType;

    @Column(name = "source_id", length = 32, updatable = false)
    private String sourceId;

    @Column(name = "reason_code", length = 64, nullable = false, updatable = false)
    private String reasonCode;

    @Column(name = "operator_id", length = 32, nullable = false, updatable = false)
    private String operatorId;

    @Column(name = "reversal_of_transaction_id", length = 32, updatable = false)
    private String reversalOfTransactionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public AccountTransaction(String id, String accountId, TransactionType type, int amount, int balanceAfter,
                               String sourceType, String sourceId, String reasonCode, String operatorId,
                               String reversalOfTransactionId, Instant now) {
        this.id = id;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.reasonCode = reasonCode;
        this.operatorId = operatorId;
        this.reversalOfTransactionId = reversalOfTransactionId;
        this.createdAt = now;
    }
}
