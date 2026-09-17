# 098 — Audit Logs needs search, filter, sort, and a way to see the row

**Status:** Fixed, live-verified (§7).
**Raised:** 2026-09-18 by the user: the Audit Logs tab should get the same
server-side search/filter/sort the Users tab got (issues 094/095), and each
entry needs a **View Detail** action or click-to-expand — the pattern Time Coin
Accounts already uses — whenever the row doesn't show everything.

## 1. The tab has no controls at all

Users, Students, Projects and the rest all have at least a search box. Audit
Logs has **pagination and nothing else** — no search, no filter, no sort. At
1,045 rows and 51 pages, finding "what did this admin do to this student's
record last Tuesday" means paging.

## 2. Two parameters are accepted and silently ignored

Worse than absent, because a caller cannot tell. Both verified against the
deployed server:

| Request | Expected | Actual |
| :--- | :--- | :--- |
| `?sort=createdAt` (ascending) | oldest first | newest first — `2026-09-17T16:16:11.542Z`, **identical** to `-createdAt` |
| `?search=nonsense-xyzzy` | few or no rows | **all 1,045** |

`AuditLogController` declares `SORTABLE = {"createdAt"}` and builds a `Pageable`
from it, so `sort=createdAt` passes validation — and then
`AuditLogQueryServiceImpl` ends its statement with a hardcoded
`order by created_at desc`, dropping the direction on the floor. **Ascending
order is unreachable.** `search` is never read from `PageQuery` at all.

This is the family issue 095 §2 is about, and issue 097 was another instance: a
control that answers a narrower question than the one asked, or none at all,
while looking like it worked.

## 3. The substance of each row is never displayed

`AuditLogDto` carries thirteen fields. The tab renders five: `action`,
`resourceType`, `resourceId`, `reasonCode` (labelled "memo"), and `createdAt`
plus the actor. Never shown anywhere in the product:

| Field | Rows holding it (of 1,045) |
| :--- | ---: |
| `beforeSummary` | 485 |
| `afterSummary` | 756 |
| `ipAddress` | 1,045 |
| `requestId` | — |
| `permissionUsed` | — |
| `userAgent` | — |

**`beforeSummary`/`afterSummary` are the audit log.** Everything else is
metadata about a change whose content is these two columns, and the only way to
read them today is a SQL client. `Design/*/08-mlps-level1-security-design-guide.md`
§10.2 lists "变更前后状态" — before/after state — as a required element of the
audit record; it is recorded faithfully and then not surfaced.

`ipAddress` matters for the same reason issue 091 made it trustworthy in the
first place (the `X-Forwarded-For` forgery): a source address nobody can see is
a control that exists only on paper.

## 4. The operator column reads from whatever page of users is loaded

```ts
const operator = log.actorId ? (users.find(u => u.id === log.actorId)?.name ?? log.actorId) : ...
```

`users` is **one server-filtered page** — 20 of 37 accounts, fewer when a filter
is active, and empty until the Users tab has been visited. An actor outside that
page falls back to the raw opaque id. So the name shown depends on unrelated
state on a different tab, and 38 distinct actors appear in the log against at
most 20 loaded rows.

## 5. What gets built

**Backend.**

- `search` matches the actor's **display name**, plus `action`, `resourceId` and
  `requestId`. The name is what an administrator actually knows; `requestId` is
  what an error envelope hands a user to quote. Resolved by a `left join user`
  in the read model's raw SQL — no Java dependency on `iam`, the same argument
  `UserListQueryRepository` already makes for joining `student`.
- `filter[action]` and a widened `filter[resourceType]`, with the option lists
  **served, not hardcoded**: a new `GET /audit-logs/facets` returns the distinct
  values present (27 actions, 10 resource types today). Action codes are added
  over time — `humanizeAuditAction` exists precisely because a fixed table would
  drift — so a hardcoded dropdown would quietly stop offering new ones.
- The `order by` honours the `Pageable` instead of ignoring it, over a
  whitelist widened to `createdAt`, `action`, `resourceType`. `createdAt desc`
  stays the default and the tiebreaker, so equal timestamps keep a stable page
  boundary.
- `actorId` resolves to a display name **server-side**, in the same query, so §4
  cannot recur by construction.

**Frontend.** The shared `ListSearchInput` (committed on Enter/icon, never
per-keystroke — issue 095 §8), filter dropdowns fed by the facets endpoint, sort
buttons matching the Users tab, and **click-to-expand** on each row, following
`AccountManagement`'s `expandedId` pattern as asked. The expanded panel shows
before/after as formatted JSON alongside actor, permission, outcome, IP, user
agent and request id.

Expand rather than a dialog: the rows are a chronology and the question is
usually "what changed here, and what happened either side of it" — a modal
covers the neighbours that give it meaning. No second fetch either, unlike
Accounts: the list response already carries every field.

## 6. Out of scope

Export. `GET /audit-logs` has no CSV counterpart and issue 093 removed the
buttons that implied one. Worth having, not part of this.

## 7. Verification

Deployed and driven against the real server. Totals (`totalItems`) are asserted
throughout rather than visible rows: a filter applied to the loaded page instead
of the query looks identical on screen.

**The two silent defects, before and after:**

| Request | Before | After |
| :--- | :--- | :--- |
| `?sort=createdAt` | `2026-09-17T16:16:11.542Z` — identical to `-createdAt` | `2026-09-16T09:33:09.794Z`, the oldest row |
| `?search=nonsense-xyzzy` | all 1,045 | **0** |
| `?search=login` | all 1,045 | 26 |

**The rest:**

| Check | Result |
| :--- | :--- |
| Controls render | search box, 27 actions + "All Actions", 10 types + "All Types", three sort buttons with `Time ↓` active |
| Facets are server-derived | `GET /audit-logs/facets` → 27 actions, 10 resource types, matching the distinct values in the table |
| Actor name | `Teacher Wei Chen`, resolved server-side — no Users tab visit required |
| Search by actor name | `Wei` → 1,046 → **62** |
| Typing does not search | **0 requests** across 8 characters; total unchanged at 1,046 |
| Enter commits | **1** request; 1,046 → 62 |
| `filter[action]` | 1,046 → **5** for `user.adminResetCredential` |
| Expand | all nine metadata fields plus before/after rendered as formatted JSON |

**Sort across a page boundary**, the acceptance criterion from issue 095 §5 — a
sort that only orders within a page passes any single-page test:

| Sort | page 1 last | page 2 first | Ordered | Rows repeated |
| :--- | :--- | :--- | :--- | :--- |
| `createdAt` | `09:33:11.529` | `09:33:11.715` | yes | 0 |
| `-createdAt` | `15:54:08.836` | `15:54:08.485` | yes | 0 |

The first attempt at this check was made in the browser and **proved nothing**:
the UI renders timestamps to the minute, and all three boundary rows read
`2026-09-16 17:33`, so the comparison was trivially true and could not have
distinguished a working sort from a broken one. Redone against the API at
millisecond precision. The "rows repeated" column is what tests the tiebreaker —
`created_at` is not unique, and without `a.id` appended, rows sharing a
millisecond can appear on both pages or on neither.

## 8. Not done

- **Action-code wording is settled, not outstanding.** `humanizeAuditAction`
  renders `user.adminResetCredential` as "User — admin Reset Credential", which
  this change makes more visible by putting 27 of them in a dropdown. **The user
  reviewed the rendering on 2026-09-18 and accepted it as-is**, so this is a
  decision rather than a deferral: do not re-raise it, and do not add a per-code
  i18n table — that is exactly what the function's own comment argues against,
  since the table would drift out of sync with a backend that adds action codes
  with every feature.
- **Export** — see §6.
