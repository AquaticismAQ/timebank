package cn.timebank.audit.web;

import cn.timebank.audit.application.AuditLogQueryService;
import cn.timebank.audit.application.dto.AuditLogDto;
import cn.timebank.common.error.ApiException;
import cn.timebank.common.pagination.PageQuery;
import cn.timebank.common.pagination.PageableFactory;
import cn.timebank.common.web.ApiResponse;
import cn.timebank.common.web.PageMeta;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Contract §4.15/Design.md §13 — read-only (invariant 10: no admin API can
 * modify or delete an audit log row, structurally guaranteed since this
 * controller has no `POST`/`PATCH`/`DELETE` mapping at all, not merely a
 * missing permission).
 */
@RestController
public class AuditLogController {

    /**
     * Widened by issue 098. Keys must stay identical to
     * {@code AuditLogQueryServiceImpl.SORT_COLUMNS} — an entry here with no
     * column there throws rather than sorting wrongly.
     */
    public static final Set<String> SORTABLE = Set.of("createdAt", "action", "resourceType");

    private final AuditLogQueryService auditLogQueryService;

    public AuditLogController(AuditLogQueryService auditLogQueryService) {
        this.auditLogQueryService = auditLogQueryService;
    }

    @GetMapping("/api/v1/audit-logs")
    @PreAuthorize("hasAuthority('auditLogs.read')")
    public ApiResponse<List<AuditLogDto>> list(PageQuery query) {
        Pageable pageable = PageableFactory.toPageable(query, SORTABLE);
        Page<AuditLogDto> page = auditLogQueryService.search(
                query.search(), query.filter("action"),
                query.filter("resourceType"), query.filter("resourceId"), query.filter("actorId"),
                parseInstant(query.filter("createdFrom")), parseInstant(query.filter("createdTo")), pageable);
        return ApiResponse.ofList(page.getContent(), PageMeta.of(page));
    }

    /**
     * Issue 098: the distinct {@code action} and {@code resourceType} values the
     * log actually holds, for the tab's filter dropdowns.
     *
     * <p>Served rather than hardcoded in the client because action codes accrue
     * with every feature — a literal list would quietly stop offering the newest
     * one, and the filter would look complete while being unable to select it.
     * Same reasoning as the service-category tab's server-derived codes.</p>
     *
     * <p>Under {@code auditLogs.read}, the same permission as the list: this
     * discloses nothing the list does not, and a separate permission would be a
     * second thing to get wrong.</p>
     */
    @GetMapping("/api/v1/audit-logs/facets")
    @PreAuthorize("hasAuthority('auditLogs.read')")
    public ApiResponse<AuditLogQueryService.Facets> facets() {
        return ApiResponse.of(auditLogQueryService.facets());
    }

    private Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (Exception e) {
            throw ApiException.invalidRequest("createdFrom/createdTo must be ISO-8601 instants.");
        }
    }
}
