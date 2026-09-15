# 078 — Service categories have no management UI (and no delete)

Status: **Fixed and live-verified (2026-09-16)**

## Request

Reported directly by the user:

> add a new page tab in system admin after the 'Audit Logs' for the Service
> Category management, it can create, edit, delete Service Categories, for
> creation, both english and chinese name is required so the frontend can
> show the corresponding value based on current language. seems that the
> current Service Category are hardcoded, seed the database with current
> values, update seeding script as needed

## What turned out to already exist

The "hardcoded" suspicion is understandable from the UI, but the data model
is already right — worth recording so none of it gets rebuilt:

- **`service_category` is a real table** (`V4__base_data.sql`) with
  `code`, **`name_zh`**, **`name_en`** and `active`. The bilingual
  requirement is already the storage model.
- **It is already seeded** with the six current values by the repeatable
  migration `R__seed_base_data.sql` (`cat_campus`, `cat_community`,
  `cat_welfare`, `cat_education`, `cat_environment`, `cat_other`), and the
  live database holds exactly those six, all active. **No database seeding
  was needed.**
- **The seed script needs no change either.** `integration-test/mock-data.ts`
  only *references* category ids; it never creates them, relying on the
  migration. That is correct as-is.
- **The frontend does not hardcode the list.** Every picker reads
  `api.dictionaries.serviceCategories()`, and the backend resolves the name
  per `Accept-Language` via `LocalizedNames.pick`. The only hardcoded thing
  is a default *selection* (`categoryId: 'cat_community'`) in two create
  forms, which is a sensible default, not a hardcoded vocabulary.
- **Create and update endpoints already exist**
  (`POST`/`PATCH /api/v1/service-categories`, gated on `rules.manage`),
  already take `nameZh` + `nameEn`, and already audit-log and evict the
  dictionary cache.

## The actual gaps

1. **No management UI.** Nothing in the Admin Portal reaches those
   endpoints, so categories can only be changed by hand in the database.
2. **No delete.** Only list/create/update exist.
3. **`ServiceCategoryDto` exposes only the resolved `name`**, not
   `nameZh`/`nameEn` — so an editor cannot prefill both fields. An admin
   editing in English would have no way to see, let alone keep, the Chinese
   name.
4. **`active` did nothing.** `GET /service-categories` returned every
   category regardless of the flag, so "deactivate instead of delete" — the
   designed way to retire a category with history — left it sitting in every
   picker exactly as before. Found while writing this screen's copy: the
   sentence describing what deactivation does would have been false.

## Design decisions

**Delete refuses when the category is in use.** Three foreign keys point at
`service_category(id)` — `service_record`, `project` and `event_proposal`.
A hard delete of an in-use category would fail at the database with an FK
violation surfacing as an opaque `500`, exactly the shape of issue 059. So
the endpoint deletes only when nothing references the category, and
otherwise returns a clear conflict telling the admin to deactivate it
instead. That is also why `active` exists: deactivation, not deletion, is
the designed way to retire a category that has history.

**The in-use check is a caught FK violation, not a cross-module query.**
`cn.timebank.organization` may not reach into `servicerecord`/`project`/
`proposal` internals — `ModuleBoundaryTest` forbids depending on another
module's `.domain`/`.infrastructure`. Attempting the delete and translating
the integrity violation keeps the module boundary intact and has no
races (the database is the authority either way).

**No `code` field in the UI; the backend derives it (follow-up, same day).**
The first cut asked the admin for a code. Nothing outside `service_category`
needs a human-chosen one — records reference the opaque id, and the code is
only a readable handle in the database and in audit entries — so asking for it
made the admin invent a value with no meaning to them and gave one more way to
get a create wrong. `create` now takes the two names only and slugs the code
from the English name (`"Library Assistance"` → `cat_library_assistance`),
falling back to a unique suffix if that slug is taken or the name has no ASCII
in it. `code` is `VARCHAR(32)`, so the slug is capped at 21 characters to leave
room for that suffix.

**A duplicate is either name matching, and it is a dialog, not a toast.**
Two categories that read the same in one language are indistinguishable to
whoever is picking one in that language, whatever their other name says — so
matching *either* name (case- and whitespace-insensitive) is the rule, at the
user's instruction, and it is simpler than any partial rule would be. Update
enforces the same rule, since renaming is the other way to collide. The
frontend checks the loaded list first so the refusal is instant and can name
the existing category, and still handles the backend's `409` for the case where
someone else created the clash meanwhile.

**The DTO gains `nameZh`/`nameEn` alongside the existing resolved `name`,**
rather than replacing it. Every existing consumer keeps reading `name` and
is unaffected; only the management screen uses the two raw fields.

## Test plan

- `mvn test`; an IT covering create → update → delete, and delete refused
  for an in-use category.
- `tsc --noEmit`, `pnpm build`, en/zh key parity.
- Live: create a category with both names, confirm it appears in a record
  form's picker **in both languages**, edit it, delete it, and confirm
  deleting an in-use category is refused with a useful message.

**Deactivating now really retires a category.** `list()` keeps returning
everything — other modules resolve the display name of records that already
point at a retired category through it — and a new `listSelectable()` filters
on `active` for the read endpoint. `GET /service-categories` defaults to the
selectable list; the management screen passes `?includeInactive=true` so it
can still see and re-activate what it retired.

One consequence worth knowing: a draft record that already references a
now-inactive category will not find it in the picker when edited, so the
student has to pick a current one. That is the intended effect of retiring a
category, not a regression.

## Resolution

Backend:

- `ServiceCategoryDto` — now `(id, code, name, nameZh, nameEn, active)`; the
  resolved `name` is unchanged for every existing consumer.
- `ServiceCategoryServiceImpl.delete(...)` — deletes, flushes, and translates
  a `DataIntegrityViolationException` into a `409` conflict that names the
  remedy ("Deactivate it instead"); audit-logs `serviceCategory.delete` and
  evicts the dictionary cache like create/update already did.
- `ServiceCategoryService.listSelectable(...)` — active-only view.
- `ServiceCategoryController` — `DELETE /api/v1/service-categories/{id}`
  behind `rules.manage`, and `GET ?includeInactive=true`.
- `CreateServiceCategoryRequest` — `(nameZh, nameEn)`; `code` removed and
  derived by `generateCode(...)`. `requireNameIsFree(...)` rejects a create or
  rename whose English *or* Chinese name is already taken.
- `ServiceCategoryManagementIT` — 9 tests, green on the server: both names
  stored and returned whatever the caller's language, update, deactivate
  leaves the selectable list but not the full one, delete when unused, delete
  refused when in use (the test creates the referencing project itself — the
  IT schema has no seeded usage), duplicate English name with a different
  Chinese one rejected, duplicate Chinese name with a different English one
  rejected, rename-onto-another-category rejected while re-saving a
  category's own name is not, and the derived code (slug, plus a unique
  suffix on collision).

Frontend:

- `components/admin/ServiceCategoryManagement.tsx` (new) — list with both
  names and an "Inactive" badge, create (code + both names, all required),
  edit (both names + active; code immutable), delete with confirmation.
  Clears the shared dictionary cache after every mutation so other screens'
  pickers pick the change up without a reload.
- `SystemAdmin.tsx` — `categories` tab immediately after Audit Logs.
- `api/client.ts`, `api/types.ts` — `api.serviceCategories.{list,create,update,remove}`,
  `ServiceCategory` gains `nameZh`/`nameEn`.
- `i18n/{en,zh}.json` — `admin.system.tabCategories`, `admin.categories.*`.
- `Docs/integration/APIContract{,_CN}.md` §4.10 — DELETE row and the
  `includeInactive` parameter.

## Live verification (2026-09-16, `admin001`)

- Tab appears between Audit Logs and Partner Organizations; lists the 6
  seeded categories with both names.
- Create with a code but no names → blocked client-side with the required
  message. Create "Library Assistance" / 图书馆协助 → created, appears in the
  Project Management category filter as "Library Assistance" in English and
  图书馆协助 in Chinese.
- Edit prefills **both** raw names; renamed to "Library Support" and
  deactivated → card shows the Inactive badge, and the category disappears
  from the project filter picker while staying on the management screen.
- Delete `cat_community` (in use) → `409`, localized refusal toast, category
  still present. Delete the unused `cat_verify078` → removed.
- After the code/duplicate follow-up (same day): the create form has no code
  field; entering a new English name with `  社区服务 ` (padded, and matching
  an existing Chinese name) opens the "Category already exists" dialog naming
  **Community Service / 社区服务** instead of creating anything; posting
  `{nameEn: "  community SERVICE ", nameZh: "完全不同"}` straight to the API
  returns `409 STATE_CONFLICT` with the same explanation, so the rule is
  enforced server-side and not only in the form; and creating "Elder Care
  Visits" / 敬老探访 through the form produced the derived code
  `cat_elder_care_visits`.
- `GET /audit-logs` shows `serviceCategory.create`, `.update` and `.delete`
  for this actor. Net database change: none (the test category was created
  and deleted; the 6 seeded categories are untouched — re-confirmed after the
  follow-up pass, whose two test categories were deleted as well).
