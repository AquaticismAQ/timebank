package cn.timebank.account.domain;

/**
 * Contract §3.7. {@code REDEMPTION}/{@code BORROW}/{@code REPAYMENT}/
 * {@code BONUS} are Phase-4-reserved — carried in the enum so the wire
 * vocabulary is stable when those features land, but nothing in Phase 2
 * writes them (same extension-seam convention as {@code feature_flag}).
 */
public enum TransactionType {
    SERVICE_CREDIT, CORRECTION, FREEZE, UNFREEZE, REDEMPTION, BORROW, REPAYMENT, BONUS
}
