package cn.timebank.lifecycle.infrastructure;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * Committed immediately, in its own transaction.
     *
     * <p>The rollover runs as one transaction so a failure part-way leaves no
     * half-promoted school. The cost of that is this row: written inside the same
     * transaction it would be rolled back along with the work, and a failed
     * rollover would leave <b>no trace at all</b> — which is the opposite of what
     * V30 exists for. An operator arriving the morning after a failed turnover
     * would find an unpromoted school and nothing saying why.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void start(String id, String academicYearId, String triggeredBy, Instant startedAt) {
        jdbcTemplate.update("""
                insert into annual_rollover_run (id, academic_year_id, status, triggered_by, started_at)
                values (:id, :year, 'RUNNING', :actor, :startedAt)
                """, new MapSqlParameterSource().addValue("id", id).addValue("year", academicYearId)
                .addValue("actor", triggeredBy).addValue("startedAt", startedAt));
    }

    /**
     * <b>Deliberately not {@code REQUIRES_NEW}</b> — unlike every other method
     * here. Success has to commit atomically with the work it describes: a
     * SUCCEEDED row that committed while the promotion rolled back would tell the
     * idempotency guard the year was done, and the school would never be promoted
     * at all, silently and permanently. Evidence of an <em>attempt</em> must
     * survive a rollback; a claim of <em>success</em> must not.
     */
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

    /**
     * A run that declined to act because the year was already done — recorded, not
     * silent.
     *
     * <p>Joins the caller's transaction, unlike {@link #start} and {@link #fail}.
     * It is written on a path that commits normally, so it needs no protection
     * from a rollback — and it runs while the work holds the target year locked,
     * where a separate transaction would block on InnoDB's parent-row lock instead
     * of simply committing.</p>
     */
    public void skip(String id) {
        jdbcTemplate.update(
                "update annual_rollover_run set status = 'SKIPPED', finished_at = :at where id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("at", Instant.now()));
    }

    /**
     * What actually keeps this record alive is the <b>call site</b>:
     * {@code AnnualRolloverServiceImpl} records the failure after the work's
     * transaction has already rolled back and released its locks. Written from
     * inside that transaction the row would be rolled back with everything else,
     * and a failed rollover would leave no trace at all.
     *
     * <p>{@code REQUIRES_NEW} here is a second line of defence, not the mechanism:
     * it does nothing in the current structure, because there is no surrounding
     * transaction to escape. If a future caller ever wraps the rollover in one,
     * this turns silent loss of the record into a loud lock-wait timeout — InnoDB
     * holds a lock on the referenced {@code academic_year} row, which the work has
     * taken {@code FOR UPDATE}. A stall that gets noticed beats a record that
     * quietly disappears.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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
