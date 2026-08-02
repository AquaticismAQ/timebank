# B2 · Base Data, Rules, and Localization

> Phase B2 of `01-Overview.md`. The reference data every business record points at, the versioned rules every credit decision is explained by, and the localization machinery both depend on.

| | |
| --- | --- |
| **Depends on** | B1 |
| **Blocks** | B3 (records need categories, rules, and students), I1's dictionary swap |
| **Effort / calendar** | 5 pw · W6–W8 |
| **Design refs** | `Design.md` §9 (localization), §12 invariants 7–8, §22.1, D8; `APIContract.md` §2.10, §4.9–§4.12, §4.16 |

---

## 1. Goal

Two things that are cheap now and expensive later: **rule versioning wired in from the first migration** (R7 — records reference the rule version in effect at their service date, never the current one), and **per-language dictionary names** (so localization is a schema property rather than a presentation hack).

## 2. Scope

**In:** student directory, academic structure, organizations, service categories, rules with versions and effective windows, school info, system settings, feature flags, localization resolution, dictionary caching.

**Out:** any transactional business record (B3); guardian *accounts* (Q4 — guardian name and contact are student fields only); import of historical students (B5 handles imports; B2 supports manual and seeded creation).

## 3. Work Items

| ID | Item | Deliverable |
| --- | --- | --- |
| **B2.1** | `student` module — students, guardians as fields, status changes, activate/deactivate; **unique constraints scoped per academic-year cohort**, never global (D8 guardrail) | Contract §4.9 endpoints; guardian and contact fields **excluded from list responses** (§9) |
| **B2.2** | Academic structure — academic years, grades, classes, cohort/graduation-year field (the purge key B6 depends on) | `GET /academic-years`, `/grades`, `/classes`; graduation year present from the first migration |
| **B2.3** | `organization` module — partner organizations, types, contacts, archive; `projectCount` as a server-computed aggregate (§2.11 rule 3) | Contract §4.10 endpoints |
| **B2.4** | Service categories with per-language names (`name_zh`, `name_en`) | `GET /service-categories` returns `id` + localized `name`, never a null or empty name |
| **B2.5** | **Localization resolution** — `Accept-Language` → `preferredLanguage` → school default; applied to all dictionary reads; enum and status values never localized | Filter/resolver used by every controller; a test asserting no status value is ever translated on the wire |
| **B2.6** | Notification templates — `templateCode` + parameters, per-language bodies | Table and rendering service ready for B3's approval notification |
| **B2.7** | `rules` module — rule versions, draft/validate/publish/archive, effective windows, **published versions immutable** (invariant 7) | Contract §4.12 endpoints; an update to a published rule is rejected, not silently versioned |
| **B2.8** | Rule seeding — 0.5 h rounding granularity, 1 h → 1 coin (60 min → 100 centi-coins), honour tiers 25/75/150/300, annual and stage hour requirements | `R__` migration; the conversion ratio is a rule-table field, never a constant in code (§22.1) |
| **B2.9** | Rule resolution service — "the rule version in effect at date D", used by B3's crediting and progress | Single entry point; a test proving a rule change does not alter an already-credited record (invariant 8) |
| **B2.10** | School info and system settings — `GET /school/info` for all authenticated users; `/system/settings` for administrators | Contract §4.16; school-specific configuration lives in data, not deployment properties (D8 guardrail 2) |
| **B2.11** | `feature_flag` table feeding `/me`; reserved Phase 3/4 endpoints reject mutations at the API boundary with `FEATURE_DISABLED` | Flags only — **no flagged feature is built or stubbed** (`Design.md` §14) |
| **B2.12** | Dictionary caching — Caffeine, ~60 s TTL, explicit eviction on rule publication | Bounded staleness accepted because rules carry effective times (D1) |

## 4. Exit Criteria

- [x] Students, grades, classes, academic years, organizations, and categories are queryable, scoped, and paginated. Proven live over HTTPS (create/list/detail/deactivate a student; `GET /academic-years`, `/grades`, `/school/info`) and by `mvn verify` on the server against real MySQL.
- [x] Student **list** responses contain no guardian name, guardian contact, phone, or e-mail; detail responses expose them only under permission, and that access is audited. Proven by `StudentPrivacyIT` on real MySQL — this caught a real bug (see §12 of `00-Status.md`): `getDetail`'s audit write was silently dropped because the method was `@Transactional(readOnly = true)`.
- [x] The same request with `Accept-Language: zh-CN` and `en` returns identically-coded, differently-named dictionary data — and identical status/enum values. Proven by `LocalizationIT` and live: `GET /grades` with each header returns the same `id`/`code`/`sequence`, different `name`.
- [x] A missing translation falls back to the school default; no response contains a null or empty localized name. Proven by `LocalizedNames.pick`'s fallback logic and `LocalizationIT`'s blank-name assertion (no missing-translation row exists in seed data to exercise the fallback branch itself, but the logic is unconditional, not data-dependent).
- [x] Publishing a rule creates a new version; editing a published version fails. Proven by `RuleVersioningIT` and live (`PATCH` on a published rule → `409 STATE_CONFLICT`).
- [x] `resolveRule(date)` returns the historically correct version, proven by a test that publishes a new rule and re-reads an old date. `RuleVersioningIT.publishingANewRuleClosesThePreviousOnesWindowWithoutAlteringHistoricalResolution` — publishes a second rule with different rounding and confirms the old service date still resolves to the first rule's parameters.
- [x] Every student row carries a graduation year (B6's purge cannot be retrofitted onto rows without one). Schema `NOT NULL`; proven live via student creation.
- [x] Feature flags in `/me` match the flag table; a mutation on a Phase 3/4 endpoint is rejected at the API boundary. Flags proven live (`/me` reflects all-disabled seed row). The rejection half is **not applicable yet** — no Phase 3/4 endpoint exists to mutate (correct per `Design.md` §14: flags are not built ahead of the feature they gate).

## 5. Risks and Watch-Outs

- **Rule versioning deferred is R7 realized.** If B3 credits records against "current settings" and versioning is added later, every pre-existing record becomes unexplainable. B2.9 must exist before B3.
- **Localization as a view concern.** Putting Chinese and English names in the frontend's i18n bundle works until a school adds a category — then a data change requires a frontend release. Dictionary names belong in the database.
- **Global unique constraints.** A student number unique across all time blocks re-enrolment and cohort reuse. Scope it per academic year (D8).
- **The 186-student mock trap.** Base data volumes come from `Design.md` §3, not from frontend fixtures: 5,000 students, ~500 staff.

## 6. Hand-Off

B3 gets students, categories, organizations, resolvable rules, and notification templates — everything a service record must reference. I1 can swap dictionaries, school info, and the student list.
