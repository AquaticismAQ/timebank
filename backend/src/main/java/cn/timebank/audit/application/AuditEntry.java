package cn.timebank.audit.application;

/**
 * What a caller supplies; requestId, IP, and user agent are filled in from
 * {@link cn.timebank.common.web.RequestContext} by the implementation, not
 * passed explicitly — a call site should not need to know how to reach the
 * current request's context.
 *
 * {@code before} and {@code after} are arbitrary objects serialized to
 * compact JSON (Design.md §13: "not full entity snapshots") — pass a
 * purpose-built summary, not the entity itself.
 */
public record AuditEntry(String actorId, String permissionUsed, String action,
                          String resourceType, String resourceId,
                          Object before, Object after, String reasonCode) {

    public static AuditEntry of(String actorId, String action, String resourceType, String resourceId) {
        return new AuditEntry(actorId, null, action, resourceType, resourceId, null, null, null);
    }
}
