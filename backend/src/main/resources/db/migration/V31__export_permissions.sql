-- Design/english/11-RolesAndPermissions.md §5 and §6: one permission per
-- export type instead of a single blanket `exports.create`, and all three
-- `imports.*` back to SDC Admin alone. Build plan:
-- Docs/optimization/11-DataImportExportOptimization.md §3.1.
--
-- Why this file exists at all, rather than only an edit to
-- R__seed_permissions_and_roles.sql (§2.1's both-halves rule, R-1): that
-- seed is INSERT IGNORE throughout, so *removing* a line from it revokes
-- nothing on a schema that already holds the row. Edit only the seed and
-- the live schema keeps every old grant while a from-scratch schema — every
-- timebank_it_* test schema — never had them, the two silently disagree,
-- and `mvn verify` passes either way because it only ever sees a fresh one.
-- The revokes below are the half only a versioned migration can do; the
-- seed carries the inserts for a from-scratch install.
--
-- Ordering, same trap V20/V21/V22 each document: Flyway runs every
-- versioned migration before any repeatable one, so on a fresh schema the
-- `role` rows that R__seed inserts do not exist yet when this runs. Every
-- grant below is therefore written as a SELECT over `role`, which yields
-- zero rows on a fresh install (the seed grants them directly there) and
-- does the real work on the live schema.

-- `permission.id` is 'perm_' + the code with dots as underscores — the
-- convention all ~50 existing rows follow. The longest new code,
-- exports.studentParticipation, makes that id 33 characters against a
-- VARCHAR(32) column, while the `code` it mirrors is VARCHAR(64): the id
-- column being the narrower of the two is the actual defect, and the next
-- long permission code would hit it again. Widening needs the FK dropped
-- and re-added — MySQL refuses to MODIFY a column an FK points at (error
-- 1833) — and re-adding the constraint re-validates every existing row,
-- which is why it is done this way rather than under FOREIGN_KEY_CHECKS=0.
ALTER TABLE role_permission DROP FOREIGN KEY fk_role_permission_permission;
ALTER TABLE permission MODIFY id VARCHAR(64) NOT NULL;
ALTER TABLE role_permission MODIFY permission_id VARCHAR(64) NOT NULL;
ALTER TABLE role_permission ADD CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES permission (id);

-- §5.3's seven types. Inserted granted to nobody — §9.4: "a new permission
-- is granted to no role by default, then assigned deliberately"; the grants
-- follow as their own statements below. Codes use each export type's stable
-- key, never its display title (§5.2), because the code is what an audit
-- entry stores and titles are i18n copy.
INSERT IGNORE INTO permission (id, code, description) VALUES
    ('perm_exports_studentList', 'exports.studentList', 'Export the Student List report'),
    ('perm_exports_serviceRecords', 'exports.serviceRecords', 'Export the Service Records report'),
    ('perm_exports_studentCompletion', 'exports.studentCompletion', 'Export the Student Completion report'),
    ('perm_exports_studentParticipation', 'exports.studentParticipation', 'Export the Student Participation report'),
    ('perm_exports_projects', 'exports.projects', 'Export the Projects report'),
    ('perm_exports_reviewHistory', 'exports.reviewHistory', 'Export the Review History report'),
    ('perm_exports_reviewEfficiency', 'exports.reviewEfficiency', 'Export the Review Efficiency report');

-- SDC Admin holds all seven (§5.3).
INSERT IGNORE INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r
JOIN permission p ON p.code IN ('exports.studentList', 'exports.serviceRecords', 'exports.studentCompletion',
                                'exports.studentParticipation', 'exports.projects', 'exports.reviewHistory',
                                'exports.reviewEfficiency')
WHERE r.code = 'admin';

-- Teacher holds six. `exports.reviewEfficiency` is withheld deliberately
-- (§5.3): every row of that report is a colleague's review volume, average
-- turnaround and approval rate — reasonable in a management context, not
-- between peers. `exports.reviewHistory` *is* granted, because the Reviewer
-- filter being pinned to the teacher themselves is a scope decision, not a
-- permission one (§5.3, §7).
INSERT IGNORE INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r
JOIN permission p ON p.code IN ('exports.studentList', 'exports.serviceRecords', 'exports.studentCompletion',
                                'exports.studentParticipation', 'exports.projects', 'exports.reviewHistory')
WHERE r.code = 'teacher';

-- The 'Teacher' role template (V22) gets the same six: a template that does
-- not match the role it templates is a trap for whoever creates the next
-- teacher role from it (§2.2). Unlike the two statements above, this row
-- does exist on a fresh schema (V22 inserts it unconditionally), so this
-- one does real work in both places and R__seed's copy is then a no-op.
INSERT IGNORE INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r
JOIN permission p ON p.code IN ('exports.studentList', 'exports.serviceRecords', 'exports.studentCompletion',
                                'exports.studentParticipation', 'exports.projects', 'exports.reviewHistory')
WHERE r.code = 'template_teacher';

-- `exports.create` retires by losing its grants, never its row (§5.2, §2.1)
-- — a permission code is a string in historical audit entries and must keep
-- meaning what it meant; same treatment as `projects.close`.
--
-- Enumerated rather than blind, per R-1. These two are the only holders the
-- seeded catalogue ever created (R__seed granted it to role_admin and
-- role_sysadmin and to no other role — in particular never to a teacher),
-- so this statement should report exactly two rows on the live schema.
DELETE rp FROM role_permission rp
JOIN role r ON r.id = rp.role_id
WHERE rp.permission_id = 'perm_exports_create'
  AND r.code IN ('admin', 'sysadmin');

-- Any remaining holder is a role a school added itself — the role system
-- exists precisely so it can. Revoked too, deliberately: from here on
-- nothing checks `exports.create`, so leaving the grant would keep offering
-- an inert permission in Role Management's editor, which is worse than one
-- that is gone (§2.1). Such a school must re-grant whichever of the seven
-- §5.3 permissions it actually meant. This statement's row count is how
-- many custom roles were affected, and is the number worth reading off the
-- migration output — unlike the statement above, it is not predictable
-- from the seed.
DELETE rp FROM role_permission rp
WHERE rp.permission_id = 'perm_exports_create';

-- Note for the next reader, so nothing is gone looking for: the two export
-- *types* that also retire in this pass, `participation` and `serviceHours`
-- (13-DataExport.md §6), have no permission rows to retire. There was only
-- ever `exports.create` for all seven built types, so the row below is the
-- whole of "their permission rows are kept and marked retired" (§2.4).
UPDATE permission
SET description = 'Retired (11-RolesAndPermissions.md §5.1): superseded by one exports.* permission per export type, grants revoked in V31. Row kept, never deleted and never reused — the code is a string in historical audit entries.'
WHERE id = 'perm_exports_create';

-- §6: all three `imports.*` return to SDC Admin alone, reversing V21's
-- admin/sysadmin merge for this group only (§6.1 — a bulk student-data
-- import that lands on the time-coin ledger is a Student Development Centre
-- judgement, not an operations task). Unlike the `exports.create` revoke
-- above this is a live capability removal: sysadmin accounts can import
-- today and will not be able to afterwards (R-7). Sysadmin is granted none
-- of the seven new export permissions either, which is why there is no
-- statement to revoke them.
DELETE rp FROM role_permission rp
JOIN role r ON r.id = rp.role_id
WHERE r.code = 'sysadmin'
  AND rp.permission_id IN ('perm_imports_create', 'perm_imports_read', 'perm_imports_commit');
