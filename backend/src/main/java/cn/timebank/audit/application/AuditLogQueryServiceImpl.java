package cn.timebank.audit.application;

import cn.timebank.audit.application.dto.AuditLogDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A purpose-built {@code NamedParameterJdbcTemplate} read, not {@code
 * JpaSpecificationExecutor} on {@code AuditLogRepository} — that interface
 * declares its own {@code delete(Specification)}, which would silently
 * reintroduce the write capability invariant 10 requires be structurally
 * absent (Design.md D6 designates JdbcTemplate for exactly this kind of
 * dynamic-filter read anyway).
 */
@Service
class AuditLogQueryServiceImpl implements AuditLogQueryService {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    AuditLogQueryServiceImpl(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AuditLogDto> search(String resourceType, String resourceId, String actorId,
                                     Instant createdFrom, Instant createdTo, Pageable pageable) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        String where = whereClause(resourceType, resourceId, actorId, createdFrom, createdTo, params);

        String sql = "select id, actor_id, permission_used, action, resource_type, resource_id, "
                + "before_summary, after_summary, reason_code, request_id, ip_address, user_agent, created_at "
                + "from audit_log" + where + " order by created_at desc limit :limit offset :offset";
        List<AuditLogDto> content = jdbcTemplate.query(sql, params, (rs, rowNum) -> new AuditLogDto(
                rs.getString("id"), rs.getString("actor_id"), rs.getString("permission_used"), rs.getString("action"),
                rs.getString("resource_type"), rs.getString("resource_id"), rs.getString("before_summary"),
                rs.getString("after_summary"), rs.getString("reason_code"), rs.getString("request_id"),
                rs.getString("ip_address"), rs.getString("user_agent"), rs.getTimestamp("created_at").toInstant()));

        String countSql = "select count(*) from audit_log" + where;
        Long total = jdbcTemplate.queryForObject(countSql, params, Long.class);
        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    private String whereClause(String resourceType, String resourceId, String actorId, Instant createdFrom,
                                Instant createdTo, MapSqlParameterSource params) {
        List<String> conditions = new ArrayList<>();
        if (resourceType != null) {
            conditions.add("resource_type = :resourceType");
            params.addValue("resourceType", resourceType);
        }
        if (resourceId != null) {
            conditions.add("resource_id = :resourceId");
            params.addValue("resourceId", resourceId);
        }
        if (actorId != null) {
            conditions.add("actor_id = :actorId");
            params.addValue("actorId", actorId);
        }
        if (createdFrom != null) {
            conditions.add("created_at >= :createdFrom");
            params.addValue("createdFrom", Timestamp.from(createdFrom));
        }
        if (createdTo != null) {
            conditions.add("created_at <= :createdTo");
            params.addValue("createdTo", Timestamp.from(createdTo));
        }
        return conditions.isEmpty() ? "" : " where " + String.join(" and ", conditions);
    }
}
