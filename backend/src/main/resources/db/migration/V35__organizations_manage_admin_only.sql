-- Partner organisations become admin-editable only
-- (Design/*/11-RolesAndPermissions.md §3.2, Ryan 2026-09-22).
--
-- `organizations.manage` was granted to the teacher role on 2026-08-08,
-- widening it from admin-only. This reverses that: a teacher keeps
-- `organizations.read` — they pick a host organisation when creating a
-- project and must be able to see the list — but creating, renaming and
-- archiving the school's partner organisations is a Student Development
-- Centre responsibility, the same line §3.2 already draws for imports.
--
-- A partner organisation is shared vocabulary: every project and every
-- service record points at one, and renaming or archiving one changes what
-- other people's records say about themselves. That is the property that
-- makes it not a year-group action.
--
-- Why a versioned migration and not only the seed edit (§2.1's both-halves
-- rule): every statement in R__seed_permissions_and_roles.sql is INSERT
-- IGNORE, so *deleting* a line there revokes nothing on a schema that
-- already holds the row. Only this file can take the grant away from the
-- live schema; the seed edit is what keeps every from-scratch schema —
-- including each timebank_it_* test schema — in step.
--
-- This is a live capability removal, not a no-op: teacher accounts can edit
-- organisations today and will not be able to afterwards. The UI already
-- gates its create/edit/archive controls on this permission
-- (`OrganizationManagement.tsx`), so the screen becomes read-only for them
-- rather than offering buttons that 403.

DELETE rp FROM role_permission rp
JOIN role r ON r.id = rp.role_id
WHERE r.code IN ('teacher', 'template_teacher')
  AND rp.permission_id = 'perm_organizations_manage';
