-- V1 baseline (B0.10): the cross-cutting tables every later module depends on.
-- ddl-auto=validate everywhere (D7) — Flyway is the only writer of schema, always.
-- utf8mb4_0900_ai_ci throughout: Chinese content requires utf8mb4 (Design.md §8).

-- ---------------------------------------------------------------------------
-- idempotency_record (B0.7, Design.md §11)
-- Short-lived operational rows; expiry sweep lands in B6.2. Deletable by the
-- application itself on a failed attempt, unlike audit_log.
-- ---------------------------------------------------------------------------
CREATE TABLE idempotency_record (
    id                   VARCHAR(32)  NOT NULL,
    idempotency_key      VARCHAR(128) NOT NULL,
    principal_id         VARCHAR(32)  NOT NULL,
    endpoint             VARCHAR(128) NOT NULL,
    request_fingerprint  VARCHAR(64)  NOT NULL,
    status               VARCHAR(16)  NOT NULL,
    response_status      INT          NULL,
    response_body        LONGTEXT     NULL,
    created_at           DATETIME(3)  NOT NULL,
    updated_at           DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_idempotency_natural_key (idempotency_key, principal_id, endpoint)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- audit_log (B0.8, Design.md §13, invariant 10 — append-only, never updated
-- or deleted; enforced structurally by AuditLogRepository exposing no such
-- method, not merely by convention).
--
-- Partitioned by month from the first migration (B0.10 note: "retrofitting
-- partitioning is expensive"), so the age-based pruning in B6.1 is a
-- DROP PARTITION, never a multi-million-row DELETE. The partitioning column
-- must be part of the primary key in MySQL, hence the composite key below —
-- `id` alone remains effectively unique in practice (OpaqueIds.generate).
--
-- Only ~12 months of partitions are pre-created; a scheduled job (B6.1) adds
-- new monthly partitions and drops expired ones. Rows past the last named
-- partition still land safely in p_future in the meantime.
-- ---------------------------------------------------------------------------
CREATE TABLE audit_log (
    id                VARCHAR(32)  NOT NULL,
    actor_id          VARCHAR(32)  NULL,
    permission_used   VARCHAR(64)  NULL,
    action            VARCHAR(64)  NOT NULL,
    resource_type     VARCHAR(64)  NOT NULL,
    resource_id       VARCHAR(32)  NULL,
    before_summary    JSON         NULL,
    after_summary     JSON         NULL,
    reason_code       VARCHAR(64)  NULL,
    request_id        VARCHAR(32)  NULL,
    ip_address        VARCHAR(64)  NULL,
    user_agent        VARCHAR(256) NULL,
    created_at        DATETIME(3)  NOT NULL,
    PRIMARY KEY (id, created_at),
    KEY idx_audit_resource (resource_type, resource_id),
    KEY idx_audit_actor_created (actor_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
PARTITION BY RANGE (TO_DAYS(created_at)) (
    PARTITION p_2026_08 VALUES LESS THAN (TO_DAYS('2026-09-01')),
    PARTITION p_2026_09 VALUES LESS THAN (TO_DAYS('2026-10-01')),
    PARTITION p_2026_10 VALUES LESS THAN (TO_DAYS('2026-11-01')),
    PARTITION p_2026_11 VALUES LESS THAN (TO_DAYS('2026-12-01')),
    PARTITION p_2026_12 VALUES LESS THAN (TO_DAYS('2027-01-01')),
    PARTITION p_2027_01 VALUES LESS THAN (TO_DAYS('2027-02-01')),
    PARTITION p_2027_02 VALUES LESS THAN (TO_DAYS('2027-03-01')),
    PARTITION p_2027_03 VALUES LESS THAN (TO_DAYS('2027-04-01')),
    PARTITION p_2027_04 VALUES LESS THAN (TO_DAYS('2027-05-01')),
    PARTITION p_2027_05 VALUES LESS THAN (TO_DAYS('2027-06-01')),
    PARTITION p_2027_06 VALUES LESS THAN (TO_DAYS('2027-07-01')),
    PARTITION p_2027_07 VALUES LESS THAN (TO_DAYS('2027-08-01')),
    PARTITION p_future VALUES LESS THAN MAXVALUE
);
