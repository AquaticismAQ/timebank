-- Docs/clarification/03-ServiceRecordFormOptimization.md §4.2/§10 D2/D5: a project
-- can have exactly one HOST_SCHOOL organization (always the singleton school org from
-- V17) plus zero or more PARTNER organizations, replacing the single
-- `project.organization_id` as the source of truth for organization associations.
-- `project.organization_id` itself is kept (not dropped) — the existing API contract
-- still treats it as "the project's partner organization" for backward compatibility;
-- it is no longer read as "the" organization anywhere new code is written.
--
-- Same generated-column-unique-index technique as V17's school-organization
-- invariant (D2's decision, for consistency): `host_marker` is `project_id` only
-- when `role = 'HOST_SCHOOL'`, so at most one HOST_SCHOOL row can exist per project,
-- DB-enforced rather than application-checked.
CREATE TABLE project_organization (
    id               VARCHAR(32) NOT NULL,
    project_id       VARCHAR(32) NOT NULL,
    organization_id  VARCHAR(32) NOT NULL,
    role             VARCHAR(16) NOT NULL, -- HOST_SCHOOL | PARTNER
    sort_order       INT         NOT NULL DEFAULT 0,
    host_marker      VARCHAR(32) GENERATED ALWAYS AS (CASE WHEN role = 'HOST_SCHOOL' THEN project_id ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_project_organization_host (host_marker),
    UNIQUE KEY uk_project_organization_role (project_id, organization_id, role),
    KEY idx_project_organization_org (organization_id),
    CONSTRAINT fk_project_organization_project FOREIGN KEY (project_id) REFERENCES project (id),
    CONSTRAINT fk_project_organization_org FOREIGN KEY (organization_id) REFERENCES organization (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- §4.2: ON_CAMPUS | OFF_CAMPUS | HYBRID, never inferred from organization type. The
-- DEFAULT here is what backfills every project that pre-dates this migration (D5:
-- "unknown/needs manual review", not a guess at on- or off-campus) — new projects
-- must supply it explicitly going forward (ProjectServiceImpl.createDraft requires
-- it; the column default only ever applies to rows this ALTER TABLE touches directly).
ALTER TABLE project ADD COLUMN delivery_scope VARCHAR(16) NOT NULL DEFAULT 'HYBRID';
