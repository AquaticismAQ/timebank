package cn.timebank.proposal.domain;

/** Contract §3.2's real state machine (the frontend mock's `needsRevision`/no-`underReview` shape lost this divergence, resolved in the contract's favor — same as divergence #1). */
public enum ProposalStatus {
    DRAFT, SUBMITTED, UNDER_REVIEW, CHANGES_REQUESTED, APPROVED, REJECTED
}
