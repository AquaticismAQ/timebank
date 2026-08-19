package cn.timebank.servicerecord.domain;

/**
 * Docs/optimization/03-ServiceRecordFormOptimization.md §5.1/§10 Q1: {@code PROJECT_OWNER}
 * is backend-derived only (PROJECT-sourced records always use the project's owner,
 * never a client-supplied choice). {@code INTERNAL_USER}/{@code STUDENT} resolve a
 * real account's snapshot server-side; {@code EXTERNAL_MANUAL} is the only kind whose
 * name/contact come directly from the client (SELF source only).
 */
public enum SupervisorKind {
    PROJECT_OWNER, INTERNAL_USER, STUDENT, EXTERNAL_MANUAL
}
