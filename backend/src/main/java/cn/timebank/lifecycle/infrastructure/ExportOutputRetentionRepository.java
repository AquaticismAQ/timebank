package cn.timebank.lifecycle.infrastructure;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * The SQL half of the export-output retention sweep (F-11,
 * `Docs/optimization/11-DataImportExportOptimization.md` §2.11). Raw JDBC over
 * `job`, like every other lifecycle sweep reads the table it ages out — the
 * rows are found by age and touched one column at a time, which is a query, not
 * a domain operation.
 */
@Repository
public class ExportOutputRetentionRepository {

    /** One export job still holding a produced file. */
    public record ExpiredExportOutput(String jobId, String storageKey) {
    }

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ExportOutputRetentionRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Export jobs whose output file is older than the cutoff, oldest first.
     *
     * <p>{@code kind = 'EXPORT'} deliberately: an import job's `result_ref` is
     * its row-error report, which belongs to the import's own lifecycle and is
     * a separate decision — nothing here should quietly start deleting it.</p>
     *
     * <p>{@code coalesce(finished_at, updated_at)} rather than `finished_at`
     * alone so no file can become permanently unsweepable because one timestamp
     * was never written; `updated_at` is NOT NULL, so the coalesce always has
     * an answer.</p>
     */
    public List<ExpiredExportOutput> findExportOutputsOlderThan(Instant cutoff, int limit) {
        return jdbcTemplate.query("""
                select id, result_ref from job
                where kind = 'EXPORT'
                  and result_ref is not null
                  and coalesce(finished_at, updated_at) < :cutoff
                order by coalesce(finished_at, updated_at)
                limit :limit
                """,
                new MapSqlParameterSource().addValue("cutoff", cutoff).addValue("limit", limit),
                (rs, rowNum) -> new ExpiredExportOutput(rs.getString("id"), rs.getString("result_ref")));
    }

    /**
     * Forgets the storage key, so the job row stops advertising a file that is
     * no longer there (Phase 5 exit criterion 3: `result_ref` reads NULL rather
     * than pointing at a deleted key).
     *
     * <p>Matched on the key as well as the id so this can never null out a
     * reference the sweep did not just delete, and {@code version} is bumped
     * because `job` is an optimistically-locked JPA entity elsewhere — a
     * raw-SQL update that left the version alone would make a stale in-memory
     * Job look current.</p>
     */
    public int clearResultRef(String jobId, String storageKey) {
        return jdbcTemplate.update("""
                update job set result_ref = null, version = version + 1, updated_at = :now
                where id = :jobId and result_ref = :storageKey
                """, new MapSqlParameterSource()
                .addValue("jobId", jobId)
                .addValue("storageKey", storageKey)
                .addValue("now", Instant.now()));
    }

    /** Spent download tokens (V33) age out long before the files do — the token they refuse is dead in 300 seconds. */
    public int deleteDownloadRedemptionsOlderThan(Instant cutoff) {
        return jdbcTemplate.update("delete from export_download_redemption where redeemed_at < :cutoff",
                new MapSqlParameterSource("cutoff", cutoff));
    }
}
