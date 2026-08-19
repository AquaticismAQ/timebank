-- Docs/optimization/07-RoleFinetune.md §2.3: make admin and sysadmin
-- functionally identical, permission-wise — union both roles' current
-- grants and assign the union to both. Written as a role-code/permission-id
-- join rather than hardcoded permission ids, so it doesn't need to be kept
-- in sync with R__seed_permissions_and_roles.sql's literal id values.
--
-- No explicit exclusion list needed: admin's existing grants already omit
-- the three student-submission permissions (eventProposals.create,
-- applications.create, serviceRecords.create) and the dead projects.close,
-- so the union naturally keeps both roles clear of them too.
--
-- This is a no-op on a from-scratch schema: as a versioned migration it
-- runs before R__seed_permissions_and_roles.sql (Flyway always runs every
-- versioned migration before any repeatable one), so role_permission is
-- still empty when this SELECT runs there. That repeatable migration's own
-- INSERT IGNORE role_permission block carries the same union directly for
-- that case; this migration's real job is backfilling the *live* schema's
-- existing role_permission rows, which predate the merge decision.

-- admin gains everything sysadmin already has (roles.manage, settings.manage,
-- securityLogs.read, lifecycle.*, plus the read-only breadth sysadmin held).
INSERT IGNORE INTO role_permission (role_id, permission_id)
SELECT admin.id, sysadmin_rp.permission_id
FROM role admin
JOIN role sysadmin ON sysadmin.code = 'sysadmin'
JOIN role_permission sysadmin_rp ON sysadmin_rp.role_id = sysadmin.id
WHERE admin.code = 'admin';

-- sysadmin gains everything admin already has (every create/update/publish/
-- decide/manage operational permission it previously lacked).
INSERT IGNORE INTO role_permission (role_id, permission_id)
SELECT sysadmin.id, admin_rp.permission_id
FROM role sysadmin
JOIN role admin ON admin.code = 'admin'
JOIN role_permission admin_rp ON admin_rp.role_id = admin.id
WHERE sysadmin.code = 'sysadmin';
