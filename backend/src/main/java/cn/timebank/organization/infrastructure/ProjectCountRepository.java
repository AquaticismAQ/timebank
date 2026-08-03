package cn.timebank.organization.infrastructure;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * B4 fills in the `projectCount` placeholder B2 left (contract §2.11 rule 3's
 * aggregate had nothing to aggregate over until `project` existed). A
 * legitimate cross-module *read-model query* (Design.md §6's "or a
 * read-model query" clause, the same pattern `review`'s queue and `file`'s
 * reference lookup already use) — raw SQL carries no Java class dependency
 * on `project.domain`, so `organization` never depends on `project`.
 */
@Repository
public class ProjectCountRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ProjectCountRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int countByOrganizationId(String organizationId) {
        String sql = "select count(*) from project where organization_id = :organizationId and status <> 'ARCHIVED'";
        Integer result = jdbcTemplate.queryForObject(sql, new MapSqlParameterSource("organizationId", organizationId), Integer.class);
        return result == null ? 0 : result;
    }
}
