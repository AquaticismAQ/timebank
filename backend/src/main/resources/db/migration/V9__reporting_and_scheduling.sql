-- B5 Pass 2: dashboards, reports, materialized summaries (Design.md §16.2,
-- D5's "first scheduled job", Docs/implementation/07-Backend-B5-AdminSurface.md).

-- ---------------------------------------------------------------------------
-- ShedLock's own table (net.javacrumbs.shedlock) — the distributed-lock
-- mechanism named but deliberately unbuilt since B0 (see pom.xml). Schema is
-- fixed by the library itself, not our own convention.
-- ---------------------------------------------------------------------------
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at  TIMESTAMP(3) NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- reporting module — materialized per-grade summaries (B5.8). Report figures
-- for participation/completion are the two Design.md §16.2 names explicitly
-- as "expensive and frequently viewed"; both are upserted by the scheduled
-- job in one grade-scoped row each, keyed by (grade_id, academic_year_id) so
-- a school keeps every year's history rather than overwriting it.
-- ---------------------------------------------------------------------------
CREATE TABLE grade_participation_summary (
    grade_id           VARCHAR(32)  NOT NULL,
    academic_year_id   VARCHAR(32)  NOT NULL,
    total_students      INT          NOT NULL,
    participated_students INT        NOT NULL,
    generated_at        DATETIME(3)  NOT NULL,
    PRIMARY KEY (grade_id, academic_year_id),
    CONSTRAINT fk_participation_summary_grade FOREIGN KEY (grade_id) REFERENCES grade (id),
    CONSTRAINT fk_participation_summary_year FOREIGN KEY (academic_year_id) REFERENCES academic_year (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE grade_compliance_summary (
    grade_id           VARCHAR(32)  NOT NULL,
    academic_year_id   VARCHAR(32)  NOT NULL,
    total_students      INT          NOT NULL,
    completed_students   INT          NOT NULL,
    required_minutes     INT          NOT NULL, -- the rule threshold in effect when this row was generated
    generated_at        DATETIME(3)  NOT NULL,
    PRIMARY KEY (grade_id, academic_year_id),
    CONSTRAINT fk_compliance_summary_grade FOREIGN KEY (grade_id) REFERENCES grade (id),
    CONSTRAINT fk_compliance_summary_year FOREIGN KEY (academic_year_id) REFERENCES academic_year (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Composite indexes the report/summary queries actually filter and group by
-- (Design.md §16.1) — service_record already has idx_service_record_status
-- and idx_service_record_grade_class individually, neither of which covers
-- "status + a service_date range" or "status + grade" together.
ALTER TABLE service_record ADD INDEX idx_service_record_status_date (status, service_date);
ALTER TABLE service_record ADD INDEX idx_service_record_grade_status (grade_id, status);
ALTER TABLE review_decision ADD INDEX idx_review_decision_created_at (created_at);
