package cn.timebank.common.pagination;

import java.util.Map;

/**
 * The common query shape every list endpoint accepts (contract §2.7):
 * {@code page[number]} (1-based), {@code page[size]}, {@code sort}
 * (comma-separated, {@code -} prefix for descending), {@code filter[field]},
 * and {@code search}. Bound automatically by {@link PageQueryArgumentResolver}
 * for any controller method parameter of this type.
 */
public record PageQuery(int pageNumber, int pageSize, java.util.List<SortToken> sort,
                         Map<String, String> filters, String search) {

    public record SortToken(String property, boolean descending) {
    }

    public String filter(String field) {
        return filters.get(field);
    }
}
