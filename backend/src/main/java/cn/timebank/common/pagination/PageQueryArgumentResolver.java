package cn.timebank.common.pagination;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a {@link PageQuery} controller parameter straight from
 * {@code page[number]}, {@code page[size]}, {@code sort}, {@code filter[...]},
 * and {@code search} query parameters (contract §2.7) — so every list
 * endpoint takes one parameter instead of five, and the 100-row server-side
 * cap (Design.md §16.1) lives in exactly one place.
 */
@Component
public class PageQueryArgumentResolver implements HandlerMethodArgumentResolver {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(PageQuery.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                   NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);

        int pageNumber = parseInt(request.getParameter("page[number]"), 1);
        if (pageNumber < 1) {
            pageNumber = 1;
        }
        int pageSize = parseInt(request.getParameter("page[size]"), DEFAULT_PAGE_SIZE);
        if (pageSize < 1) {
            pageSize = DEFAULT_PAGE_SIZE;
        }
        pageSize = Math.min(pageSize, MAX_PAGE_SIZE);

        List<PageQuery.SortToken> sort = parseSort(request.getParameter("sort"));
        Map<String, String> filters = parseFilters(request);
        String search = request.getParameter("search");

        return new PageQuery(pageNumber, pageSize, sort, filters, search);
    }

    private int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private List<PageQuery.SortToken> parseSort(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .map(token -> token.startsWith("-")
                        ? new PageQuery.SortToken(token.substring(1), true)
                        : new PageQuery.SortToken(token, false))
                .toList();
    }

    private Map<String, String> parseFilters(HttpServletRequest request) {
        Map<String, String> filters = new LinkedHashMap<>();
        java.util.Enumeration<String> names = request.getParameterNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (name.startsWith("filter[") && name.endsWith("]")) {
                String field = name.substring("filter[".length(), name.length() - 1);
                String value = request.getParameter(name);
                if (value != null && !value.isBlank()) {
                    filters.put(field, value);
                }
            }
        }
        return filters;
    }
}
