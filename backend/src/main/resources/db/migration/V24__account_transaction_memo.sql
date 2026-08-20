-- account_transaction had reason_code (a short admin-selected category, e.g.
-- "CORRECTION") but nowhere to put the actual free-text explanation an admin
-- types alongside it — every request DTO already accepted a `memo` field,
-- but AccountServiceImpl silently dropped it (Docs/issues/037-*.md).
ALTER TABLE account_transaction ADD COLUMN memo VARCHAR(1000) NULL AFTER reason_code;
