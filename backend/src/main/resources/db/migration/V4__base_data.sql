-- B2: base data, rules, and localization (Design.md §9, §12 invariants 7-8, D8).

-- ---------------------------------------------------------------------------
-- student module: academic structure + students
-- ---------------------------------------------------------------------------
CREATE TABLE academic_year (
    id          VARCHAR(32) NOT NULL,
    code        VARCHAR(16) NOT NULL, -- e.g. "2025-2026"
    start_date  DATE        NOT NULL,
    end_date    DATE        NOT NULL,
    is_current  TINYINT(1)  NOT NULL DEFAULT 0,
    created_at  DATETIME(3) NOT NULL,
    updated_at  DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_academic_year_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- A static dictionary (grade_6..grade_11), not year-scoped — per-language
-- names live here so localization is a data property, not a view concern
-- (Design.md §9, B2's own goal statement).
CREATE TABLE grade (
    id        VARCHAR(32)  NOT NULL,
    code      VARCHAR(32)  NOT NULL,
    name_zh   VARCHAR(100) NOT NULL,
    name_en   VARCHAR(100) NOT NULL,
    sequence  INT          NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_grade_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Classes belong to one grade within one academic year — a new "10A" roster
-- each year, not a single evergreen row (matters for scope assignments and
-- for the cohort-scoped purge, B6.3).
CREATE TABLE class (
    id                VARCHAR(32)  NOT NULL,
    code              VARCHAR(32)  NOT NULL,
    name_zh           VARCHAR(100) NOT NULL,
    name_en           VARCHAR(100) NOT NULL,
    grade_id          VARCHAR(32)  NOT NULL,
    academic_year_id  VARCHAR(32)  NOT NULL,
    created_at        DATETIME(3)  NOT NULL,
    updated_at        DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_class_code_year (code, academic_year_id),
    KEY idx_class_grade (grade_id),
    CONSTRAINT fk_class_grade FOREIGN KEY (grade_id) REFERENCES grade (id),
    CONSTRAINT fk_class_academic_year FOREIGN KEY (academic_year_id) REFERENCES academic_year (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- student_number is a plain UNIQUE index, not a cohort-compound key: the
-- number space becomes reusable naturally once B6.3's purge physically
-- deletes a graduated cohort's rows, which is what D8's "not assumed
-- globally unique forever" guardrail actually asks for (Design.md D8).
--
-- guardian_name/guardian_contact exist here as fields only (Q4: no guardian
-- accounts in Phase 2) and are excluded from list responses at the DTO
-- layer, never at the schema layer (contract §9).
--
-- graduation_year is the purge key B6.3 depends on — present from this
-- first migration, never retrofitted.
CREATE TABLE student (
    id                            VARCHAR(32)  NOT NULL,
    student_number                VARCHAR(32)  NOT NULL,
    display_name                  VARCHAR(100) NOT NULL,
    email                         VARCHAR(255) NULL,
    grade_id                      VARCHAR(32)  NOT NULL,
    class_id                      VARCHAR(32)  NOT NULL,
    enrollment_academic_year_id   VARCHAR(32)  NOT NULL,
    graduation_year               INT          NOT NULL,
    status                        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    guardian_name                 VARCHAR(100) NULL,
    guardian_contact               VARCHAR(64)  NULL,
    joined_at                     DATE         NOT NULL,
    version                       BIGINT       NOT NULL DEFAULT 1,
    created_at                    DATETIME(3)  NOT NULL,
    updated_at                    DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_student_number (student_number),
    KEY idx_student_grade_class (grade_id, class_id),
    KEY idx_student_graduation_year (graduation_year),
    CONSTRAINT fk_student_grade FOREIGN KEY (grade_id) REFERENCES grade (id),
    CONSTRAINT fk_student_class FOREIGN KEY (class_id) REFERENCES class (id),
    CONSTRAINT fk_student_academic_year FOREIGN KEY (enrollment_academic_year_id) REFERENCES academic_year (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- organization module: partner organizations, service categories,
-- school info, feature flags (grouped here per Docs/implementation/00-Status.md
-- §13's module-placement note — school info/feature flags don't map to any
-- single Design.md §7 module, and are closest in spirit to "organization")
-- ---------------------------------------------------------------------------
CREATE TABLE organization (
    id             VARCHAR(32)  NOT NULL,
    name           VARCHAR(200) NOT NULL,
    type           VARCHAR(16)  NOT NULL,
    contact_name   VARCHAR(100) NULL,
    contact_email  VARCHAR(255) NULL,
    contact_phone  VARCHAR(64)  NULL,
    address        VARCHAR(255) NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    version        BIGINT       NOT NULL DEFAULT 1,
    created_at     DATETIME(3)  NOT NULL,
    updated_at     DATETIME(3)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE service_category (
    id          VARCHAR(32)  NOT NULL,
    code        VARCHAR(32)  NOT NULL,
    name_zh     VARCHAR(100) NOT NULL,
    name_en     VARCHAR(100) NOT NULL,
    active      TINYINT(1)   NOT NULL DEFAULT 1,
    created_at  DATETIME(3)  NOT NULL,
    updated_at  DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_service_category_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Single row (D8: single-tenant per deployment) — school-specific
-- configuration lives in data, never in deployment properties (D8 guardrail 2).
--
-- current_academic_year_code is a denormalized string, not a foreign key to
-- academic_year.id: `organization` (this table) and `student` (owns
-- academic_year) must not depend on each other in either direction —
-- `student`'s controllers already depend on `organization.SchoolInfoService`
-- for the localization default-language fallback (B2.5), so the reverse
-- dependency this FK would require creates a module cycle. Matches the
-- same "display value travels with the reference" pattern as contract §2.11.
CREATE TABLE school_info (
    id                            VARCHAR(32)  NOT NULL,
    name                          VARCHAR(200) NOT NULL,
    full_name                     VARCHAR(255) NULL,
    student_dev_center            VARCHAR(200) NULL,
    contact                       VARCHAR(255) NULL,
    default_language              VARCHAR(8)   NOT NULL DEFAULT 'zh-CN',
    current_academic_year_code    VARCHAR(16)  NULL,
    updated_at                    DATETIME(3)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Phase 3/4 capability switches (Design.md §14) — flags only, nothing they
-- guard is built. Mutations against a disabled feature's endpoints are
-- rejected at the API boundary once those endpoints exist (not yet).
CREATE TABLE feature_flag (
    code        VARCHAR(32) NOT NULL,
    enabled     TINYINT(1)  NOT NULL DEFAULT 0,
    updated_at  DATETIME(3) NOT NULL,
    PRIMARY KEY (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- rules module — versioned, immutable once published (invariant 7).
-- One row bundles every parameter a school publishes together as one
-- consistent rule set, matching how the frontend mock's StudentProgress
-- shape treats them as a single coherent whole.
-- ---------------------------------------------------------------------------
CREATE TABLE rule_version (
    id                        VARCHAR(32)  NOT NULL,
    status                    VARCHAR(16)  NOT NULL, -- DRAFT | PUBLISHED | ARCHIVED
    rounding_minutes          INT          NOT NULL, -- 30 (0.5h granularity, §22.1)
    minutes_per_coin_unit     INT          NOT NULL, -- 60
    centi_coins_per_unit      INT          NOT NULL, -- 100 (1 coin = 100 centi-coins)
    annual_required_minutes  INT          NOT NULL,
    stage_required_minutes   INT          NOT NULL,
    honour_bronze_minutes    INT          NOT NULL,
    honour_silver_minutes    INT          NOT NULL,
    honour_gold_minutes      INT          NOT NULL,
    honour_excellent_minutes INT          NOT NULL,
    effective_from            DATETIME(3)  NULL,
    effective_to               DATETIME(3)  NULL,
    version                   BIGINT       NOT NULL DEFAULT 1,
    created_at                DATETIME(3)  NOT NULL,
    updated_at                DATETIME(3)  NOT NULL,
    published_at               DATETIME(3)  NULL,
    created_by                 VARCHAR(32)  NULL,
    PRIMARY KEY (id),
    KEY idx_rule_version_effective (status, effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- notification module — templates only in B2; the send/list endpoints and
-- the notification row itself are B5's (Docs/implementation/07-Backend-B5-AdminSurface.md).
-- Built now because B3's approval transaction needs somewhere to render
-- its notification from (Design.md §12 invariant 5).
-- ---------------------------------------------------------------------------
CREATE TABLE notification_template (
    id          VARCHAR(32)   NOT NULL,
    code        VARCHAR(64)   NOT NULL,
    title_zh    VARCHAR(200)  NOT NULL,
    title_en    VARCHAR(200)  NOT NULL,
    body_zh     VARCHAR(1000) NOT NULL,
    body_en     VARCHAR(1000) NOT NULL,
    created_at  DATETIME(3)   NOT NULL,
    updated_at  DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_template_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
