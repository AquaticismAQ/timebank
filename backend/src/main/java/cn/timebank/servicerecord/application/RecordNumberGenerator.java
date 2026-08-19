package cn.timebank.servicerecord.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Docs/optimization/03-ServiceRecordFormOptimization.md §5.3/§10 D3: takes the next
 * per-(academic year, student) sequence number for {@code recordNumber} atomically —
 * one {@code INSERT ... ON DUPLICATE KEY UPDATE seq = LAST_INSERT_ID(seq + 1)}
 * statement, no separate {@code SELECT ... FOR UPDATE} read to hold a lock across.
 * {@code LAST_INSERT_ID(expr)} is a documented MySQL idiom for exactly this
 * "generate my own sequence" case — it sets the session's last-insert-id to the
 * given expression, readable back via {@code SELECT LAST_INSERT_ID()} on the same
 * connection. Must run inside the caller's existing transaction so both statements
 * share one connection (Spring binds one connection per transaction).
 *
 * <p>The {@code VALUES} clause's initial seed also goes through {@code LAST_INSERT_ID(1)},
 * not a bare {@code 1} — {@code ON DUPLICATE KEY UPDATE} (and the {@code LAST_INSERT_ID}
 * call inside it) only ever executes when the row already exists, so a bare {@code 1}
 * would leave the session's last-insert-id value completely untouched on every
 * genuinely first insert for a given (year, student) — {@code SELECT LAST_INSERT_ID()}
 * would then return whatever a *different*, unrelated earlier call left behind on this
 * pooled connection, not the 1 just inserted. Caught live: two records for the same new
 * student collided on the same generated number because the first one silently picked up
 * a stale value from a prior call sharing the connection.
 */
@Component
class RecordNumberGenerator {

    private final JdbcTemplate jdbcTemplate;

    RecordNumberGenerator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    int nextSequence(String academicYearId, String studentId) {
        jdbcTemplate.update(
                "INSERT INTO record_number_seq (academic_year_id, student_id, seq) VALUES (?, ?, LAST_INSERT_ID(1)) "
                        + "ON DUPLICATE KEY UPDATE seq = LAST_INSERT_ID(seq + 1)",
                academicYearId, studentId);
        Integer seq = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Integer.class);
        return seq == null ? 1 : seq;
    }
}
