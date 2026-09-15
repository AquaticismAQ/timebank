package cn.timebank.caseflow.domain;

/**
 * Contract §3.6's state machine: draft → submitted → resolved, branching
 * needsInformation/rejected, and submitted → draft for a withdrawal.
 *
 * <p>{@code IN_REVIEW} was removed 2026-09-16 (issue 082, the sibling of
 * issue 080's identical finding for proposals): the only way in was a hop
 * inside the decision transaction that was never committed, and unlike
 * proposals there is not even a claim endpoint that could have parked a case
 * there. A reviewer decides in one step.
 */
public enum CaseStatus {
    DRAFT, SUBMITTED, NEEDS_INFORMATION, RESOLVED, REJECTED
}
