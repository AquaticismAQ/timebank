package cn.timebank.audit.application;

/**
 * The audit module's only published interface (Design.md §6 dependency
 * rule: cross-module access goes through the owning module's service
 * interface). Every other module depends on this, never on the
 * repository or the entity.
 */
public interface AuditService {

    /**
     * Writes one append-only row. Must be called from inside the same
     * business transaction as the change it records (Design.md §12
     * invariant "written for login, permission changes, review decisions,
     * account changes, rule publication, import/export, and evidence
     * access") — this method joins the caller's transaction rather than
     * opening its own, so a rollback of the business change rolls the
     * audit row back with it.
     */
    void record(AuditEntry entry);
}
