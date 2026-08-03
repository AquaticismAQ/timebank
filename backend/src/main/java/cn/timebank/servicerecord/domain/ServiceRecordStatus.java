package cn.timebank.servicerecord.domain;

/** Contract §3.4. {@code APPEAL_PENDING}/{@code CORRECTED} are reachable per the contract's diagram but nothing transitions into them until B5 (cases, manual correction) exists — the edges are defined now so B5 only adds a caller, not a state-machine change. */
public enum ServiceRecordStatus {
    DRAFT, SUBMITTED, UNDER_REVIEW, APPROVED, RETURNED, REJECTED, APPEAL_PENDING, CORRECTED
}
