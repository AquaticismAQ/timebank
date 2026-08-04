-- B5 Pass 3: exports, imports, the async job/executor/checkpoint model (D5,
-- Docs/implementation/07-Backend-B5-AdminSurface.md B5.9-B5.11).

CREATE TABLE job (
    id                  VARCHAR(32)  NOT NULL,
    kind                VARCHAR(16)  NOT NULL, -- EXPORT | IMPORT
    resource_type       VARCHAR(64)  NOT NULL, -- e.g. 'participationReport', 'serviceRecordsHistorical'
    status              VARCHAR(24)  NOT NULL, -- vocabulary differs by kind — see Job's own Javadoc
    created_by          VARCHAR(32)  NOT NULL,
    params_json         VARCHAR(2000) NULL,
    total_rows          INT          NULL,
    valid_rows          INT          NULL,
    processed_rows      INT          NOT NULL DEFAULT 0,
    checkpoint_offset   INT          NOT NULL DEFAULT 0,
    result_ref          VARCHAR(255) NULL,     -- storage key of the export output / import error report
    error_ref           VARCHAR(255) NULL,
    error_message       VARCHAR(1000) NULL,
    source_file_key     VARCHAR(255) NULL,     -- import only: the uploaded source file's storage key
    source_file_hash    VARCHAR(64)  NULL,     -- import only: sha-256 of the source file, for dedup (contract §4.14)
    claimed_by          VARCHAR(64)  NULL,
    claimed_at          DATETIME(3)  NULL,
    lease_expires_at    DATETIME(3)  NULL,
    version             BIGINT       NOT NULL DEFAULT 1,
    created_at          DATETIME(3)  NOT NULL,
    updated_at          DATETIME(3)  NOT NULL,
    started_at          DATETIME(3)  NULL,
    finished_at         DATETIME(3)  NULL,
    PRIMARY KEY (id),
    KEY idx_job_created_by (created_by),
    KEY idx_job_status_lease (status, lease_expires_at),
    KEY idx_job_source_hash (source_file_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
