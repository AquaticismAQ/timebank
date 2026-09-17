package cn.timebank.audit.application;

import cn.timebank.audit.application.dto.AuditLogDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A purpose-built {@code NamedParameterJdbcTemplate} read, not {@code
 * JpaSpecificationExecutor} on {@code AuditLogRepository} — that interface
 * declares its own {@code delete(Specification)}, which would silently
 * reintroduce the write capability invariant 10 requires be structurally
 * absent (Design.md D6 designates JdbcTemplate for exactly this kind of
 * dynamic-filter read anyway).
 *
 * <h2>The join to {@code user} (issue 098)</h2>
 *
 * <p>{@code actor_id} is an opaque id, and the frontend used to turn it into a
 * name by searching the one page of accounts the Users tab happened to have
 * loaded — so the name shown depended on unrelated state on another tab, and
 * fell back to the raw id for any actor outside it. Resolved here instead, in
 * the same statement, which also makes the actor's name searchable.</p>
 *
 * <p>A {@code left join}, never an inner one: {@code actor_id} is null for
 * system-generated rows (a scheduled sweep has no actor), and an inner join
 * would delete exactly those rows from the log — the ones no human can be asked
 * about. The join is raw SQL and carries no Java dependency on {@code iam},
 * which is the same argument {@code UserListQueryRepository} makes for reaching
 * {@code student}.</p>
 */
@Service
class AuditLogQueryServiceImpl implements AuditLogQueryService {

    /**
     * Maps a wire-level sort property to its SQL expression. Must stay
     * key-for-key identical to {@code AuditLogController.SORTABLE} — that
     * whitelist is the only thing between a raw {@code sort} parameter and this
     * map, and nothing here is string-concatenated from user input.
     */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "createdAt", "a.created_at",
            "action", "a.action",
            "resourceType", "a.resource_type");

    /**
     * Appended to every ordering. {@code created_at} is not unique — a single
     * transaction writes several rows within the same millisecond — so without
     * a tiebreaker MySQL is free to return them in different orders on
     * different pages, which duplicates some rows across a page boundary and
     * drops others entirely.
     */
    private static final String TIEBREAKER = "a.id desc";

    private static final String SELECT_COLUMNS = """
            select a.id, a.actor_id, u.display_name as actor_display_name, a.permission_used, a.action,
                   a.resource_type, a.resource_id, a.before_summary, a.after_summary, a.reason_code,
                   a.request_id, a.ip_address, a.user_agent, a.created_at
            """;

    private static final String FROM_AND_JOINS = """
            from audit_log a
            left join `user` u on u.id = a.actor_id
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    AuditLogQueryServiceImpl(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AuditLogDto> search(String search, String action, String resourceType, String resourceId,
                                     String actorId, Instant createdFrom, Instant createdTo, Pageable pageable) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        String where = whereClause(search, action, resourceType, resourceId, actorId, createdFrom, createdTo, params);

        String sql = SELECT_COLUMNS + FROM_AND_JOINS + where
                + orderByClause(pageable.getSort()) + " limit :limit offset :offset";
        List<AuditLogDto> content = jdbcTemplate.query(sql, params, (rs, rowNum) -> new AuditLogDto(
                rs.getString("id"), rs.getString("actor_id"), rs.getString("actor_display_name"),
                rs.getString("permission_used"), rs.getString("action"),
                rs.getString("resource_type"), rs.getString("resource_id"), rs.getString("before_summary"),
                rs.getString("after_summary"), rs.getString("reason_code"), rs.getString("request_id"),
                rs.getString("ip_address"), rs.getString("user_agent"), rs.getTimestamp("created_at").toInstant()));

        String countSql = "select count(*) " + FROM_AND_JOINS + where;
        Long total = jdbcTemplate.queryForObject(countSql, params, Long.class);
        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    /**
     * Two statements rather than one grouped read: the two lists are
     * independent, and a single {@code union} would need a discriminator column
     * and would still scan the table twice. Both are cheap — MySQL answers them
     * from an index-less scan of a table this size in milliseconds — and the
     * result changes only when a new kind of action is written for the first
     * time.
     *
     * <p>Deliberately <b>not</b> filtered by the caller's current filters: these
     * populate the dropdowns, so narrowing them to what the active filter
     * already matches would make it impossible to switch to any other value.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public Facets facets() {
        return new Facets(
                jdbcTemplate.getJdbcTemplate().queryForList(
                        "select distinct action from audit_log order by action", String.class),
                jdbcTemplate.getJdbcTemplate().queryForList(
                        "select distinct resource_type from audit_log order by resource_type", String.class));
    }

    /**
     * Package-private so a unit test can assert that each supplied filter
     * actually reaches the SQL. A parameter that is accepted and then ignored is
     * indistinguishable from one that works — which is precisely what
     * {@code search} was before issue 098, and what the whole {@code order by}
     * was.
     */
    String whereClause(String search, String action, String resourceType, String resourceId, String actorId,
                        Instant createdFrom, Instant createdTo, MapSqlParameterSource params) {
        List<String> conditions = new ArrayList<>();
        if (search != null && !search.isBlank()) {
            // The actor's *name* first: it is the only term in this row an
            // administrator knows by heart. `request_id` is here because it is
            // what an error envelope hands a user to quote back.
            params.addValue("search", "%" + search.trim().toLowerCase() + "%");
            conditions.add("(lower(u.display_name) like :search or lower(a.action) like :search"
                    + " or lower(a.resource_id) like :search or lower(a.request_id) like :search)");
        }
        if (action != null && !action.isBlank()) {
            conditions.add("a.action = :action");
            params.addValue("action", action);
        }
        if (resourceType != null && !resourceType.isBlank()) {
            conditions.add("a.resource_type = :resourceType");
            params.addValue("resourceType", resourceType);
        }
        if (resourceId != null && !resourceId.isBlank()) {
            conditions.add("a.resource_id = :resourceId");
            params.addValue("resourceId", resourceId);
        }
        if (actorId != null && !actorId.isBlank()) {
            conditions.add("a.actor_id = :actorId");
            params.addValue("actorId", actorId);
        }
        if (createdFrom != null) {
            conditions.add("a.created_at >= :createdFrom");
            params.addValue("createdFrom", Timestamp.from(createdFrom));
        }
        if (createdTo != null) {
            conditions.add("a.created_at <= :createdTo");
            params.addValue("createdTo", Timestamp.from(createdTo));
        }
        return conditions.isEmpty() ? "" : " where " + String.join(" and ", conditions);
    }

    /**
     * Honours the requested sort (issue 098). This was a hardcoded
     * {@code order by created_at desc}, so {@code ?sort=createdAt} — which the
     * controller validated and accepted — returned newest-first exactly like
     * {@code -createdAt}, and ascending order could not be requested at all.
     *
     * <p>Newest-first remains the default: an audit log is read from the top,
     * and an empty sort arriving here means nobody asked.</p>
     */
    String orderByClause(Sort sort) {
        if (sort == null || sort.isUnsorted()) {
            return " order by a.created_at desc, " + TIEBREAKER;
        }
        LinkedHashMap<String, String> terms = new LinkedHashMap<>();
        sort.forEach(order -> {
            String column = SORT_COLUMNS.get(order.getProperty());
            if (column == null) {
                throw new IllegalStateException("Unmapped audit-log sort property: " + order.getProperty());
            }
            terms.put(column, column + (order.isDescending() ? " desc" : " asc"));
        });
        // The tiebreaker is appended unless the caller already ordered by
        // something unique enough to make it redundant.
        String ordering = terms.values().stream().collect(Collectors.joining(", "));
        return " order by " + ordering + ", " + TIEBREAKER;
    }
}
