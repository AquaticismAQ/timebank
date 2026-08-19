-- Docs/optimization/07-RoleFinetune.md §2.2: which roles the new Roles-tab
-- delete endpoint must refuse to remove. Only student/admin/sysadmin are
-- protected — teacher is deliberately left FALSE (the column default), since
-- §2.2 dropped it from the protected list: it stays seeded (existing
-- teacher001-style accounts are unaffected) but becomes an ordinary,
-- eventually-deletable-once-unassigned role like any custom one.
ALTER TABLE role ADD COLUMN is_protected BOOLEAN NOT NULL DEFAULT FALSE;

-- Backfills the *live* schema's existing role rows (created back in B1.1,
-- long before this column existed). This UPDATE is a no-op on a from-scratch
-- schema — R__seed_permissions_and_roles.sql's INSERT runs after every
-- versioned migration including this one, so on a fresh install the role
-- table is still empty here; that repeatable migration sets is_protected
-- directly in its own INSERT for that case, not relying on this UPDATE.
UPDATE role SET is_protected = TRUE WHERE code IN ('student', 'admin', 'sysadmin');
