package cn.timebank.common.pagination;

import cn.timebank.common.error.ApiException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Turns a {@link PageQuery} into a Spring Data {@link Pageable}. Every call
 * site passes the set of properties it is willing to sort by — an
 * unindexed or unvalidated sort field is exactly the query-discipline
 * failure Design.md §16.1 calls out, so there is no path that lets a raw
 * query parameter become a JPA order-by property unchecked.
 *
 * A deterministic {@code id} tiebreaker is appended to every sort so that
 * paging through identical requests never produces undefined ordering
 * (contract §2.7 "Server must return stable sorting").
 */
public final class PageableFactory {

    private PageableFactory() {
    }

    public static Pageable toPageable(PageQuery query, Set<String> sortableProperties) {
        Set<Sort.Order> orders = new LinkedHashSet<>();
        for (PageQuery.SortToken token : query.sort()) {
            if (!sortableProperties.contains(token.property())) {
                throw ApiException.invalidRequest("Cannot sort by '" + token.property() + "'.");
            }
            orders.add(new Sort.Order(token.descending() ? Sort.Direction.DESC : Sort.Direction.ASC, token.property()));
        }
        if (sortableProperties.contains("id")) {
            orders.add(Sort.Order.asc("id"));
        }
        Sort sort = orders.isEmpty() ? Sort.unsorted() : Sort.by(new java.util.ArrayList<>(orders));
        // page[number] is 1-based on the wire; PageRequest is 0-based.
        return PageRequest.of(query.pageNumber() - 1, query.pageSize(), sort);
    }
}
