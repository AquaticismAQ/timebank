package cn.timebank.project.domain;

/** Docs/clarification/03-ServiceRecordFormOptimization.md §4.2 — never inferred from organization type; HYBRID also means "unknown, backfilled" for projects that pre-date this field (V18). */
public enum DeliveryScope {
    ON_CAMPUS, OFF_CAMPUS, HYBRID
}
