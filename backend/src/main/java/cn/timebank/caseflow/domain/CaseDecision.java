package cn.timebank.caseflow.domain;

/** Contract §4.8's `POST /cases/{id}/decisions` decision values. */
public enum CaseDecision {
    RESOLVE, REQUEST_INFORMATION, REJECT
}
