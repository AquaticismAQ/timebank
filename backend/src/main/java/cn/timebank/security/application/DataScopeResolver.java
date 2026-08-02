package cn.timebank.security.application;

/**
 * B1.7. Resolves scope from **assignment data only** — {@code
 * teacher_scope_assignment} rows plus the SELF fallback when a principal
 * has none. Whether a principal is entitled to {@link ScopeDescriptor.All}
 * for a given resource type is a decision that depends on permission
 * semantics specific to that resource (e.g. which permission means
 * "school-wide" for service records is not the same question as for
 * projects), so that decision belongs to the calling module's own {@code
 * @PreAuthorize} checks — this resolver only ever returns {@code All} if
 * the caller explicitly asks it to (see {@link #resolve}), never by
 * inferring it from a role or permission name itself.
 */
public interface DataScopeResolver {

    /**
     * @param principal    whose scope is being resolved
     * @param resourceType an opaque scope namespace, e.g. {@code "serviceRecords"}, {@code "projects"}
     * @param grantAll     the caller's own determination that this principal has unrestricted
     *                     access to this resource type (typically from a permission check already
     *                     made via {@code @PreAuthorize}) — when true, short-circuits to {@link ScopeDescriptor.All}
     */
    ScopeDescriptor resolve(TimeBankPrincipal principal, String resourceType, boolean grantAll);
}
