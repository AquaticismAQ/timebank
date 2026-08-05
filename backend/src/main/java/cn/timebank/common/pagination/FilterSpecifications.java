package cn.timebank.common.pagination;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Small helpers for turning a {@link PageQuery}'s raw {@code filter[...]}/
 * {@code search} values into JPA {@link Specification}s (contract §2.7).
 * Always combine the result with a resource's own data-scope Specification
 * via {@link #and} — filtering narrows what scope already allows, it never
 * substitutes for it. A {@code null} Specification is Spring Data's own
 * convention for "no predicate" (see {@code DataScopeSpecifications}).
 */
public final class FilterSpecifications {

    private FilterSpecifications() {
    }

    public static <T> Specification<T> equalTo(String field, String value) {
        if (value == null || value.isBlank()) return null;
        return (root, query, cb) -> cb.equal(root.get(field), value);
    }

    public static <T> Specification<T> equalToBoolean(String field, String value) {
        if (value == null || value.isBlank()) return null;
        boolean parsed = Boolean.parseBoolean(value);
        return (root, query, cb) -> cb.equal(root.get(field), parsed);
    }

    /** An unrecognized enum value matches nothing rather than throwing — a filter typo is an empty page, not a 500. */
    public static <T, E extends Enum<E>> Specification<T> equalToEnum(String field, Class<E> enumType, String value) {
        if (value == null || value.isBlank()) return null;
        E parsed;
        try {
            parsed = Enum.valueOf(enumType, value);
        } catch (IllegalArgumentException e) {
            return (root, query, cb) -> cb.disjunction();
        }
        return (root, query, cb) -> cb.equal(root.get(field), parsed);
    }

    public static <T> Specification<T> searchAnyContains(String search, String... fields) {
        if (search == null || search.isBlank()) return null;
        String pattern = "%" + search.toLowerCase() + "%";
        return (root, query, cb) -> cb.or(Arrays.stream(fields)
                .map(f -> cb.like(cb.lower(root.get(f)), pattern))
                .toArray(Predicate[]::new));
    }

    @SafeVarargs
    public static <T> Specification<T> and(Specification<T>... specs) {
        return Stream.of(specs).filter(Objects::nonNull).reduce(null, (a, b) -> a == null ? b : a.and(b));
    }
}
