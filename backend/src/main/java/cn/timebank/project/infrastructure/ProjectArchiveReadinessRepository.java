package cn.timebank.project.infrastructure;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Cross-module read-model query (same justification as {@link
 * EnrollmentReadRepository}'s own doc comment) joining `enrollment` and
 * `service_record` — both owned by other modules — to answer "is this
 * completed project ready to archive": every {@code CONFIRMED}-enrolled
 * student's service record for the project must have reached a terminal
 * review decision ({@code APPROVED}/{@code REJECTED}/{@code CORRECTED}). A
 * {@code CONFIRMED} student with no record at all, or one still {@code
 * DRAFT}/{@code SUBMITTED}/{@code UNDER_REVIEW}/{@code RETURNED}/{@code
 * APPEAL_PENDING}, counts as unresolved and blocks the normal archive path
 * (02-PorjectOrganization.md §2.2 — {@code ProjectStateMachine.cancel} is
 * the deliberate override for a stalled/no-show case, not this gate).
 */
@Repository
public class ProjectArchiveReadinessRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ProjectArchiveReadinessRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int countUnresolvedConfirmedStudents(String projectId) {
        String sql = """
                select count(*) from enrollment e
                where e.project_id = :projectId and e.status = 'CONFIRMED'
                and not exists (
                    select 1 from service_record sr
                    where sr.project_id = e.project_id and sr.student_id = e.student_id
                    and sr.status in ('APPROVED', 'REJECTED', 'CORRECTED')
                )
                """;
        Integer result = jdbcTemplate.queryForObject(sql, new MapSqlParameterSource("projectId", projectId), Integer.class);
        return result == null ? 0 : result;
    }
}
