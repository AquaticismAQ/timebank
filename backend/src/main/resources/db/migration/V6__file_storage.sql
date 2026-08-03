-- B3.4/B3.5: evidence file metadata (D9). Bytes live outside MySQL — on
-- local disk for this test-stage pass (`FileStorage`'s `LocalFileStorage`
-- adapter, 14-Backend-B3F-CloudFileStorage.md tracks the move to a real
-- S3-compatible store) — this table is metadata only, never the bytes
-- themselves (Design.md D9's BLOB option is disqualifying regardless of
-- which adapter is behind the port).
CREATE TABLE evidence_file (
    id                    VARCHAR(32)  NOT NULL,
    uploader_id           VARCHAR(32)  NOT NULL, -- the principal (user) id, not studentId
    purpose               VARCHAR(32)  NOT NULL,
    original_filename     VARCHAR(255) NOT NULL,
    declared_mime_type    VARCHAR(100) NOT NULL,
    declared_size_bytes   BIGINT       NOT NULL,
    actual_size_bytes     BIGINT       NULL,
    sha256_hash           VARCHAR(64)  NULL,
    status                VARCHAR(16)  NOT NULL, -- PENDING | PROCESSING | READY | REJECTED
    rejection_reason      VARCHAR(255) NULL,
    storage_key           VARCHAR(255) NOT NULL,
    created_at            DATETIME(3)  NOT NULL,
    updated_at            DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_evidence_file_uploader (uploader_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
