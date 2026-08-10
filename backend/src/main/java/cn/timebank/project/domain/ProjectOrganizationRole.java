package cn.timebank.project.domain;

/** Docs/clarification/03-ServiceRecordFormOptimization.md §4.2 — HOST_SCHOOL is exactly one per project (V18's generated-column unique index), PARTNER is zero or more. */
public enum ProjectOrganizationRole {
    HOST_SCHOOL, PARTNER
}
