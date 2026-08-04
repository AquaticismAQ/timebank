package cn.timebank.lifecycle.infrastructure;

import cn.timebank.lifecycle.infrastructure.LedgerReconciliationRepository.MismatchCheck;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/** B6.7's audit trail, same shape as {@code PurgeRunRepository} — a run row plus one append-only mismatch row per account found drifted. */
@Repository
public class ReconciliationRunRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ReconciliationRunRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void start(String id, String mode, Instant checkpointBefore, Instant startedAt) {
        jdbcTemplate.update("""
                insert into reconciliation_run (id, mode, status, checkpoint_before, started_at)
                values (:id, :mode, 'RUNNING', :checkpointBefore, :startedAt)
                """, new MapSqlParameterSource().addValue("id", id).addValue("mode", mode)
                .addValue("checkpointBefore", checkpointBefore).addValue("startedAt", startedAt));
    }

    public void recordMismatches(String runId, List<MismatchCheck> mismatches) {
        for (MismatchCheck m : mismatches) {
            jdbcTemplate.update("""
                    insert into reconciliation_mismatch
                        (id, reconciliation_run_id, account_id, stored_balance, computed_balance, divergent_transaction_id, created_at)
                    values (:id, :runId, :accountId, :stored, :computed, :divergentId, :now)
                    """, new MapSqlParameterSource()
                    .addValue("id", cn.timebank.common.OpaqueIds.generate("recmm"))
                    .addValue("runId", runId).addValue("accountId", m.accountId())
                    .addValue("stored", m.storedBalance()).addValue("computed", m.computedBalance())
                    .addValue("divergentId", m.divergentTransactionId()).addValue("now", Instant.now()));
        }
    }

    public void complete(String id, int accountsChecked, int mismatchesFound, Instant checkpointAfter) {
        jdbcTemplate.update("""
                update reconciliation_run set status = 'SUCCEEDED', accounts_checked = :checked,
                    mismatches_found = :mismatches, checkpoint_after = :checkpointAfter, finished_at = :finishedAt
                where id = :id
                """, new MapSqlParameterSource().addValue("id", id).addValue("checked", accountsChecked)
                .addValue("mismatches", mismatchesFound).addValue("checkpointAfter", checkpointAfter)
                .addValue("finishedAt", Instant.now()));
    }

    public void fail(String id, String errorMessage) {
        jdbcTemplate.update("""
                update reconciliation_run set status = 'FAILED', error_message = :error, finished_at = :finishedAt where id = :id
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("error", errorMessage.length() > 1000 ? errorMessage.substring(0, 1000) : errorMessage)
                .addValue("finishedAt", Instant.now()));
    }

    public boolean hasSucceededSince(Instant since) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from reconciliation_run where status = 'SUCCEEDED' and started_at >= :since",
                new MapSqlParameterSource("since", since), Long.class);
        return count != null && count > 0;
    }

    public Instant currentCheckpoint() {
        // A plain queryForObject(..., Instant.class) fails: JdbcTemplate's
        // default conversion for DATETIME columns lands on LocalDateTime,
        // which has no direct Instant conversion — java.sql.Timestamp does,
        // via the JDBC driver's own timezone handling (matches the pattern
        // ReviewQueueRepository already uses for the same reason).
        List<Instant> rows = jdbcTemplate.query(
                "select last_checked_at from reconciliation_checkpoint where id = 'default'",
                new MapSqlParameterSource(), (rs, rowNum) -> rs.getTimestamp("last_checked_at").toInstant());
        return rows.isEmpty() ? Instant.EPOCH : rows.get(0);
    }

    public void advanceCheckpoint(Instant to) {
        jdbcTemplate.update("update reconciliation_checkpoint set last_checked_at = :to, updated_at = :now where id = 'default'",
                new MapSqlParameterSource().addValue("to", to).addValue("now", Instant.now()));
    }
}
