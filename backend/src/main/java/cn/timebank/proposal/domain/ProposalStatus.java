package cn.timebank.proposal.domain;

/**
 * Contract §3.2's real state machine.
 *
 * <p>{@code UNDER_REVIEW} was removed 2026-09-16 (issue 080): it became
 * unreachable when the two-tier {@code recommendApproval} decision went away
 * on 2026-08-20, since the only remaining way in was a hop inside the
 * decision transaction that was never committed. A reviewer decides in one
 * step, so a proposal goes straight from {@code SUBMITTED} to its outcome.
 */
public enum ProposalStatus {
    DRAFT, SUBMITTED, CHANGES_REQUESTED, APPROVED, REJECTED
}
