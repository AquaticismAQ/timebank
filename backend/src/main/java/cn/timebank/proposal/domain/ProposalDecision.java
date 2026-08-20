package cn.timebank.proposal.domain;

/**
 * Contract §4.3. {@code RECOMMEND_APPROVAL} (a no-op "keep at UNDER_REVIEW, log an
 * internal-only endorsement" decision) was removed 2026-08-20 — approval was already a
 * two-step process (proposal approval, then a separate project publish), and adding a
 * third "recommend, then someone else approves" tier made the review workflow more
 * complex than the two-step approve/publish split needed. No live data ever recorded
 * this decision (confirmed before removal), so no migration was needed.
 */
public enum ProposalDecision {
    REQUEST_CHANGES, APPROVE, REJECT
}
