package cn.timebank.security.application;

import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;

/**
 * What {@link DataScopeSpecifications} needs to turn a {@code
 * ScopeDescriptor} into a predicate against one entity type — the
 * "query-builder integration point" B1.7 requires later phases to use.
 * Only the paths a given resource actually supports need a non-throwing
 * implementation; the others may throw {@link UnsupportedOperationException}
 * if that scope dimension never applies to the resource.
 */
public interface ScopeFields<T> {

    Path<String> ownerIdPath(Root<T> root);

    Path<String> gradeIdPath(Root<T> root);

    Path<String> classIdPath(Root<T> root);

    Path<String> projectIdPath(Root<T> root);
}
