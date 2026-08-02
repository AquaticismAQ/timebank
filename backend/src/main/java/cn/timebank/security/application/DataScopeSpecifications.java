package cn.timebank.security.application;

import org.springframework.data.jpa.domain.Specification;

/**
 * Turns a {@link ScopeDescriptor} into a JPA {@link Specification} —
 * B1.7's "incorporated as query predicates" requirement, applied inside
 * the query rather than as a post-filter (Design.md §10: post-filtering
 * leaks existence through pagination counts). {@link ScopeDescriptor.All}
 * yields {@code null} (Spring Data JPA's convention for "no predicate").
 *
 * No business resource calls this yet — {@code Student}/{@code Grade}
 * (B2) and {@code Project}/{@code ServiceRecord} (B3/B4) are what
 * {@link ScopeFields} implementations will target. Proven generically now
 * against a test fixture entity so the mechanism is verified before
 * anything depends on it.
 */
public final class DataScopeSpecifications {

    private DataScopeSpecifications() {
    }

    public static <T> Specification<T> apply(ScopeDescriptor scope, ScopeFields<T> fields, String currentPrincipalId) {
        return switch (scope) {
            case ScopeDescriptor.All ignored -> null;
            case ScopeDescriptor.Self ignored ->
                    (root, query, cb) -> cb.equal(fields.ownerIdPath(root), currentPrincipalId);
            case ScopeDescriptor.Grades grades ->
                    (root, query, cb) -> fields.gradeIdPath(root).in(grades.gradeIds());
            case ScopeDescriptor.Classes classes ->
                    (root, query, cb) -> fields.classIdPath(root).in(classes.classIds());
            case ScopeDescriptor.Projects projects ->
                    (root, query, cb) -> fields.projectIdPath(root).in(projects.projectIds());
        };
    }
}
