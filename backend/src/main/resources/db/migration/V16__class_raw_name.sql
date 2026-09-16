-- Issue 010: `class.name_zh`/`name_en` held fully-formed, hand-translated display
-- strings (including the "班" suffix) for what is actually a single raw class label
-- (a proper noun like "Luna", never really translated) — the same shape that let
-- issue 005's grade-prefix bug happen, and why the named class was still missing its "班"
-- suffix nobody remembered to add. `class` has no cross-module reader (unlike
-- `grade`, deliberately left alone here — see issue 010), so it can collapse to one
-- raw column; the frontend now assembles the localized display string itself.
ALTER TABLE class ADD COLUMN name VARCHAR(100) NULL AFTER code;

-- `name_en` is already the bare raw label post-issue-005 (`'A'`, `'B'`, `'Luna'`) —
-- backfill from it, not `name_zh` (which still carries the baked-in "班" suffix).
UPDATE class SET name = name_en;

ALTER TABLE class MODIFY COLUMN name VARCHAR(100) NOT NULL;
ALTER TABLE class DROP COLUMN name_zh;
ALTER TABLE class DROP COLUMN name_en;
