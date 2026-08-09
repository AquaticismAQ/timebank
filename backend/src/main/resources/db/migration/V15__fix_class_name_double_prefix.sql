-- Issue 005: the 12 class rows from R__seed_base_data.sql were originally
-- seeded with the grade name baked into name_zh/name_en (e.g. 'Grade 9 B',
-- '九年级B班'), so combining them with the grade's own name at display time
-- produced "Grade 9 Grade 9 B". R__seed_base_data.sql now inserts the
-- corrected values going forward, but its INSERT IGNORE never touches rows
-- that already exist — this versioned migration is the one-time correction
-- for those. A plain versioned UPDATE, not a repeatable migration, since it
-- must run exactly once and never again (Flyway versioned-migration
-- semantics are the right fit for "fix already-inserted bad data", unlike
-- R__'s rerun-on-checksum-change/INSERT-IGNORE behavior).
UPDATE class SET name_en = 'A', name_zh = 'A班' WHERE id IN
    ('class_6A', 'class_7A', 'class_8A', 'class_9A', 'class_10A', 'class_11A');
UPDATE class SET name_en = 'B', name_zh = 'B班' WHERE id IN
    ('class_6B', 'class_7B', 'class_8B', 'class_9B', 'class_10B', 'class_11B');
