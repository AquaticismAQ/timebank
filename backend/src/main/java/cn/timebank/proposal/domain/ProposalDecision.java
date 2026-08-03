package cn.timebank.proposal.domain;

/** Contract §4.3. {@code RECOMMEND_APPROVAL} keeps the proposal at `UNDER_REVIEW` — same shape as B3's `ESCALATE`. */
public enum ProposalDecision {
    REQUEST_CHANGES, RECOMMEND_APPROVAL, APPROVE, REJECT
}
