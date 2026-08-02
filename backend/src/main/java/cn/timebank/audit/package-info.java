/**
 * Append-only operation audit log (Design.md §13, invariant 10).
 *
 * Built in B0 because every other module's mutations depend on it from
 * their first commit. Other modules call {@link cn.timebank.audit.application.AuditService}
 * — the module's only published interface — never the repository directly.
 */
package cn.timebank.audit;
