-- Permission catalog and default roles (B1.1). Reference/config data
-- per D7 — repeatable so future permission additions ship as a diff to
-- this file, applied idempotently via INSERT IGNORE. Roles are real rows
-- (never a Java enum) precisely so a school can add or adjust them without
-- a code change (Design.md §5 watch-out). Grants below are the contract
-- §5 permission matrix's defaults, not a fixed policy.
--
-- This file only ever adds. Because every statement is INSERT IGNORE,
-- *deleting* a line here revokes nothing on a schema that already holds the
-- row — it only stops a from-scratch schema acquiring it, so the live
-- server and every fresh timebank_it_* test schema quietly diverge and
-- `mvn verify` passes either way. A removal therefore always needs both
-- halves: the DELETE in a versioned migration, and the line dropped here.
-- V31__export_permissions.sql is the worked example.

INSERT IGNORE INTO permission (id, code, description) VALUES
    ('perm_projects_read', 'projects.read', 'View projects within visible scope'),
    ('perm_projects_create', 'projects.create', 'Create project drafts'),
    ('perm_projects_update', 'projects.update', 'Edit projects'),
    ('perm_projects_publish', 'projects.publish', 'Publish a project'),
    ('perm_projects_close', 'projects.close', 'Close a project early (retired 02-PorjectOrganization.md §2 — CLOSED is system-only now; kept as a harmless unused row rather than deleted)'),
    ('perm_projects_archive', 'projects.archive', 'Archive a completed project once every confirmed student''s record is reviewed'),
    ('perm_projects_cancel', 'projects.cancel', 'Cancel and archive a project outright, with a reason, bypassing the reviewed-records gate'),
    ('perm_projects_audit_read', 'projects.audit.read', 'View project change timeline'),
    ('perm_eventProposals_read', 'eventProposals.read', 'View event proposals within scope'),
    ('perm_eventProposals_create', 'eventProposals.create', 'Submit an event proposal'),
    ('perm_eventProposals_review', 'eventProposals.review', 'Review/approve/reject event proposals'),
    ('perm_eventProposals_assign', 'eventProposals.assign', 'Assign or transfer a proposal reviewer'),
    ('perm_applications_read', 'applications.read', 'View registrations within scope'),
    ('perm_applications_create', 'applications.create', 'Register for a project'),
    ('perm_applications_manage', 'applications.manage', 'Confirm, reject, or bulk-manage registrations'),
    ('perm_serviceRecords_read', 'serviceRecords.read', 'View service records within scope'),
    ('perm_serviceRecords_create', 'serviceRecords.create', 'Create and submit a service record'),
    ('perm_reviews_read', 'reviews.read', 'View the review queue'),
    ('perm_reviews_decide', 'reviews.decide', 'Submit a review decision'),
    ('perm_reviews_bulkDecide', 'reviews.bulkDecide', 'Submit batch review decisions'),
    ('perm_reviews_assign', 'reviews.assign', 'Assign or transfer a record reviewer'),
    ('perm_cases_read', 'cases.read', 'View appeals/supplements within scope'),
    ('perm_cases_create', 'cases.create', 'Create an appeal or supplement'),
    ('perm_cases_decide', 'cases.decide', 'Decide an appeal or supplement'),
    ('perm_students_read', 'students.read', 'View student directory within scope'),
    ('perm_students_manage', 'students.manage', 'Create or update student records'),
    ('perm_progress_read', 'progress.read', 'View progress tracking'),
    ('perm_organizations_read', 'organizations.read', 'View partner organizations'),
    ('perm_organizations_manage', 'organizations.manage', 'Create or update partner organizations'),
    ('perm_accounts_read', 'accounts.read', 'View account balances and transactions'),
    ('perm_accounts_adjust', 'accounts.adjust', 'Create a manual account adjustment'),
    ('perm_accounts_reverse', 'accounts.reverse', 'Reverse an account transaction'),
    ('perm_accounts_freeze', 'accounts.freeze', 'Freeze or unfreeze an account'),
    ('perm_rules_read', 'rules.read', 'View rule versions'),
    -- Issue 157: "and service categories" moved out to the two rows below.
    -- The code is unchanged and never will be — it is a string in historical
    -- audit entries (§9.3); only the description narrows. V34 carries the
    -- same correction to the live row, which INSERT IGNORE cannot update.
    ('perm_rules_manage', 'rules.manage', 'Create or edit rule drafts'),
    -- 11-RolesAndPermissions.md §5a. `serviceCategories.read` gates the
    -- management view — the list *including retired* categories. The plain
    -- `GET /service-categories` behind every form's picker stays
    -- `isAuthenticated()`: a permission every role must hold never denies
    -- anything, and forgetting one role would silently break its forms.
    ('perm_serviceCategories_read', 'serviceCategories.read', 'View the service category list, including retired categories'),
    ('perm_serviceCategories_manage', 'serviceCategories.manage', 'Create, rename, retire or delete a service category'),
    ('perm_rules_publish', 'rules.publish', 'Publish a rule version'),
    ('perm_reports_read', 'reports.read', 'View dashboards and reports'),
    ('perm_exports_create', 'exports.create', 'Retired (11-RolesAndPermissions.md §5.1): superseded by one exports.* permission per export type, grants revoked in V31. Row kept, never deleted and never reused — the code is a string in historical audit entries.'),
    -- 11-RolesAndPermissions.md §5.3: one permission per export type. The
    -- id is 'perm_' + the code with dots as underscores, as everywhere else
    -- here, which needed V31 to widen permission.id — exports.studentParticipation
    -- makes a 33-character id and the column was VARCHAR(32).
    ('perm_exports_studentList', 'exports.studentList', 'Export the Student List report'),
    ('perm_exports_serviceRecords', 'exports.serviceRecords', 'Export the Service Records report'),
    ('perm_exports_studentCompletion', 'exports.studentCompletion', 'Export the Student Completion report'),
    ('perm_exports_studentParticipation', 'exports.studentParticipation', 'Export the Student Participation report'),
    ('perm_exports_projects', 'exports.projects', 'Export the Projects report'),
    ('perm_exports_reviewHistory', 'exports.reviewHistory', 'Export the Review History report'),
    ('perm_exports_reviewEfficiency', 'exports.reviewEfficiency', 'Export the Review Efficiency report'),
    ('perm_imports_create', 'imports.create', 'Create a data import job'),
    ('perm_imports_read', 'imports.read', 'View import job status and errors'),
    ('perm_imports_commit', 'imports.commit', 'Confirm and commit an import'),
    ('perm_users_read', 'users.read', 'View user accounts'),
    ('perm_users_manage', 'users.manage', 'Create, update, unlock, or reset users'),
    ('perm_roles_read', 'roles.read', 'View roles and the permission catalog'),
    ('perm_roles_manage', 'roles.manage', 'Create or update roles'),
    ('perm_auditLogs_read', 'auditLogs.read', 'View the operation audit log'),
    ('perm_securityLogs_read', 'securityLogs.read', 'View login logs'),
    ('perm_settings_read', 'settings.read', 'View system settings'),
    ('perm_settings_manage', 'settings.manage', 'Update system settings'),
    ('perm_lifecycle_purge', 'lifecycle.purge', 'Trigger a cohort retention purge (dry-run or real)'),
    ('perm_lifecycle_reconcile', 'lifecycle.reconcile', 'Trigger a ledger reconciliation run and view its results'),
    ('perm_directory_read', 'directory.read', 'Search a minimal id+displayName directory of students/teachers for picking a co-organizer or supervisor — deliberately narrower than students.read/users.read (no contact/role/status fields)');

-- Session timeout: 720 min (12h) for student, 480 min (8h) for staff roles,
-- matching contract §2.2's default — configurable per role from here on.
-- is_protected here (not left to V20__role_protected_flag.sql's UPDATE
-- alone) matters for a from-scratch schema: this repeatable migration
-- always runs *after* every versioned one (Flyway's fixed ordering), so on
-- a fresh install V20's UPDATE fires against an empty role table and is a
-- no-op — these rows wouldn't exist yet. V20's UPDATE still does the real
-- work for the live schema, which already had these rows before is_protected
-- existed; this INSERT is what makes a from-scratch install (every IT test
-- schema, or a hypothetical future fresh deploy) correct too.
INSERT IGNORE INTO role (id, code, name, session_timeout_minutes, is_protected, created_at, updated_at) VALUES
    ('role_student', 'student', 'Student', 720, TRUE, NOW(3), NOW(3)),
    ('role_teacher', 'teacher', 'Teacher', 480, FALSE, NOW(3), NOW(3)),
    ('role_admin', 'admin', 'SDC Admin', 480, TRUE, NOW(3), NOW(3)),
    ('role_sysadmin', 'sysadmin', 'System Admin', 480, TRUE, NOW(3), NOW(3));

INSERT IGNORE INTO role_permission (role_id, permission_id) VALUES
    ('role_student', 'perm_projects_read'),
    ('role_student', 'perm_eventProposals_create'),
    ('role_student', 'perm_eventProposals_read'),
    ('role_student', 'perm_applications_create'),
    ('role_student', 'perm_applications_read'),
    ('role_student', 'perm_serviceRecords_create'),
    ('role_student', 'perm_serviceRecords_read'),
    ('role_student', 'perm_cases_create'),
    ('role_student', 'perm_cases_read'),
    ('role_student', 'perm_accounts_read'),
    ('role_student', 'perm_progress_read'),
    ('role_student', 'perm_organizations_read'),
    ('role_student', 'perm_directory_read'),
    ('role_teacher', 'perm_projects_read'),
    ('role_teacher', 'perm_projects_create'),
    ('role_teacher', 'perm_projects_update'),
    ('role_teacher', 'perm_projects_publish'),
    ('role_teacher', 'perm_eventProposals_read'),
    ('role_teacher', 'perm_eventProposals_review'),
    ('role_teacher', 'perm_eventProposals_assign'),
    ('role_teacher', 'perm_applications_read'),
    ('role_teacher', 'perm_serviceRecords_read'),
    ('role_teacher', 'perm_reviews_read'),
    ('role_teacher', 'perm_reviews_decide'),
    ('role_teacher', 'perm_reviews_assign'),
    ('role_teacher', 'perm_cases_create'),
    ('role_teacher', 'perm_cases_read'),
    ('role_teacher', 'perm_students_read'),
    ('role_teacher', 'perm_progress_read'),
    ('role_teacher', 'perm_organizations_read'),
    ('role_teacher', 'perm_organizations_manage'),
    ('role_teacher', 'perm_accounts_read'),
    ('role_teacher', 'perm_rules_read'),
    ('role_teacher', 'perm_reports_read'),
    -- 11-RolesAndPermissions.md §5.3: six of the seven export types. The
    -- teacher grant is a new capability, not a narrowing of an old one —
    -- `exports.create` was never granted to a teacher. exports.reviewEfficiency
    -- is withheld: every row of it is a colleague's review volume and
    -- approval rate.
    ('role_teacher', 'perm_exports_studentList'),
    ('role_teacher', 'perm_exports_serviceRecords'),
    ('role_teacher', 'perm_exports_studentCompletion'),
    ('role_teacher', 'perm_exports_studentParticipation'),
    ('role_teacher', 'perm_exports_projects'),
    ('role_teacher', 'perm_exports_reviewHistory'),
    -- 11-RolesAndPermissions.md §5a: read, never manage. Seeing the whole
    -- vocabulary — including what has been retired, and so why an old record
    -- names something no longer offered — is reading the school's own
    -- dictionary, not an administrative power.
    ('role_teacher', 'perm_serviceCategories_read'),
    -- 07-RoleFinetune.md §2.3: admin and sysadmin hold an identical,
    -- unioned permission set — same reasoning as is_protected above, this
    -- repeatable migration is what makes a from-scratch schema correct
    -- immediately; V21__merge_admin_sysadmin_permissions.sql's INSERT
    -- IGNORE ... SELECT is what backfills the live schema's existing rows
    -- (which predate the merge decision).
    ('role_admin', 'perm_projects_read'),
    ('role_admin', 'perm_projects_create'),
    ('role_admin', 'perm_projects_update'),
    ('role_admin', 'perm_projects_publish'),
    ('role_admin', 'perm_projects_archive'),
    ('role_admin', 'perm_projects_cancel'),
    ('role_admin', 'perm_projects_audit_read'),
    ('role_admin', 'perm_eventProposals_read'),
    ('role_admin', 'perm_eventProposals_review'),
    ('role_admin', 'perm_eventProposals_assign'),
    ('role_admin', 'perm_applications_read'),
    ('role_admin', 'perm_applications_manage'),
    ('role_admin', 'perm_serviceRecords_read'),
    ('role_admin', 'perm_reviews_read'),
    ('role_admin', 'perm_reviews_decide'),
    ('role_admin', 'perm_reviews_bulkDecide'),
    ('role_admin', 'perm_reviews_assign'),
    ('role_admin', 'perm_cases_create'),
    ('role_admin', 'perm_cases_read'),
    ('role_admin', 'perm_cases_decide'),
    ('role_admin', 'perm_students_read'),
    ('role_admin', 'perm_students_manage'),
    ('role_admin', 'perm_progress_read'),
    ('role_admin', 'perm_organizations_read'),
    ('role_admin', 'perm_organizations_manage'),
    ('role_admin', 'perm_accounts_read'),
    ('role_admin', 'perm_accounts_adjust'),
    ('role_admin', 'perm_accounts_reverse'),
    ('role_admin', 'perm_accounts_freeze'),
    ('role_admin', 'perm_rules_read'),
    ('role_admin', 'perm_rules_manage'),
    ('role_admin', 'perm_rules_publish'),
    ('role_admin', 'perm_serviceCategories_read'),
    ('role_admin', 'perm_serviceCategories_manage'),
    ('role_admin', 'perm_reports_read'),
    -- 11-RolesAndPermissions.md §5.3: all seven export types. Deliberately
    -- *not* 'perm_exports_create' — that permission is retired (§5.1) and
    -- re-seeding its grant here would put it straight back on a fresh
    -- schema, which is the half of the change V31 cannot do on its own.
    ('role_admin', 'perm_exports_studentList'),
    ('role_admin', 'perm_exports_serviceRecords'),
    ('role_admin', 'perm_exports_studentCompletion'),
    ('role_admin', 'perm_exports_studentParticipation'),
    ('role_admin', 'perm_exports_projects'),
    ('role_admin', 'perm_exports_reviewHistory'),
    ('role_admin', 'perm_exports_reviewEfficiency'),
    ('role_admin', 'perm_imports_create'),
    ('role_admin', 'perm_imports_read'),
    ('role_admin', 'perm_imports_commit'),
    ('role_admin', 'perm_users_read'),
    ('role_admin', 'perm_users_manage'),
    ('role_admin', 'perm_roles_read'),
    ('role_admin', 'perm_roles_manage'),
    ('role_admin', 'perm_auditLogs_read'),
    ('role_admin', 'perm_securityLogs_read'),
    ('role_admin', 'perm_settings_read'),
    ('role_admin', 'perm_settings_manage'),
    ('role_admin', 'perm_lifecycle_purge'),
    ('role_admin', 'perm_lifecycle_reconcile'),
    ('role_sysadmin', 'perm_projects_read'),
    ('role_sysadmin', 'perm_projects_create'),
    ('role_sysadmin', 'perm_projects_update'),
    ('role_sysadmin', 'perm_projects_publish'),
    ('role_sysadmin', 'perm_projects_archive'),
    ('role_sysadmin', 'perm_projects_cancel'),
    ('role_sysadmin', 'perm_projects_audit_read'),
    ('role_sysadmin', 'perm_eventProposals_read'),
    ('role_sysadmin', 'perm_eventProposals_review'),
    ('role_sysadmin', 'perm_eventProposals_assign'),
    ('role_sysadmin', 'perm_applications_read'),
    ('role_sysadmin', 'perm_applications_manage'),
    ('role_sysadmin', 'perm_serviceRecords_read'),
    ('role_sysadmin', 'perm_reviews_read'),
    ('role_sysadmin', 'perm_reviews_decide'),
    ('role_sysadmin', 'perm_reviews_bulkDecide'),
    ('role_sysadmin', 'perm_reviews_assign'),
    ('role_sysadmin', 'perm_cases_create'),
    ('role_sysadmin', 'perm_cases_read'),
    ('role_sysadmin', 'perm_cases_decide'),
    ('role_sysadmin', 'perm_students_read'),
    ('role_sysadmin', 'perm_students_manage'),
    ('role_sysadmin', 'perm_progress_read'),
    ('role_sysadmin', 'perm_organizations_read'),
    ('role_sysadmin', 'perm_organizations_manage'),
    ('role_sysadmin', 'perm_accounts_read'),
    ('role_sysadmin', 'perm_accounts_adjust'),
    ('role_sysadmin', 'perm_accounts_reverse'),
    ('role_sysadmin', 'perm_accounts_freeze'),
    ('role_sysadmin', 'perm_rules_read'),
    ('role_sysadmin', 'perm_rules_manage'),
    ('role_sysadmin', 'perm_rules_publish'),
    ('role_sysadmin', 'perm_serviceCategories_read'),
    ('role_sysadmin', 'perm_serviceCategories_manage'),
    ('role_sysadmin', 'perm_reports_read'),
    -- 11-RolesAndPermissions.md §6.1 reverses V21's admin/sysadmin merge for
    -- import and export only: no exports.* and no imports.* line here on
    -- purpose. Export takes student data out in bulk and import writes it in
    -- bulk; a system administrator needs the system to run, not the roster
    -- in a file. V21's union INSERT is a no-op on a fresh schema, so this
    -- absence is what makes a from-scratch install agree with the live
    -- schema after V31's DELETEs. Everything else stays merged.
    ('role_sysadmin', 'perm_users_read'),
    ('role_sysadmin', 'perm_users_manage'),
    ('role_sysadmin', 'perm_roles_read'),
    ('role_sysadmin', 'perm_roles_manage'),
    ('role_sysadmin', 'perm_auditLogs_read'),
    ('role_sysadmin', 'perm_securityLogs_read'),
    ('role_sysadmin', 'perm_settings_read'),
    ('role_sysadmin', 'perm_settings_manage'),
    ('role_sysadmin', 'perm_lifecycle_purge'),
    ('role_sysadmin', 'perm_lifecycle_reconcile');

-- 07-RoleFinetune.md §2.7 / V22__role_templates.sql: fresh-schema copy of
-- the seeded 'Teacher' role template's grants — see V22's own comment for
-- why this duplication exists (repeatable migrations always run after
-- every versioned one, so V22's role_permission SELECT sees nothing yet
-- here). The `role_template_teacher` row itself isn't re-inserted here —
-- V22's own INSERT IGNORE already creates it unconditionally (it doesn't
-- depend on role_teacher's grants existing yet), so duplicating it here
-- would just be a same-row INSERT IGNORE no-op that logs a harmless but
-- noisy "Duplicate entry" warning on every fresh install.
INSERT IGNORE INTO role_permission (role_id, permission_id) VALUES
    ('role_template_teacher', 'perm_projects_read'),
    ('role_template_teacher', 'perm_projects_create'),
    ('role_template_teacher', 'perm_projects_update'),
    ('role_template_teacher', 'perm_projects_publish'),
    ('role_template_teacher', 'perm_eventProposals_read'),
    ('role_template_teacher', 'perm_eventProposals_review'),
    ('role_template_teacher', 'perm_eventProposals_assign'),
    ('role_template_teacher', 'perm_applications_read'),
    ('role_template_teacher', 'perm_serviceRecords_read'),
    ('role_template_teacher', 'perm_reviews_read'),
    ('role_template_teacher', 'perm_reviews_decide'),
    ('role_template_teacher', 'perm_reviews_assign'),
    ('role_template_teacher', 'perm_cases_create'),
    ('role_template_teacher', 'perm_cases_read'),
    ('role_template_teacher', 'perm_students_read'),
    ('role_template_teacher', 'perm_progress_read'),
    ('role_template_teacher', 'perm_organizations_read'),
    ('role_template_teacher', 'perm_organizations_manage'),
    ('role_template_teacher', 'perm_accounts_read'),
    ('role_template_teacher', 'perm_rules_read'),
    ('role_template_teacher', 'perm_reports_read'),
    -- The same six the live `teacher` role gains above (§2.2): a template
    -- that does not match the role it templates is a trap for whoever
    -- creates the next teacher role from it.
    ('role_template_teacher', 'perm_exports_studentList'),
    ('role_template_teacher', 'perm_exports_serviceRecords'),
    ('role_template_teacher', 'perm_exports_studentCompletion'),
    ('role_template_teacher', 'perm_exports_studentParticipation'),
    ('role_template_teacher', 'perm_exports_projects'),
    ('role_template_teacher', 'perm_exports_reviewHistory'),
    ('role_template_teacher', 'perm_serviceCategories_read');
