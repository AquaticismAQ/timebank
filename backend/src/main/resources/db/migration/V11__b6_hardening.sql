-- B6: data lifecycle, reconciliation, hardening (Design.md §16.3, D12,
-- Docs/implementation/08-Backend-B6-Hardening.md).

-- ---------------------------------------------------------------------------
-- lifecycle module — cohort purge audit trail (B6.3/B6.4). One row per run
-- (dry-run or real), never updated once finished — the compliance record
-- PIPL requires ("which cohort, when, how many rows, under which policy
-- version" per Design.md §16.3 point 3).
-- ---------------------------------------------------------------------------
CREATE TABLE purge_run (
    id                  VARCHAR(32)  NOT NULL,
    graduation_year     INT          NOT NULL,
    dry_run             TINYINT(1)   NOT NULL,
    status              VARCHAR(16)  NOT NULL, -- RUNNING | SUCCEEDED | FAILED
    policy_version      VARCHAR(32)  NOT NULL, -- names the retention rule version this run applied (Design.md §16.3)
    students_scanned    INT          NOT NULL DEFAULT 0,
    students_purged     INT          NOT NULL DEFAULT 0,
    rows_deleted        INT          NOT NULL DEFAULT 0,
    objects_deleted     INT          NOT NULL DEFAULT 0,
    triggered_by        VARCHAR(32)  NOT NULL, -- actor id, or 'scheduler' for the automated run
    error_message       VARCHAR(1000) NULL,
    started_at          DATETIME(3)  NOT NULL,
    finished_at         DATETIME(3)  NULL,
    PRIMARY KEY (id),
    KEY idx_purge_run_started (started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- ledger reconciliation (B6.7, D12) — a nightly checkpointed pass plus a
-- weekly full re-sum; both write here so "did reconciliation run last
-- night" is itself queryable (feeds the non-execution alert, same pattern
-- as purge_run).
-- ---------------------------------------------------------------------------
CREATE TABLE reconciliation_run (
    id                  VARCHAR(32)  NOT NULL,
    mode                VARCHAR(16)  NOT NULL, -- CHECKPOINT | FULL
    status              VARCHAR(16)  NOT NULL, -- RUNNING | SUCCEEDED | FAILED
    accounts_checked    INT          NOT NULL DEFAULT 0,
    mismatches_found    INT          NOT NULL DEFAULT 0,
    checkpoint_before   DATETIME(3)  NULL,
    checkpoint_after    DATETIME(3)  NULL,
    error_message       VARCHAR(1000) NULL,
    started_at          DATETIME(3)  NOT NULL,
    finished_at         DATETIME(3)  NULL,
    PRIMARY KEY (id),
    KEY idx_reconciliation_run_started (started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Append-only: one row per account found to have drifted, naming exactly
-- what the exit criterion asks for ("names the account and divergence
-- point"). Never updated — a recurring mismatch on the same account
-- produces a new row each run, preserving history.
CREATE TABLE reconciliation_mismatch (
    id                      VARCHAR(32)  NOT NULL,
    reconciliation_run_id   VARCHAR(32)  NOT NULL,
    account_id              VARCHAR(32)  NOT NULL,
    stored_balance          INT          NOT NULL,
    computed_balance        INT          NOT NULL,
    divergent_transaction_id VARCHAR(32) NULL, -- the first transaction (by created_at) after which the running sum no longer matches, when determinable
    created_at              DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_reconciliation_mismatch_account (account_id),
    CONSTRAINT fk_reconciliation_mismatch_run FOREIGN KEY (reconciliation_run_id) REFERENCES reconciliation_run (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Single-row watermark table (id is always 'default') — the "since the last
-- checkpoint" cursor B6.7's nightly pass advances. A real table, not an
-- in-memory field, so the watermark survives a restart (D5's own reasoning
-- for the job table, applied here at a much smaller scale).
CREATE TABLE reconciliation_checkpoint (
    id                VARCHAR(16)  NOT NULL,
    last_checked_at   DATETIME(3)  NOT NULL,
    updated_at        DATETIME(3)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO reconciliation_checkpoint (id, last_checked_at, updated_at)
VALUES ('default', '2000-01-01 00:00:00.000', NOW(3));

-- ---------------------------------------------------------------------------
-- B6.9 index review: a handful of gaps found by EXPLAIN against the
-- currently-seeded dataset. Recorded per-index, matching the migration's
-- own convention of naming which phase/finding each index answers.
-- ---------------------------------------------------------------------------
ALTER TABLE account_transaction ADD INDEX idx_account_transaction_created_at (created_at);
ALTER TABLE enrollment ADD INDEX idx_enrollment_student_status (student_id, status);

-- The review queue (B3.6) filters `status IN (SUBMITTED, UNDER_REVIEW)` and
-- orders by `submitted_at asc` — `idx_service_record_status` alone lets
-- MySQL use the index for the filter but still requires a separate
-- filesort for the order-by. Design.md §16.1 named this exact composite
-- (status, submitted_at) up front; it was never actually added when B3.6
-- was built. Confirmed with EXPLAIN before and after (00-Status.md §19).
ALTER TABLE service_record ADD INDEX idx_service_record_status_submitted (status, submitted_at);
