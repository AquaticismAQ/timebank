package cn.timebank.dataexchange.infrastructure;

import cn.timebank.dataexchange.domain.HistoricalImportWindow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/**
 * The window's single row (Design/*&#47;12-DataImport.md §6).
 *
 * <p>Opening it is a native insert rather than {@code save()} on purpose.
 * §6.1's "later imports do not extend the window" must hold whatever the
 * caller does, including a reclaimed commit run re-finishing an already
 * committed job, so "open" is expressed as *insert only if absent* in one
 * statement and the row becomes immutable the moment it exists. A
 * load-then-save would need the caller to remember not to overwrite, which
 * is the kind of thing that is remembered right up until it isn't.</p>
 */
public interface HistoricalImportWindowRepository extends JpaRepository<HistoricalImportWindow, String> {

    /**
     * @return 1 when this call opened the window, 0 when it was already open
     *         (or already expired) and nothing changed. {@code ON DUPLICATE
     *         KEY UPDATE id = id} rather than {@code INSERT IGNORE}: ignore
     *         swallows unrelated errors such as a truncated value, and the one
     *         conflict worth tolerating here is the primary key.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO historical_import_window
                (id, opened_at, expires_at, window_days, opened_by_job_id, opened_by)
            VALUES (:id, :openedAt, :expiresAt, :windowDays, :jobId, :actorId)
            ON DUPLICATE KEY UPDATE id = id
            """)
    int openIfAbsent(@Param("id") String id,
                      @Param("openedAt") Instant openedAt,
                      @Param("expiresAt") Instant expiresAt,
                      @Param("windowDays") int windowDays,
                      @Param("jobId") String jobId,
                      @Param("actorId") String actorId);
}
