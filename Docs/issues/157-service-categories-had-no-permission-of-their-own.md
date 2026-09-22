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

## 4. What it must not do

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
