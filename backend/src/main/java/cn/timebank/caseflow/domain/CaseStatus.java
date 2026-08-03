package cn.timebank.caseflow.domain;

/** Contract §3.6's state machine: draft → submitted → inReview → resolved, branching needsInformation/rejected. */
public enum CaseStatus {
    DRAFT, SUBMITTED, IN_REVIEW, NEEDS_INFORMATION, RESOLVED, REJECTED
}
