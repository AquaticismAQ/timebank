package cn.timebank.dataexchange.application;

import cn.timebank.dataexchange.application.dto.HistoricalImportWindowDto;

/**
 * The 15-day window on historical service-record import
 * (Design/*&#47;11-DataImport.md §6).
 *
 * <p>Historical import is a migration-era capability and the only bulk write
 * that credits the time-coin ledger directly, so it is not left one click away
 * once the migration is over: the first successful import starts a clock, later
 * imports run inside it without extending it, and when it runs out the feature
 * is closed for good. Roster import is a different type and is never affected —
 * a school takes in new students every year.</p>
 *
 * <p>Nothing reachable over HTTP reopens the window. The only route back is a
 * maintenance utility on the server console, specified but deliberately not
 * built: Docs/backlog/01-HistoricalImportReenableUtility.md.</p>
 */
public interface HistoricalImportWindowService {

    /**
     * Refuses a new historical import once the window has expired. A no-op for
     * every other import type, and before the first historical import — where
     * the window does not exist yet and uploading is exactly what opens it.
     */
    void requireImportable(String importType);

    /**
     * The second of §2.9's two enforcement points. A job that reached
     * {@code VALIDATED} while the window was open must not commit after it has
     * closed (Q-8) — otherwise "validate now, commit whenever" leaves the door
     * open indefinitely, which is the thing §6 exists to prevent.
     */
    void requireCommittable(String importType);

    /**
     * Opens the window if it is not already open, for the first historical
     * import to reach {@code COMMITTED}. Idempotent by design: calling it again
     * — including from a reclaimed run re-finishing an already committed job —
     * changes nothing, because §6.1 says the clock is started once.
     */
    void openOnFirstCommit(String jobId, String actorId);

    /** §6.2's three interface states, in one read. */
    HistoricalImportWindowDto state();
}
