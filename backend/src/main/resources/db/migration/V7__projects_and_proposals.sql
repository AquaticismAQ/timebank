-- B4: projects, registrations (the contract's Application, internal module
-- `enrollment`), and student event proposals (Design.md §12 invariant 6,
-- Docs/implementation/06-Backend-B4-Projects.md).

-- ---------------------------------------------------------------------------
-- project module
-- ---------------------------------------------------------------------------
CREATE TABLE project (
    id                       VARCHAR(32)   NOT NULL,
    title                    VARCHAR(200)  NOT NULL,
    summary                  VARCHAR(500)  NULL,
    description              VARCHAR(4000) NULL,
    category_id              VARCHAR(32)   NOT NULL,
    organization_id          VARCHAR(32)   NULL,
    owner_id                 VARCHAR(32)   NOT NULL,
    origin_proposal_id       VARCHAR(32)   NULL, -- backfilled only for proposal-generated projects
    start_at                 DATETIME(3)   NOT NULL,
    end_at                   DATETIME(3)   NOT NULL,
    location                 VARCHAR(255)  NULL,
    capacity                 INT           NOT NULL,
    recognized_minutes       INT           NOT NULL,
    eligible_grade_ids       JSON          NULL,
    evidence_requirements    VARCHAR(1000) NULL,
    registration_open_at     DATETIME(3)   NOT NULL,
    registration_close_at    DATETIME(3)   NOT NULL,
    status                   VARCHAR(20)   NOT NULL, -- DRAFT | PUBLISHED | REGISTRATION_OPEN | IN_PROGRESS | COMPLETED | CLOSED | ARCHIVED
    version                  BIGINT        NOT NULL DEFAULT 1,
    created_at               DATETIME(3)   NOT NULL,
    updated_at               DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_project_status (status),
    KEY idx_project_organization (organization_id),
    KEY idx_project_owner (owner_id),
    CONSTRAINT fk_project_category FOREIGN KEY (category_id) REFERENCES service_category (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- studentOrganizerIds — populated at proposal-generation time from the
-- proposer + co-organizers; a plain join table since a project can have more
-- than one.
CREATE TABLE project_student_organizer (
    project_id  VARCHAR(32) NOT NULL,
    student_id  VARCHAR(32) NOT NULL,
    PRIMARY KEY (project_id, student_id),
    CONSTRAINT fk_project_organizer_project FOREIGN KEY (project_id) REFERENCES project (id),
    CONSTRAINT fk_project_organizer_student FOREIGN KEY (student_id) REFERENCES student (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Append-only business timeline (B4.5) — distinct from audit_log (Design.md §13).
CREATE TABLE project_revision (
    id                VARCHAR(32)  NOT NULL,
    project_id        VARCHAR(32)  NOT NULL,
    revision_number   INT          NOT NULL,
    snapshot_json     JSON         NOT NULL,
    edited_by         VARCHAR(32)  NOT NULL,
    created_at        DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_project_revision_project (project_id),
    CONSTRAINT fk_project_revision_project FOREIGN KEY (project_id) REFERENCES project (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- enrollment module (contract's Application; REST path stays /applications)
-- ---------------------------------------------------------------------------
CREATE TABLE enrollment (
    id             VARCHAR(32)  NOT NULL,
    project_id     VARCHAR(32)  NOT NULL,
    student_id     VARCHAR(32)  NOT NULL,
    status         VARCHAR(16)  NOT NULL, -- PENDING | CONFIRMED | WAITLISTED | REJECTED | COMPLETED | CANCELLED
    note           VARCHAR(500) NULL,
    applied_at     DATETIME(3)  NOT NULL,
    confirmed_at   DATETIME(3)  NULL,
    completed_at   DATETIME(3)  NULL,
    cancelled_at   DATETIME(3)  NULL,
    version        BIGINT       NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_enrollment_project_student (project_id, student_id),
    KEY idx_enrollment_project_status (project_id, status),
    KEY idx_enrollment_student (student_id),
    CONSTRAINT fk_enrollment_project FOREIGN KEY (project_id) REFERENCES project (id),
    CONSTRAINT fk_enrollment_student FOREIGN KEY (student_id) REFERENCES student (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- proposal module — field set per APIContract.md §3.2 (the frontend mock's
-- EventProposal type diverges heavily and has zero real write call sites;
-- the contract is authoritative here, same resolution as divergence #1).
-- ---------------------------------------------------------------------------
CREATE TABLE event_proposal (
    id                          VARCHAR(32)   NOT NULL,
    proposer_student_id         VARCHAR(32)   NOT NULL,
    grade_id                    VARCHAR(32)   NOT NULL, -- denormalized at creation (the proposer's grade/class), for teacher data-scope on the approval queue
    class_id                    VARCHAR(32)   NOT NULL,
    co_organizer_student_ids    JSON          NULL,
    title                       VARCHAR(200)  NOT NULL,
    objective                   VARCHAR(1000) NULL,
    description                 VARCHAR(4000) NULL,
    expected_outcome            VARCHAR(1000) NULL,
    category_id                 VARCHAR(32)   NOT NULL,
    planned_start_at            DATETIME(3)   NOT NULL,
    planned_end_at              DATETIME(3)   NOT NULL,
    location                    VARCHAR(255)  NULL,
    format                      VARCHAR(32)   NULL,
    target_participants         VARCHAR(500)  NULL,
    capacity                    INT           NOT NULL,
    volunteer_tasks             VARCHAR(1000) NULL,
    proposed_supervisor_id      VARCHAR(32)   NOT NULL,
    organization_id             VARCHAR(32)   NULL,
    external_contact            VARCHAR(255)  NULL,
    safety_plan                 VARCHAR(2000) NULL,
    resource_needs              VARCHAR(1000) NULL,
    recruitment_plan            VARCHAR(1000) NULL,
    proposed_recognized_minutes INT           NOT NULL,
    evidence_plan                VARCHAR(1000) NULL,
    status                      VARCHAR(20)   NOT NULL, -- DRAFT | SUBMITTED | UNDER_REVIEW | CHANGES_REQUESTED | APPROVED | REJECTED
    current_revision            INT           NOT NULL DEFAULT 1,
    assigned_reviewer_id        VARCHAR(32)   NULL,
    approved_project_id         VARCHAR(32)   NULL,
    version                     BIGINT        NOT NULL DEFAULT 1,
    created_at                  DATETIME(3)   NOT NULL,
    submitted_at                 DATETIME(3)   NULL,
    updated_at                   DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_event_proposal_proposer (proposer_student_id),
    KEY idx_event_proposal_status (status),
    KEY idx_event_proposal_reviewer (assigned_reviewer_id),
    CONSTRAINT fk_event_proposal_proposer FOREIGN KEY (proposer_student_id) REFERENCES student (id),
    CONSTRAINT fk_event_proposal_category FOREIGN KEY (category_id) REFERENCES service_category (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE event_proposal_revision (
    id                  VARCHAR(32)  NOT NULL,
    event_proposal_id   VARCHAR(32)  NOT NULL,
    revision_number     INT          NOT NULL,
    snapshot_json       JSON         NOT NULL,
    edited_by           VARCHAR(32)  NOT NULL,
    created_at          DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_event_proposal_revision_proposal (event_proposal_id),
    CONSTRAINT fk_event_proposal_revision_proposal FOREIGN KEY (event_proposal_id) REFERENCES event_proposal (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Append-only (invariant 3's same discipline applied to proposal decisions).
CREATE TABLE event_proposal_review (
    id                          VARCHAR(32)   NOT NULL,
    event_proposal_id           VARCHAR(32)   NOT NULL,
    reviewer_id                 VARCHAR(32)   NOT NULL,
    decision                    VARCHAR(24)   NOT NULL, -- REQUEST_CHANGES | RECOMMEND_APPROVAL | APPROVE | REJECT
    reason_code                 VARCHAR(64)   NOT NULL,
    comment                     VARCHAR(1000) NULL,
    proposal_version_at_decision BIGINT       NOT NULL,
    created_at                  DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_event_proposal_review_proposal (event_proposal_id),
    CONSTRAINT fk_event_proposal_review_proposal FOREIGN KEY (event_proposal_id) REFERENCES event_proposal (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
