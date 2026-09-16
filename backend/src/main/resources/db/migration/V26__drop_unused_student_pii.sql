-- Data minimization (Docs/optimization/08-SecurityPrivacyHardening.md §3.1,
-- finding W-5): three columns held personal data no screen ever displayed.
--
-- guardian_name/guardian_contact are an adult third party's name and phone
-- number, carried since V4 for a guardian-account feature Phase 2 explicitly
-- does not have (Q4). email is the student roster's own address, distinct from
-- user.email and never read by anything.
--
-- Not collecting them is cheaper than protecting them: they widened the breach
-- surface, the import template, and the sensitive-field machinery for no
-- functional return.
--
-- user.email is deliberately untouched — a different column on a different
-- table, and the login identifier the sign-in page advertises.
ALTER TABLE student DROP COLUMN guardian_name;
ALTER TABLE student DROP COLUMN guardian_contact;
ALTER TABLE student DROP COLUMN email;
