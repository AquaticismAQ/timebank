package cn.timebank.account.application.dto;

import java.time.Instant;

/**
 * {@code totalEarned} is the sum of this account's positive transactions —
 * what the ledger says came in (issue 152). It is not derived from service
 * minutes: corrections, reversals and manual adjustments are transactions too,
 * so hours times a rate is a different number from what the account holds.
 *
 * <p>There is deliberately no {@code totalRedeemed}. Redemption is Phase 4 and
 * cannot happen, so the field could only ever be zero, and a permanent zero
 * presented as data reads as "this student redeemed nothing" rather than
 * "nothing can be redeemed".</p>
 */
public record AccountDto(String id, String studentId, int balance, int totalEarned,
                          boolean frozen, String frozenReason,
                          Long version, Instant createdAt, Instant updatedAt) {
}
