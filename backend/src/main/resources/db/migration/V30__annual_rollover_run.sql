-- Annual rollover (Design/*/13-AnnualRollover.md).
--
-- One row per attempt, not per academic year: a failed run must be
-- retryable, and keeping the failure visible is the point of having the
-- table at all. Idempotency is therefore "does a SUCCEEDED row exist for
-- this year", checked inside the run's own transaction while holding the
-- academic_year row — not a unique key, which would make a failed attempt
-- block every retry.
CREATE TABLE annual_rollover_run (
    id                    VARCHAR(32)   NOT NULL,
    academic_year_id      VARCHAR(32)   NOT NULL,
    status                VARCHAR(16)   NOT NULL, -- RUNNING | SUCCEEDED | FAILED | SKIPPED
    students_promoted     INT           NOT NULL DEFAULT 0,
    students_graduated    INT           NOT NULL DEFAULT 0,
    logins_deactivated    INT           NOT NULL DEFAULT 0,
    accounts_resynced     INT           NOT NULL DEFAULT 0, -- §3.4: the live grade snapshot follows the student
    classes_created       INT           NOT NULL DEFAULT 0,
    students_skipped      INT           NOT NULL DEFAULT 0, -- no class, or no matching class in the target year
    inconsistent_grades   INT           NOT NULL DEFAULT 0, -- §5's derived-vs-stored check
    triggered_by          VARCHAR(32)   NOT NULL, -- actor id, or 'scheduler'
    error_message         VARCHAR(1000) NULL,
    started_at            DATETIME(3)   NOT NULL,
    finished_at           DATETIME(3)   NULL,
    PRIMARY KEY (id),
    KEY idx_annual_rollover_year (academic_year_id, status),
    CONSTRAINT fk_annual_rollover_year FOREIGN KEY (academic_year_id) REFERENCES academic_year (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
