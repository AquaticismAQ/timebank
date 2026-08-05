-- I3.6: notifications were rendered once at creation time, in the actor's
-- language, and the rendered strings stored permanently (V5's original
-- design, explicitly flagged in Notification's own doc comment as
-- deferred "since re-rendering per request would need the recipient's
-- language resolved at read time, which no read endpoint exists to do yet
-- (B5's)" — B5/I1 built that read endpoint since; this closes the gap).
-- Templates are 100% static per language (NotificationTemplateService.render
-- has never taken interpolation params), so templateCode + the reader's
-- resolved language is sufficient to reconstruct title/body on every read —
-- no replacement column is needed, this is a pure drop.
ALTER TABLE notification
    DROP COLUMN title,
    DROP COLUMN body;
