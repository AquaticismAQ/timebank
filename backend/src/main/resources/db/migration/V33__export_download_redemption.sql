-- Phase 5 egress hygiene, F-10 (Docs/optimization/11-DataImportExportOptimization.md
-- §2.11): the export download token becomes single-use.
--
-- A stateless HMAC cannot express "used once" — the second presentation of a
-- token is byte-identical to the first, so the only thing that can tell them
-- apart is a row written by the first. This table is that row, and nothing
-- else: it is not a download log (the audit log is), and it holds no state the
-- token itself does not already carry.
--
-- `token_hash`, not the token: the token is a bearer credential valid for 300
-- seconds, so a table of verbatim tokens would be a list of live download
-- credentials — readable by anyone who can read the database, which is a wider
-- set than the people allowed to export. A SHA-256 is all an exact-match
-- "have I seen this one?" needs.
--
-- The primary key is what enforces single-use: redemption is an INSERT, and two
-- concurrent replays race on the unique index inside InnoDB rather than on
-- anything in application code. `redeemed_at` exists to be swept (rows outlive
-- their token's usefulness by minutes, not months) and to make the table
-- readable when someone asks what happened.
--
-- No foreign key to `job`: these rows are bookkeeping about a credential, not
-- children of the job, and they are swept on age alone. A constraint would tie
-- a 300-second fact to the job row's own much longer lifecycle for no
-- integrity gain — nothing reads `job_id` here to join on.
CREATE TABLE export_download_redemption (
    token_hash  CHAR(64)    NOT NULL, -- SHA-256 of the presented token, hex
    job_id      VARCHAR(32) NOT NULL, -- the export job the spent token addressed
    redeemed_at DATETIME(3) NOT NULL,
    PRIMARY KEY (token_hash),
    KEY idx_export_download_redemption_redeemed_at (redeemed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
