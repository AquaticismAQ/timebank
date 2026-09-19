package cn.timebank.lifecycle.infrastructure;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/**
 * The rollover's audit trail — one row per attempt, inserted then closed once,
 * never rewritten. Mirrors {@link PurgeRunRepository}, and for the same reason:
 * a process that runs unattended has to leave behind something that says what
 * it did, because nobody was watching it do it.
 */
@Repository
public class AnnualRolloverRunRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AnnualRolloverRunRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void start(String id, String academicYearId, String triggeredBy, Instant startedAt) {
        jdbcTemplate.update("""
                insert into annual_rollover_run (id, academic_year_id, status, triggered_by, started_at)
                values (:id, :year, 'RUNNING', :actor, :startedAt)
                """, new MapSqlParameterSource().addValue("id", id).addValue("year", academicYearId)
                .addValue("actor", triggeredBy).addValue("startedAt", startedAt));
    }

    public void complete(String id, int promoted, int graduated, int logins, int accounts,
                          int classes, int skipped, int inconsistent) {
        jdbcTemplate.update("""
                update annual_rollover_run set status = 'SUCCEEDED',
                    students_promoted = :promoted, students_graduated = :graduated,
                    logins_deactivated = :logins, accounts_resynced = :accounts,
                    classes_created = :classes, students_skipped = :skipped,
                    inconsistent_grades = :inconsistent, finished_at = :finishedAt
                where id = :id
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("promoted", promoted).addValue("graduated", graduated)
                .addValue("logins", logins).addValue("accounts", accounts)
                .addValue("classes", classes).addValue("skipped", skipped)
                .addValue("inconsistent", inconsistent).addValue("finishedAt", Instant.now()));
    }

    /** A run that declined to act because the year was already done — recorded, not silent. */
    public void skip(String id) {
        jdbcTemplate.update(
                "update annual_rollover_run set status = 'SKIPPED', finished_at = :at where id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("at", Instant.now()));
    }

    public void fail(String id, String errorMessage) {
        jdbcTemplate.update("""
                update annual_rollover_run set status = 'FAILED', error_message = :error, finished_at = :at
                where id = :id
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("error", errorMessage == null ? "(no message)"
                        : errorMessage.length() > 1000 ? errorMessage.substring(0, 1000) : errorMessage)
                .addValue("at", Instant.now()));
    }
}
