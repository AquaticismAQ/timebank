# 01 — Historical import re-enable utility (console)

**Status:** Specified, not built. No target release.
**Depends on:** `Design/*/12-DataImport.md` §6, which defines the window this
utility reopens.
**The window itself now exists** (2026-09-19, `V32__historical_import_window.sql`).
That settles the one thing this spec could not assume: §3's requirement that a
reopen record who did it, when, and why has somewhere to go. The
`historical_import_window` table carries `reopened_at`, `reopened_by` and
`reopen_reason`, deliberately left empty by the application — nothing in the
running system writes them, and nothing in it can. So this utility is now a
write to an existing shape rather than a migration to be written under deadline
pressure by whoever needs the door opened.

## 1. What this is

The historical service-record import closes permanently 15 days after its first
successful run (`12-DataImport.md` §6). Once closed, the card disappears from the
export/import screen and no HTTP route can reopen it.

This document specifies the **only** way back: a maintenance utility run on the
server console by a system administrator.

It does not exist yet. This file exists so that the design in
`12-DataImport.md` §6 does not promise a door that nobody has written down.

## 2. Why it is console-only

The window exists to stop a migration-era capability staying open forever. A
one-off migration tool that credits the time-coin ledger directly is exactly the
thing that should not remain one click away once the migration is over.

Any re-enable path reachable from the browser defeats that, however well it is
permissioned — a button an administrator can find is a button that gets pressed
under deadline pressure. Requiring shell access on the server makes reopening a
deliberate, logged, two-person-visible act rather than a UI affordance.

**This is the one place a system administrator touches the import feature**, and
it is not a contradiction of `11-RolesAndPermissions.md` §6.1: that rule says
System Admin may not import or export data. Operating the switch is not the same
act as walking through the door — the utility reopens the window and grants
nothing to the operator, who still holds no `imports.*` permission and still
cannot run an import.

## 3. What it must do

| Requirement | Why |
| :--- | :--- |
| Run only on the server, from a shell | The property in §2 is the whole point |
| Require an explicit reason string | The audit entry has to say why, not just that |
| Write an audit entry naming the operating OS user, the reason and the new expiry | Reopening is a privileged act and must be reconstructable afterwards |
| Set a **new, bounded** expiry rather than removing the limit | Reopening restores a window, it does not remove the mechanism |
| Refuse silently-repeated runs | Re-running should extend deliberately, not by accident |
| Print what it changed | The operator is on a console with no UI to confirm from |

## 4. Open questions for whoever builds it

- **How long is the reopened window?** Another 15 days, or an operator-supplied
  length with a ceiling? A fixed length is harder to misuse; a supplied one
  handles a migration that genuinely needs longer.
- **Where does it live?** A Spring Boot CLI runner in the existing fat JAR,
  invoked with a profile, keeps it in one deployable and reuses the datasource.
  A separate script would need its own database credentials, which is a second
  place for them to exist.
- **Does it need a second pair of eyes?** A two-person rule is achievable
  (two operators, two confirmations) but nothing else in this system works that
  way today, so it would be a new pattern rather than a consistent one.

## 5. What it must not do

- **Must not be reachable over HTTP**, under any permission.
- **Must not grant the operator import permission**, permanently or temporarily.
- **Must not import anything itself.** It reopens the window; an SDC Admin then
  performs the import through the normal validate-then-commit flow.
- **Must not remove the expiry mechanism**, only set a new expiry.
