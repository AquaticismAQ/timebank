package cn.timebank.project.infrastructure;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * `enrolledCount` (contract §2.11 rule 3) and the viewer's own {@code
 * myApplicationStatus} — a legitimate cross-module *read-model query*
 * (Design.md §6, the same pattern `review`'s queue and `file`'s reference
 * lookup use) against `enrollment`'s table, avoiding a `project` ↔
 * `enrollment` Java-level cycle (`enrollment` already depends on {@code
 * ProjectService} for the capacity lock, B4.7).
 */
@Repository
public class EnrollmentReadRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public EnrollmentReadRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int countConfirmed(String projectId) {
        String sql = "select count(*) from enrollment where project_id = :projectId and status = 'CONFIRMED'";
        Integer result = jdbcTemplate.queryForObject(sql, new MapSqlParameterSource("projectId", projectId), Integer.class);
        return result == null ? 0 : result;
    }

    /** One query for a whole page of projects — avoids N+1 on the list endpoint. */
    public Map<String, Integer> countConfirmedByProjectIds(List<String> projectIds) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        String sql = """
                select project_id, count(*) as c from enrollment
                where project_id in (:projectIds) and status = 'CONFIRMED'
                group by project_id
                """;
        return jdbcTemplate.query(sql, new MapSqlParameterSource("projectIds", projectIds), rs -> {
            Map<String, Integer> counts = new java.util.HashMap<>();
            while (rs.next()) {
                counts.put(rs.getString("project_id"), rs.getInt("c"));
            }
            return counts;
        });
    }

    public Optional<String> myApplicationStatus(String projectId, String studentId) {
        String sql = "select status from enrollment where project_id = :projectId and student_id = :studentId";
        List<String> statuses = jdbcTemplate.query(sql,
                new MapSqlParameterSource("projectId", projectId).addValue("studentId", studentId),
                (rs, rowNum) -> rs.getString("status"));
        return statuses.stream().findFirst();
    }

    public Map<String, String> myApplicationStatusByProjectIds(List<String> projectIds, String studentId) {
        if (projectIds.isEmpty() || studentId == null) {
            return Map.of();
        }
        String sql = """
                select project_id, status from enrollment
                where project_id in (:projectIds) and student_id = :studentId
                """;
        List<Map.Entry<String, String>> rows = jdbcTemplate.query(sql,
                new MapSqlParameterSource("projectIds", projectIds).addValue("studentId", studentId),
                (rs, rowNum) -> Map.entry(rs.getString("project_id"), rs.getString("status")));
        return rows.stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Every project this student has ever applied to.
     *
     * Needed so that narrowing the project list to a student's own grade
     * (issue 129) cannot hide a project they are already enrolled in — a
     * project's eligible grades can be edited after the fact, and a student who
     * signed up under the old list still has a record pointing at it.
     */
    public java.util.Set<String> projectIdsAppliedToBy(String studentId) {
        if (studentId == null || studentId.isBlank()) {
            return java.util.Set.of();
        }
        return java.util.Set.copyOf(jdbcTemplate.queryForList(
                "select distinct project_id from enrollment where student_id = :studentId",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("studentId", studentId),
                String.class));
    }
}
