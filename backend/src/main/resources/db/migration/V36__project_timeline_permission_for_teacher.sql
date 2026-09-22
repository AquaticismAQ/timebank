-- The project timeline becomes readable by a teacher
-- (Design/*/11-RolesAndPermissions.md §3.2, §8).
--
-- `projects.audit.read` gates `GET /projects/{id}/timeline` — who created,
-- edited, published, registered for, cancelled or archived a project, and
-- when. A teacher creates and publishes projects and confirms their
-- registrants, so "what has happened to this project" is a question about
-- their own work. The data scope still decides *which* projects: an
-- out-of-scope id 404s exactly as it does for the project detail.
--
-- It is a read of the audit trail, not of the audit *log screen*: that stays
-- `auditLogs.read`, which a teacher does not hold. The distinction is the
-- one this permission was always for — one resource's history, versus the
-- whole system's.
--
-- Ordering, the trap V20/V21/V22/V31 each document: Flyway runs every
-- versioned migration before any repeatable one, so on a fresh schema the
-- `role` rows do not exist yet when this runs. The grant is therefore a
-- SELECT over `role`, yielding zero rows on a fresh install (where the seed
-- grants it directly) and doing the real work on the live schema.

INSERT IGNORE INTO role_permission (role_id, permission_id)
SELECT r.id, 'perm_projects_audit_read'
FROM role r
WHERE r.code IN ('teacher', 'template_teacher');
