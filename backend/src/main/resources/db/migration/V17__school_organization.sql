-- Docs/clarification/03-ServiceRecordFormOptimization.md §4.1/§10 D1: exactly one
-- Organization row is the school itself — not user-creatable/-archivable via the
-- normal organization CRUD, and PROJECT-sourced service records resolve their
-- host organization from it rather than trusting a client-supplied id.
--
-- Enforced with a generated column + unique index rather than an application-layer
-- check-then-insert (D1's decision): `school_org_marker` is 1 only when the flag is
-- true and NULL otherwise: MySQL unique indexes treat NULLs as distinct, so this
-- allows unlimited non-school rows but rejects a second `is_school_organization=1`
-- row outright, race-safe under concurrent inserts.
--
-- DDL only — the actual singleton row is seeded in `R__seed_base_data.sql`, not
-- here. Flyway runs all versioned migrations (this one included) before any
-- repeatable migration, and `school_info` (this row's data source) is only ever
-- seeded by the repeatable `R__seed_base_data.sql` — a data-seeding statement here
-- would run against an empty `school_info` table on a fresh schema and silently
-- insert nothing.
ALTER TABLE organization ADD COLUMN is_school_organization TINYINT(1) NOT NULL DEFAULT 0;
ALTER TABLE organization ADD COLUMN school_org_marker TINYINT(1)
    GENERATED ALWAYS AS (CASE WHEN is_school_organization = 1 THEN 1 ELSE NULL END) STORED;
ALTER TABLE organization ADD UNIQUE KEY uk_organization_school_marker (school_org_marker);
