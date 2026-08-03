-- B5 Pass 1: appeals/supplements/manual corrections (the contract's Case),
-- the notification read path's covering index, system settings, and
-- announcements (Docs/implementation/07-Backend-B5-AdminSurface.md).

-- ---------------------------------------------------------------------------
-- caseflow module
-- ---------------------------------------------------------------------------
CREATE TABLE case_record (
    id                              VARCHAR(32)   NOT NULL,
    type                            VARCHAR(20)   NOT NULL, -- APPEAL | SUPPLEMENT | MANUAL_CORRECTION
    student_id                      VARCHAR(32)   NOT NULL,
    grade_id                        VARCHAR(32)   NOT NULL, -- denormalized at creation, for teacher data-scope
    class_id                        VARCHAR(32)   NOT NULL,
    subject_type                    VARCHAR(32)   NULL, -- e.g. serviceRecord | accountTransaction | project
    subject_id                      VARCHAR(32)   NULL,
    title                           VARCHAR(200)  NOT NULL,
    description                     VARCHAR(4000) NULL,
    requested_amount_centi_coins    INT           NULL, -- manualCorrection only; signed
    reason_code                     VARCHAR(64)   NOT NULL,
    status                          VARCHAR(20)   NOT NULL, -- DRAFT | SUBMITTED | IN_REVIEW | NEEDS_INFORMATION | RESOLVED | REJECTED
    assigned_reviewer_id            VARCHAR(32)   NULL,
    version                         BIGINT        NOT NULL DEFAULT 1,
    created_at                      DATETIME(3)   NOT NULL,
    submitted_at                    DATETIME(3)   NULL,
    updated_at                      DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_case_student (student_id),
    KEY idx_case_status (status),
    KEY idx_case_grade_class (grade_id, class_id),
    CONSTRAINT fk_case_student FOREIGN KEY (student_id) REFERENCES student (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Append-only business timeline, distinct from audit_log (Design.md §13).
CREATE TABLE case_revision (
    id                VARCHAR(32)  NOT NULL,
    case_id           VARCHAR(32)  NOT NULL,
    revision_number   INT          NOT NULL,
    snapshot_json     JSON         NOT NULL,
    edited_by         VARCHAR(32)  NOT NULL,
    created_at        DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_case_revision_case (case_id),
    CONSTRAINT fk_case_revision_case FOREIGN KEY (case_id) REFERENCES case_record (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Append-only (invariant 3's same discipline applied to case decisions).
CREATE TABLE case_decision (
    id                      VARCHAR(32)   NOT NULL,
    case_id                 VARCHAR(32)   NOT NULL,
    reviewer_id             VARCHAR(32)   NOT NULL,
    decision                VARCHAR(24)   NOT NULL, -- RESOLVE | REQUEST_INFORMATION | REJECT
    reason_code             VARCHAR(64)   NOT NULL,
    comment                 VARCHAR(1000) NULL,
    case_version_at_decision BIGINT       NOT NULL,
    created_at              DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_case_decision_case (case_id),
    CONSTRAINT fk_case_decision_case FOREIGN KEY (case_id) REFERENCES case_record (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- notification read path (B5.4/B5.5)
-- ---------------------------------------------------------------------------
-- Covering index for the polled unread-count query (Design.md §16.1's
-- explicit requirement for this exact endpoint) — a single index-only scan.
ALTER TABLE notification ADD KEY idx_notification_recipient_unread (recipient_user_id, is_read);

CREATE TABLE announcement (
    id          VARCHAR(32)   NOT NULL,
    title_zh    VARCHAR(200)  NOT NULL,
    title_en    VARCHAR(200)  NOT NULL,
    body_zh     VARCHAR(2000) NOT NULL,
    body_en     VARCHAR(2000) NOT NULL,
    active      TINYINT(1)    NOT NULL DEFAULT 1,
    starts_at   DATETIME(3)   NULL,
    ends_at     DATETIME(3)   NULL,
    created_at  DATETIME(3)   NOT NULL,
    updated_at  DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_announcement_active (active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- system settings (B5.12) — a single fixed row, not a generic key-value
-- store: exactly one setting exists today (the notification poll interval).
-- ---------------------------------------------------------------------------
CREATE TABLE system_settings (
    id                                  VARCHAR(32) NOT NULL,
    notification_poll_interval_seconds INT         NOT NULL DEFAULT 30,
    version                             BIGINT      NOT NULL DEFAULT 1,
    updated_at                          DATETIME(3) NOT NULL,
    updated_by                          VARCHAR(32) NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
