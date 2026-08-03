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

    private static final Set<String> SORTABLE = Set.of("createdAt");

    private final AuditLogQueryService auditLogQueryService;

    public AuditLogController(AuditLogQueryService auditLogQueryService) {
        this.auditLogQueryService = auditLogQueryService;
    }

    @GetMapping("/api/v1/audit-logs")
    @PreAuthorize("hasAuthority('auditLogs.read')")
    public ApiResponse<List<AuditLogDto>> list(PageQuery query) {
        Pageable pageable = PageableFactory.toPageable(query, SORTABLE);
        Page<AuditLogDto> page = auditLogQueryService.search(
                query.filter("resourceType"), query.filter("resourceId"), query.filter("actorId"),
                parseInstant(query.filter("createdFrom")), parseInstant(query.filter("createdTo")), pageable);
        return ApiResponse.ofList(page.getContent(), PageMeta.of(page));
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
