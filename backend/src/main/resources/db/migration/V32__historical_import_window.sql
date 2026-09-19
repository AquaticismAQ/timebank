-- The 15-day window on historical service-record import
-- (Design/*/11-DataImport.md §6, plan 11-DataImportExportOptimization.md §2.9).
--
-- One row, never more: the window is state on the *import feature*, not on a
-- job, so the primary key is a fixed literal id and "is the window open" is a
-- single-row read. Opening is an INSERT the first committed historical import
-- performs with ON DUPLICATE KEY UPDATE nothing, which is what makes §6.1's
-- "later imports do not extend it" true by construction rather than by a
-- check someone has to remember to write.
--
-- Not a `system_settings` key/value pair: the window has four facts that are
-- only meaningful together (when it opened, when it expires, what opened it,
-- and later who reopened it and why), and four rows in a key/value store
-- cannot be read or written atomically.
--
-- Not derived from the job table either (`min(finished_at)` over committed
-- historical jobs). That reads correctly today and becomes wrong the moment
-- the console re-enable utility (Docs/backlog/01-HistoricalImportReenableUtility.md)
-- sets a new expiry, because a derivation has nowhere to put one.
CREATE TABLE historical_import_window (
    id                VARCHAR(32)  NOT NULL, -- fixed literal, see HistoricalImportWindow.SINGLETON_ID
    opened_at         DATETIME(3)  NOT NULL,
    -- Stored, not computed as opened_at + 15 days at read time: the applied
    -- length is a fact about this window, and the re-enable utility is
    -- specified to set a different one. A reader must not need to know which
    -- rule produced the expiry it is looking at.
    expires_at        DATETIME(3)  NOT NULL,
    window_days       INT          NOT NULL,
    opened_by_job_id  VARCHAR(32)  NOT NULL, -- the import whose commit opened it
    opened_by         VARCHAR(32)  NULL,     -- actor id of that import's operator
    -- Reserved for Docs/backlog/01: reopening restores a window, so it writes
    -- a new expiry here plus who did it and why. Columns exist now so that the
    -- utility is a write to an existing shape rather than another migration
    -- under time pressure on the day the window has already closed.
    reopened_at       DATETIME(3)  NULL,
    reopened_by       VARCHAR(64)  NULL,     -- operating OS user on the server console
    reopen_reason     VARCHAR(500) NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
