-- Service categories get permissions of their own (issue 157,
-- Design/*/11-RolesAndPermissions.md §5a).
--
-- They had none. The three write endpoints borrowed `rules.manage`, whose
-- own description admitted it: "Create or edit rule drafts and service
-- categories". A rule version is a versioned, immutable-once-published
-- policy object whose change recalculates every student's compliance; a
-- service category is a word on a dropdown. Sharing one permission means a
-- school cannot let someone tidy the category list without also handing
-- them the rules that decide who graduates — the same too-much-or-nothing
-- grain §5.1 rejected for exports.
--
-- Why this file and not only the seed edit (§2.1's both-halves rule):
-- inserts alone would be fine through R__seed_permissions_and_roles.sql,
-- since every statement there is INSERT IGNORE and it re-runs on the live
-- schema when its checksum changes. The `UPDATE` at the bottom is the half
-- only a versioned migration can do — INSERT IGNORE cannot correct an
-- existing row's description, so without this the live catalogue would keep
-- describing `rules.manage` as covering service categories while every
-- fresh schema described it correctly.
--
-- Ordering, the trap V20/V21/V22/V31 each document: Flyway runs every
-- versioned migration before any repeatable one, so on a fresh schema the
-- `role` rows do not exist yet when this runs. The grants are therefore
-- SELECTs over `role`, yielding zero rows on a fresh install (where the
-- seed grants them directly) and doing the real work on the live schema.

-- Inserted granted to nobody; the grants follow as their own statements
-- (§9.4 — a new permission is granted to no role by default, then assigned
-- deliberately).
INSERT IGNORE INTO permission (id, code, description) VALUES
    ('perm_serviceCategories_read', 'serviceCategories.read', 'View the service category list, including retired categories'),
    ('perm_serviceCategories_manage', 'serviceCategories.manage', 'Create, rename, retire or delete a service category');

-- Read: teacher, SDC admin, sysadmin. A teacher chooses categories all day
-- when reviewing records, and seeing the whole vocabulary — including what
-- has been retired, and so why an old record names something no longer
-- offered — is reading the school's own dictionary, not an administrative
-- power.
--
-- Not the student. The picker on their forms is served by the *ungated*
-- `GET /service-categories`, which returns selectable categories only and
-- stays `isAuthenticated()`: a permission every single role must hold never
-- denies anything, and forgetting to grant it to one role would silently
-- break that role's forms.
INSERT IGNORE INTO role_permission (role_id, permission_id)
SELECT r.id, 'perm_serviceCategories_read'
FROM role r
WHERE r.code IN ('teacher', 'admin', 'sysadmin');

-- Manage: SDC admin and sysadmin — exactly who held `rules.manage` before,
-- so no account gains or loses the ability to edit categories today. The
-- point of this migration is that the two can now move apart.
INSERT IGNORE INTO role_permission (role_id, permission_id)
SELECT r.id, 'perm_serviceCategories_manage'
FROM role r
WHERE r.code IN ('admin', 'sysadmin');

-- The 'Teacher' role template (V22) tracks the teacher role: a template
-- that does not match the role it templates is a trap for whoever creates
-- the next teacher role from it (§2.2). Unlike the statements above this
-- row does exist on a fresh schema, so it does real work in both places.
INSERT IGNORE INTO role_permission (role_id, permission_id)
SELECT r.id, 'perm_serviceCategories_read'
FROM role r
WHERE r.code = 'template_teacher';

-- `rules.manage` keeps its code and its rule-drafting meaning — a
-- permission code is a string in historical audit entries and must go on
-- meaning what it meant (§9.3). Only its description narrows, now that the
-- second half of it has a home.
UPDATE permission
SET description = 'Create or edit rule drafts'
WHERE id = 'perm_rules_manage';
