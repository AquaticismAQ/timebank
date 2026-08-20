-- 32 characters was too tight for a free-text "activity format" description
-- (Docs/issues/036-*.md) — a normal phrase like "Classroom donation drive
-- plus delivery visit" (45 chars) already overflows it.
ALTER TABLE event_proposal MODIFY format VARCHAR(64);
