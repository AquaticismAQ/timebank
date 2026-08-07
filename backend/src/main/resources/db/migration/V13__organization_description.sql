-- 02-PorjectOrganization.md §4 decision: partner organizations get a real
-- free-text field, not just the existing structured contact columns.
ALTER TABLE organization
    ADD COLUMN description TEXT NULL AFTER type;
