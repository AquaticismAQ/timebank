package cn.timebank.review.infrastructure;

import cn.timebank.common.i18n.LocalizedNames;
import cn.timebank.review.application.dto.ReviewQueueItemDto;
import cn.timebank.security.application.ScopeDescriptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

/**
 * B3.6: a purpose-built read DTO via {@code JdbcTemplate}, never an entity
 * graph — the review queue is one of the two busiest admin surfaces at
 * 5,000 students (the phase doc's own words). This is a legitimate
 * cross-module *read-model query* (Design.md §6's "or a read-model query"
 * clause) against `service_record`/`service_category` — raw SQL carries no
 * Java class dependency on `servicerecord.domain`, so it doesn't cross
 * ArchUnit's module boundary rule the way a JPA entity reference would.
 */
@Repository
public class ReviewQueueRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ReviewQueueRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ReviewQueueItemDto> queryPage(ScopeDescriptor scope, String resolvedLanguage, int limit, int offset) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", limit).addValue("offset", offset);
        String where = scopeClause(scope, params);

        String sql = """
                select sr.id, sr.student_id, sr.student_name, sr.source_type, sr.service_date, sr.duration_minutes,
                       sr.service_category_id, sc.name_zh, sc.name_en, sr.status, sr.submitted_at,
                       sr.has_anomaly, sr.anomaly_note, sr.assigned_reviewer_id
                from service_record sr
                join service_category sc on sc.id = sr.service_category_id
                where sr.status in ('SUBMITTED', 'UNDER_REVIEW')""" + where + """

                order by sr.submitted_at asc
                limit :limit offset :offset
                """;

        return jdbcTemplate.query(sql, params, (rs, rowNum) -> {
            Instant submittedAt = rs.getTimestamp("submitted_at") != null ? rs.getTimestamp("submitted_at").toInstant() : null;
            boolean hasAnomaly = rs.getBoolean("has_anomaly");
            long daysWaiting = submittedAt != null ? ChronoUnit.DAYS.between(submittedAt, Instant.now()) : 0;
            String priority = hasAnomaly || daysWaiting > 3 ? "high" : daysWaiting > 1 ? "medium" : "low";
            return new ReviewQueueItemDto(
                    rs.getString("id"), rs.getString("student_id"), rs.getString("student_name"),
                    rs.getString("source_type"), rs.getObject("service_date", LocalDate.class),
                    rs.getInt("duration_minutes"), rs.getString("service_category_id"),
                    LocalizedNames.pick(resolvedLanguage, rs.getString("name_zh"), rs.getString("name_en")),
                    rs.getString("status"), submittedAt, daysWaiting, priority, hasAnomaly,
                    rs.getString("anomaly_note"), rs.getString("assigned_reviewer_id"));
        });
    }

    public long countPending(ScopeDescriptor scope) {
        return count(scope, "sr.status in ('SUBMITTED', 'UNDER_REVIEW')");
    }

    public long countAnomalies(ScopeDescriptor scope) {
        return count(scope, "sr.status in ('SUBMITTED', 'UNDER_REVIEW') and sr.has_anomaly = 1");
    }

    public long countOverdue(ScopeDescriptor scope) {
        return count(scope,
                "sr.status in ('SUBMITTED', 'UNDER_REVIEW') and sr.submitted_at < (now() - interval 3 day)");
    }

    private long count(ScopeDescriptor scope, String baseWhere) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String scopeClause = scopeClause(scope, params);
        String sql = "select count(*) from service_record sr where " + baseWhere + scopeClause;
        Long result = jdbcTemplate.queryForObject(sql, params, Long.class);
        return result == null ? 0 : result;
    }

    /** Appends an `and ...` fragment for the resolved scope — bound parameters only, never string-interpolated values. */
    private String scopeClause(ScopeDescriptor scope, MapSqlParameterSource params) {
        return switch (scope) {
            case ScopeDescriptor.All ignored -> "";
            case ScopeDescriptor.Grades grades -> {
                params.addValue("gradeIds", nonEmpty(grades.gradeIds()));
                yield " and sr.grade_id in (:gradeIds)";
            }
            case ScopeDescriptor.Classes classes -> {
                params.addValue("classIds", nonEmpty(classes.classIds()));
                yield " and sr.class_id in (:classIds)";
            }
            // A reviewer's scope is never Self/Projects in practice (only students get
            // Self; service records aren't project-scoped for review purposes) — both
            // safely resolve to "match nothing" rather than throwing.
            case ScopeDescriptor.Self ignored -> " and 1 = 0";
            case ScopeDescriptor.Projects ignored -> " and 1 = 0";
        };
    }

    private Set<String> nonEmpty(Set<String> ids) {
        return ids.isEmpty() ? Set.of("__none__") : ids;
    }
}
