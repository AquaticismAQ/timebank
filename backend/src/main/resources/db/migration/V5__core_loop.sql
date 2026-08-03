-- B3: core loop — service records, review, ledger, progress, notifications
-- (Design.md §12 invariants 1-9, D12; Docs/implementation/05-Backend-B3-CoreLoop.md).
--
-- File/evidence storage (B3.4/B3.5) is a deliberate follow-up, not built
-- here — evidence_file_ids below is a plain JSON array of opaque strings
-- with no FK, since the `file` module does not exist yet.
--
-- None of these tables are partitioned: at the pilot scale this phase's own
-- measurement target names (~450k records over the programme), none
-- approaches the ~16M-row, ten-year volume that justified partitioning
-- audit_log/login_log in B0/B1. Revisit only if that assumption changes.

-- ---------------------------------------------------------------------------
-- servicerecord module
-- ---------------------------------------------------------------------------
CREATE TABLE service_record (
    id                      VARCHAR(32)  NOT NULL,
    student_id              VARCHAR(32)  NOT NULL,
    student_name            VARCHAR(100) NOT NULL, -- denormalized at creation (contract §2.11) — avoids N+1 on the list endpoint
    grade_id                VARCHAR(32)  NOT NULL, -- denormalized at creation, for data-scope queries (B1.7 wiring)
    class_id                VARCHAR(32)  NOT NULL,
    project_id              VARCHAR(32)  NULL,     -- sourceType=PROJECT only; project module doesn't exist until B4
    source_type             VARCHAR(16)  NOT NULL, -- PROJECT | EXTERNAL | SELF
    service_date            DATE         NOT NULL,
    start_at                DATETIME(3)  NOT NULL,
    end_at                  DATETIME(3)  NOT NULL,
    duration_minutes        INT          NOT NULL,
    location                VARCHAR(255) NULL,
    organization_id         VARCHAR(32)  NULL,
    organization_name       VARCHAR(200) NULL,     -- sourceType=EXTERNAL only
    service_category_id     VARCHAR(32)  NOT NULL,
    description             VARCHAR(2000) NULL,
    supervisor_name         VARCHAR(100) NULL,
    supervisor_contact      VARCHAR(100) NULL,
    evidence_file_ids       JSON         NULL,     -- array of opaque file ids; no FK (file module is a follow-up)
    includes_travel_time    TINYINT(1)   NOT NULL DEFAULT 0, -- Design.md Q5 disclosure flag
    status                  VARCHAR(16)  NOT NULL, -- DRAFT | SUBMITTED | UNDER_REVIEW | APPROVED | RETURNED | REJECTED | APPEAL_PENDING | CORRECTED
    current_revision        INT          NOT NULL DEFAULT 1,
    assigned_reviewer_id    VARCHAR(32)  NULL,
    has_anomaly             TINYINT(1)   NOT NULL DEFAULT 0,
    anomaly_note            VARCHAR(512) NULL,
    review_summary          VARCHAR(512) NULL,
    credited_units          INT          NULL,     -- centi-coins, set on approval only
    account_transaction_id  VARCHAR(32)  NULL,
    version                 BIGINT       NOT NULL DEFAULT 1,
    created_at              DATETIME(3)  NOT NULL,
    submitted_at             DATETIME(3)  NULL,
    updated_at               DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_service_record_student (student_id),
    KEY idx_service_record_grade_class (grade_id, class_id),
    KEY idx_service_record_status (status),
    KEY idx_service_record_reviewer (assigned_reviewer_id),
    CONSTRAINT fk_service_record_student FOREIGN KEY (student_id) REFERENCES student (id),
    CONSTRAINT fk_service_record_category FOREIGN KEY (service_category_id) REFERENCES service_category (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Append-only: a business-facing "what changed and when" timeline,
-- distinct from audit_log (security audit — Design.md §13).
CREATE TABLE service_record_revision (
    id                  VARCHAR(32)  NOT NULL,
    service_record_id   VARCHAR(32)  NOT NULL,
    revision_number     INT          NOT NULL,
    snapshot_json       JSON         NOT NULL, -- field values before this edit was applied
    edited_by           VARCHAR(32)  NOT NULL,
    created_at          DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_service_record_revision_record (service_record_id),
    CONSTRAINT fk_service_record_revision_record FOREIGN KEY (service_record_id) REFERENCES service_record (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- review module — append-only decision events (invariant 3)
-- ---------------------------------------------------------------------------
CREATE TABLE review_decision (
    id                          VARCHAR(32)  NOT NULL,
    service_record_id           VARCHAR(32)  NOT NULL,
    reviewer_id                 VARCHAR(32)  NOT NULL,
    decision                    VARCHAR(24)  NOT NULL, -- APPROVE | RETURN_FOR_CHANGES | REJECT | ESCALATE
    reason_code                 VARCHAR(64)  NOT NULL,
    comment                     VARCHAR(1000) NULL,
    record_version_at_decision  BIGINT       NOT NULL,
    created_at                  DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_review_decision_record (service_record_id),
    CONSTRAINT fk_review_decision_record FOREIGN KEY (service_record_id) REFERENCES service_record (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- account module — stored balance + immutable ledger (D12, invariants 1-2)
-- ---------------------------------------------------------------------------
CREATE TABLE account (
    id          VARCHAR(32)  NOT NULL,
    student_id  VARCHAR(32)  NOT NULL,
    grade_id    VARCHAR(32)  NOT NULL, -- denormalized, same rationale as service_record
    class_id    VARCHAR(32)  NOT NULL,
    balance     INT          NOT NULL DEFAULT 0, -- centi-coins; the stored cache D12 requires — never written directly, only via account_transaction
    frozen      TINYINT(1)   NOT NULL DEFAULT 0,
    frozen_reason VARCHAR(255) NULL,
    frozen_at   DATETIME(3)  NULL,
    version     BIGINT       NOT NULL DEFAULT 1,
    created_at  DATETIME(3)  NOT NULL,
    updated_at  DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_account_student (student_id),
    CONSTRAINT fk_account_student FOREIGN KEY (student_id) REFERENCES student (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Append-only (invariant 2) — no update/delete method exists at the
-- repository layer, mirroring audit_log's structural enforcement.
-- REDEMPTION/BORROW/REPAYMENT/BONUS are Phase-4-reserved values the `type`
-- enum already carries (contract §3.7) even though nothing writes them yet
-- — same extension-seam convention as feature_flag.
CREATE TABLE account_transaction (
    id                          VARCHAR(32)  NOT NULL,
    account_id                  VARCHAR(32)  NOT NULL,
    type                        VARCHAR(16)  NOT NULL, -- SERVICE_CREDIT | CORRECTION | FREEZE | UNFREEZE | REDEMPTION | BORROW | REPAYMENT | BONUS
    amount                      INT          NOT NULL, -- signed centi-coins; 0 for freeze/unfreeze markers
    balance_after               INT          NOT NULL,
    source_type                 VARCHAR(32)  NULL,
    source_id                   VARCHAR(32)  NULL,
    reason_code                 VARCHAR(64)  NOT NULL,
    operator_id                 VARCHAR(32)  NOT NULL,
    reversal_of_transaction_id  VARCHAR(32)  NULL,
    created_at                  DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_account_transaction_account (account_id, created_at),
    KEY idx_account_transaction_source (source_type, source_id),
    CONSTRAINT fk_account_transaction_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT fk_account_transaction_reversal FOREIGN KEY (reversal_of_transaction_id) REFERENCES account_transaction (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- progress module — stored *lifetime* projection, updated only from inside
-- the approval transaction (invariant 8: each contribution already
-- reflects the rule in effect at its own service date, so this table never
-- re-derives). "Stage" (the multi-year enrollment period) is this lifetime
-- total; the annual figure is bounded to one academic year's records and
-- computed live by `servicerecord` on request rather than kept as a second
-- stateful counter — live-summing one year of one student's records is not
-- the "full history on page load" cost B3.11 warns against.
-- ---------------------------------------------------------------------------
CREATE TABLE student_progress (
    student_id                   VARCHAR(32)  NOT NULL,
    total_credited_minutes       INT          NOT NULL DEFAULT 0,
    total_credited_centi_coins   INT          NOT NULL DEFAULT 0,
    honour_level                 VARCHAR(16)  NULL,
    version                      BIGINT       NOT NULL DEFAULT 1,
    updated_at                   DATETIME(3)  NOT NULL,
    PRIMARY KEY (student_id),
    CONSTRAINT fk_student_progress_student FOREIGN KEY (student_id) REFERENCES student (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- notification module — B2 built templates only ("the notification row
-- itself... is B5's"); the approval transaction (invariant 5) needs
-- somewhere to write its fifth effect, so the row is added now, minimally.
-- No list/read/mark-read endpoints yet — those stay B5's.
-- ---------------------------------------------------------------------------
CREATE TABLE notification (
    id                     VARCHAR(32)  NOT NULL,
    recipient_user_id      VARCHAR(32)  NOT NULL,
    template_code          VARCHAR(64)  NOT NULL,
    title                  VARCHAR(200) NOT NULL,
    body                   VARCHAR(1000) NOT NULL,
    related_resource_type  VARCHAR(64)  NULL,
    related_resource_id    VARCHAR(32)  NULL,
    is_read                TINYINT(1)   NOT NULL DEFAULT 0,
    created_at             DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_notification_recipient (recipient_user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
