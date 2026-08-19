-- Docs/optimization/07-RoleFinetune.md §2.7: role templates are a light
-- addition on top of the existing role model — a template is just a `role`
-- row with is_template=TRUE, reusing role_permission for its grants, rather
-- than a parallel schema. A template is never assigned to a user and has no
-- meaningful code/session-timeout, but the columns are NOT NULL, so template
-- rows get harmless placeholder values; /role-templates never exposes either
-- field over the wire (RoleTemplateDto omits both).
ALTER TABLE role ADD COLUMN is_template BOOLEAN NOT NULL DEFAULT FALSE AFTER is_protected;

-- Seed a 'Teacher' template with the same grants as the live `teacher` role
-- at the time this migration was written — a one-time starting point for
-- creating new roles, not a live-synced copy (independently editable once
-- created, like any other template).
--
-- Same two-tier pattern as V20/V21: this migration's role_permission SELECT
-- is a no-op on a from-scratch schema (versioned migrations all run before
-- R__seed_permissions_and_roles.sql, so role_teacher's grants don't exist
-- yet when this runs there) — its real job is backfilling the *live*
-- schema, which already has role_teacher's rows. R__seed's own INSERT
-- IGNORE carries the same 21 grants directly for the fresh-install case.
INSERT IGNORE INTO role (id, code, name, session_timeout_minutes, is_protected, is_template, created_at, updated_at) VALUES
    ('role_template_teacher', 'template_teacher', 'Teacher', 480, FALSE, TRUE, NOW(3), NOW(3));

INSERT IGNORE INTO role_permission (role_id, permission_id)
SELECT 'role_template_teacher', permission_id FROM role_permission WHERE role_id = 'role_teacher';
