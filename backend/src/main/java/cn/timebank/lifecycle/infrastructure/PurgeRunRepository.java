package cn.timebank.lifecycle.infrastructure;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/** B6.4: the compliance audit trail for every purge run, dry or real — never updated after {@link #complete}/{@link #fail}, only inserted then closed once. */
@Repository
public class PurgeRunRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public PurgeRunRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void start(String id, int graduationYear, boolean dryRun, String policyVersion, String triggeredBy, Instant startedAt) {
        jdbcTemplate.update("""
                insert into purge_run (id, graduation_year, dry_run, status, policy_version, triggered_by, started_at)
                values (:id, :year, :dryRun, 'RUNNING', :policy, :actor, :startedAt)
                """, new MapSqlParameterSource()
                .addValue("id", id).addValue("year", graduationYear).addValue("dryRun", dryRun)
                .addValue("policy", policyVersion).addValue("actor", triggeredBy).addValue("startedAt", startedAt));
    }

    public void complete(String id, int studentsScanned, int studentsPurged, int rowsDeleted, int objectsDeleted) {
        jdbcTemplate.update("""
                update purge_run set status = 'SUCCEEDED', students_scanned = :scanned, students_purged = :purged,
                    rows_deleted = :rows, objects_deleted = :objects, finished_at = :finishedAt
                where id = :id
                """, new MapSqlParameterSource()
                .addValue("id", id).addValue("scanned", studentsScanned).addValue("purged", studentsPurged)
                .addValue("rows", rowsDeleted).addValue("objects", objectsDeleted).addValue("finishedAt", Instant.now()));
    }

    public void fail(String id, String errorMessage) {
        jdbcTemplate.update("""
                update purge_run set status = 'FAILED', error_message = :error, finished_at = :finishedAt where id = :id
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("error", errorMessage.length() > 1000 ? errorMessage.substring(0, 1000) : errorMessage)
                .addValue("finishedAt", Instant.now()));
    }

    /**
     * B6.4's non-execution alert: has the job cadence itself succeeded
     * (dry or real) within the given window? The automated schedule is
     * dry-run only (see {@code CohortPurgeJob}'s Javadoc), so a healthy
     * system produces a dry-run success daily even when no cohort is yet
     * eligible for a real purge — checking for a *real* run here would
     * false-alarm on every ordinary day.
     */
    public boolean hasSucceededSince(Instant since) {
        Long count = jdbcTemplate.queryForObject("""
                select count(*) from purge_run where status = 'SUCCEEDED' and started_at >= :since
                """, new MapSqlParameterSource("since", since), Long.class);
        return count != null && count > 0;
    }
}
