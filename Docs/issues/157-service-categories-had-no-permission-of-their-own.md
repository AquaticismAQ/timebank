# 157 — Service categories had no permission of their own

**Status:** **Fixed and deployed 2026-09-22.** Aquaticism, immediately after issue 156:
"where is the permission for service categories create/edit/view? seems missed"
— and then, "teacher role should have view permission."

## 1. What was there

Nothing named after it. The three write endpoints borrowed `rules.manage`:

```java
@PostMapping("/api/v1/service-categories")
@PreAuthorize("hasAuthority('rules.manage')")
```

and the permission catalogue admits it in the row's own description — *"Create or
edit rule drafts **and service categories**"*. One permission, two unrelated
things.

They are not alike. A rule version is a versioned, immutable-once-published
policy object whose change recalculates every student's compliance; a service
category is a word on a dropdown. Bundling them means a school cannot let someone
tidy the category list without also handing them the ability to draft and edit
the rules that decide who graduates. That is the exact failure `11-RolesAndPermissions.md`
§5.1 names for exports: at this grain the only choices are *too much* or
*nothing*.

Issue 156 hid the Service Categories tab behind `rules.manage` yesterday, which
was right given what existed and wrong as a resting place — it made a teacher's
read-only view impossible, because the only permission the tab could ask for was
one that also grants writing.

## 2. Fix

Two permissions, and a deliberate decision not to add a third.

| Permission | Covers | Student | Teacher | SDC Admin | Sysadmin |
| :--- | :--- | :---: | :---: | :---: | :---: |
| `serviceCategories.read` | the management view — the full list *including retired* categories | — | ✓ | ✓ | ✓ |
| `serviceCategories.manage` | create, rename, retire, delete | — | — | ✓ | ✓ |

**Teacher gets read**, per Aquaticism. A teacher chooses categories all day when
reviewing records; being able to see the whole vocabulary, including what has
been retired and therefore why an old record names something no longer offered,
is a reading of the school's own dictionary, not an administrative power.

### The plain dictionary read stays ungated

`GET /service-categories` — the selectable categories, the list behind every
picker on every form — remains `isAuthenticated()`. A student submitting a
service record has to have it, and a permission every single role must hold is a
permission that never denies anything: decoration with a failure mode, since
forgetting to grant it to one role silently breaks that role's forms.

What `serviceCategories.read` gates is `?includeInactive=true`, which is the
management screen's variant and the only part that shows something a form does
not: **what has been retired**. So the permission has a real boundary rather than
a nominal one, and the line falls exactly where the extra information does.

### `rules.manage` loses the second half of its description

Its wording is corrected everywhere it appears — the seed, a versioned migration
for the live row (`INSERT IGNORE` cannot update an existing one), and the
translated copy Role Management actually displays.

## 3. Verification

**On the deployed server, as all three kinds of person.**

The migration, read back from the live catalogue:

```
perm_rules_manage              rules.manage               Create or edit rule drafts
perm_serviceCategories_read    serviceCategories.read     View the service category list, including retired categories
perm_serviceCategories_manage  serviceCategories.manage   Create, rename, retire or delete a service category

read    → teacher, admin, sysadmin, template_teacher
manage  → admin, sysadmin
```

`rules.manage`'s description no longer says "and service categories" — including
in Role Management, where the copy comes from the translation bundle rather than
the row, so both halves had to be corrected.

**Teacher** (`teacher001`), who holds `serviceCategories.read` and neither
`serviceCategories.manage` nor `rules.manage`:

```
Service Categories tab       visible, six categories listed
buttons on the page          only the tabs — no New Category, no row actions
GET /service-categories                       200
GET /service-categories?includeInactive=true  200
POST /service-categories                      403
PATCH /service-categories/cat_other           403   (and cat_other still reads "Other")
```

**Student** (`28339473`) — the control that decides whether the split is real
rather than nominal:

```
GET /service-categories                       200, six categories
GET /service-categories?includeInactive=true  403 FORBIDDEN
```

One endpoint, two answers, decided by the query parameter. And the thing that
would actually hurt if this were wrong was checked in the UI rather than at the
API: the student's *Submit a service record* wizard still renders all six
categories in its picker.

**Admin** (`admin001`), proving `serviceCategories.manage` still works end to end
rather than inferring it — a probe category created, renamed and deleted through
the real endpoints:

```
categories before  6
POST   200     PATCH  200     DELETE 200
categories after   6          probe gone
```

The server is left exactly as it was found. Nothing else was written.

### The stale-session fix, with the failing condition forced

A pre-migration session was reconstructed rather than waited for: the stored
user's permission list was doctored in `localStorage` to what it would have held
before `V34`/`V35` ran — `serviceCategories.*` stripped out, `organizations.manage`
put back — and the page reloaded once.

```
admin001    doctored 53 perms → after one reload 55, serviceCategories.read back
            tabs: all nine, Categories among them
            GET /api/v1/me fired (network panel), which is the mechanism

teacher001  doctored: serviceCategories.read absent, organizations.manage present
            after one reload:  serviceCategories.read  present   (added back)
                               serviceCategories.manage absent   (never granted)
                               organizations.manage    absent    (revoked)
            tabs: Rules · Data Export · Categories · Organizations · System
```

The **removal** is the half that proves the mechanism. Restoring a missing
permission could be explained by a union; dropping an injected one cannot. The
refreshed list is the server's answer, not a merge of it with whatever the
browser was holding — which is what makes a *revoked* permission take effect
without signing out.

### Organisations, read-only for teachers and still editable by admins

```
teacher001  GET  /organizations      200, six rows, zero action buttons on screen
            PATCH /organizations/{id} 403
            POST  /organizations      403
admin001    PATCH /organizations/{id} 200   ← the control
```

Both `PATCH`es send the organisation's **current** name, so the probe changes
nothing whichever way the permission check goes; the name afterwards is still
`Example Community Service Centre`. Without the admin line, two 403s would only
show that something was broken.

### The server-session half — what is proven, and what is not

Proven directly:

```
service_category         6 rows, all active, throughout     ← nothing was erased
GET ?includeInactive=true   200 with 6 rows to a current session
admin001, fresh session     Categories tab → "6 service categories"
mvn test                    373 pass
```

**Not yet proven, and this is a real gap rather than a formality.** Demonstrating
`SessionAuthorityRefresher` needs a permission to change *while a session is
live*, and every session available here was created after the migrations, so the
refresher correctly does nothing for them — a test that cannot fail. Forcing the
condition means revoking and restoring a grant on the shared server, which is a
live database write; offered and declined in favour of Aquaticism reloading one of his
own genuinely-stale tabs, which is the reported case itself.

So the two directions still to see confirmed are: a tab that was showing **0**
listing its six categories after one reload with no re-login, and a revoked
permission losing its affordance the same way. The next session that touches this
should close this paragraph out rather than assume it.

## 4. The fallout, found by Aquaticism within the hour: the tab vanished for admins

> "why you remove the 'Service Categories' tab from admin?"

Nothing removed it. `admin` holds `serviceCategories.read` and always did from
the moment `V34` ran. What was missing was the *browser's* knowledge of it.

`AuthProvider` captured the user — permissions included — at **login**, wrote it
to `localStorage['timebank-user']`, and never re-read it. So a session that began
before the migration carried a permission list that could not contain a
permission which had not existed yet. Gate a tab on that permission and the tab
disappears for exactly the people who are entitled to it, until they sign out and
back in.

The staleness was years old and harmless while nothing was gated on it. Issues
156 and 157 are what turned it into a visible defect, so it is fixed here rather
than logged as a discovery of its own.

**Fix:** `AuthProvider` re-reads `GET /me` on every app load and merges the
authorization facts — permissions, roles, workspaces, scope grades, feature flags
— into the stored user. Merged rather than replaced, because `username` is not a
field `/me` returns. A failure is ignored: a network blip must not sign anyone
out, and a genuinely expired session is already handled by the 401 handler.

This also closes the same hole in the other direction, which matters more: a
permission *revoked* mid-session used to keep its UI affordances until the user
next logged in. Only the server ever enforced it, which was correct but meant the
screen disagreed with the system for as long as the session lasted.

**That was only half the cause, and fixing half of it made things worse.** See
§4a — Aquaticism's next report was "why both teacher and admin show '0 service
categories'".

## 4a. The other half: the session's authorities were a snapshot too

> "why both teacher and admin show '0 service categories', you erase the db of it?"

Nothing was erased — `service_category` held its six rows throughout, all active,
and the endpoint returned all six to a freshly signed-in caller.

Authorities are permission codes carried on `CookieSessionUserDetails`, which is
**serialized into the session row at login** and read back on every later
request. So the server's idea of what a caller may do is a snapshot taken when
the session began, with the same two failure directions as the browser's copy —
and a staff session lasts eight hours.

Fixing only the client made this *visible instead of invisible*, and in the worst
possible shape:

| | before §4 | after §4, before §4a |
| :--- | :--- | :--- |
| browser knows about `serviceCategories.read` | no | **yes** |
| session holds the authority | no | no |
| what the user sees | no tab at all | a tab that says **0 service categories** |

A hidden tab is a legible permission boundary. A tab that opens and reports zero
is a claim about the data — which is exactly how Aquaticism read it, and why the
question was "did you erase the db". **Half a fix was worse than none**, because
it converted a boundary into a lie about content.

**Fix:** `SessionAuthorityRefresher`, called from `GET /me` — the same request the
browser already makes on every app load, so the two halves move together by
construction rather than drifting. It reloads the user through
`UserDetailsService`, compares the authority sets, and only when they differ
re-seats the `SecurityContext` and saves it back to the session. Unchanged
authorities write nothing, so a page load stays a read. A log line records the
granted/revoked codes, since a role changing under a live session is rare and is
the first thing worth seeing when a permission question arrives.

Deliberately **not** included: revalidating the account itself. If the user has
been disabled or locked since login, the session is left alone — tearing it down
is a different decision with a different blast radius, and attaching it to a
tab-visibility fix would be the wrong place to make it.

## 5. Also in this pass: partner organisations become admin-only

> "Organization can only be modify by admin"

`organizations.manage` had been granted to the teacher role on 2026-08-08,
widening it from admin-only. `V35` reverses that for `teacher` and for the
Teacher role template, and the seed drops both lines — the both-halves rule
again, since only a versioned migration can revoke from the live schema.

The reasoning is the one that separates a year-group action from a school-wide
one: **a partner organisation is shared vocabulary.** Every project and every
service record points at one, so renaming or archiving one changes what other
people's records say about themselves. Teachers keep `organizations.read`,
because picking a project's host organisation needs exactly that and nothing
more.

`OrganizationManagement.tsx` already gates its create/edit/archive controls on
`organizations.manage`, so the screen simply becomes read-only for them rather
than offering buttons that 403 — no frontend change was needed.

**Not to be confused with the tab appearing.** Aquaticism also asked why teachers now
have an Organizations tab in System Admin that "not exists before". It did exist:
before issue 156 the tab was rendered unconditionally for everyone
(`git show df4679e^` — `{ key: 'organizations', ... }` with no gate), and the
pre-156 live measurement of `teacher001` recorded `Rules · Data Export · Audit
Logs · Categories · Organizations · System`. What changed is that it is now
*deliberate* and read-only, rather than unconditional and editable.

## 6. What it must not do

- **Must not break the category picker for a student.** §2 — the plain read is
  unchanged and ungated.
- **Must not let a teacher edit categories.** Read and manage are separate
  grants, checked separately, server-side.
- **Must not leave the two halves disagreeing.** The grants ship as both a
  versioned migration (the live schema) and a seed edit (every from-scratch
  schema, including each `timebank_it_*` test schema), per `11-RolesAndPermissions.md`
  §2.1's both-halves rule.
- **Must not reuse or rename an existing permission code.** `rules.manage` keeps
  its code and its rule-drafting meaning; only its description narrows.
- **Must not require signing out to see a permission change.** §4 — the cached
  user is refreshed from `/me` on every load.
- **Must not take `organizations.read` from teachers.** §5 — they pick a host
  organisation when creating a project.
