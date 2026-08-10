-- Docs/clarification/03-ServiceRecordFormOptimization.md §5/§10: new service_record
-- fields for the PROJECT/SELF source split. All nullable — existing records (incl.
-- historical EXTERNAL-sourced ones, which keep their original fields untouched per
-- §7.3) simply have no recordNumber/travelTimeReason/supervisor* snapshot; new
-- records always get one via ServiceRecordServiceImpl.
ALTER TABLE service_record
    ADD COLUMN record_number VARCHAR(64) NULL,
    ADD COLUMN travel_time_reason VARCHAR(500) NULL,
    ADD COLUMN supervisor_kind VARCHAR(20) NULL, -- PROJECT_OWNER | INTERNAL_USER | STUDENT | EXTERNAL_MANUAL
    ADD COLUMN supervisor_subject_id VARCHAR(32) NULL,
    ADD COLUMN project_organizations_snapshot JSON NULL;

-- NULL-safe unique index: MySQL allows unlimited NULLs through a UNIQUE index, so
-- historical records without a number don't collide with each other or with future
-- numbered ones.
ALTER TABLE service_record ADD UNIQUE KEY uk_service_record_number (record_number);

-- §10 D3: one row per (academic_year, student), incremented atomically via
-- `INSERT ... ON DUPLICATE KEY UPDATE seq = LAST_INSERT_ID(seq + 1)` — no separate
-- locked read, no explicit transaction-held lock (RecordNumberGenerator).
CREATE TABLE record_number_seq (
    academic_year_id  VARCHAR(32) NOT NULL,
    student_id        VARCHAR(32) NOT NULL,
    seq               INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (academic_year_id, student_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
