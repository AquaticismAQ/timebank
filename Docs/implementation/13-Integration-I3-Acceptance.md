# I3 · Evidence Upload, Localization, Acceptance, and Cutover

> Phase I3 of `01-Overview.md`. The last integration phase: the one flow the frontend has never had at all (file upload), verification in both languages, end-to-end acceptance with real users, and the move to production.

| | |
| --- | --- |
| **Depends on** | I2, B5, B6, B7 |
| **Blocks** | Go-live. **Gate G5** |
| **Effort / calendar** | 5 pw · W20–W28 |
| **Design refs** | `APIContract.md` §4.6, §2.10, §11; `Design.md` D9, §16.3, §17 |

---

## 1. Goal

Get from "all endpoints work" to "a school can run a term on this": evidence upload built from nothing, both languages verified, the acceptance scenarios passed by actual teachers and students, historical ledgers imported, and a cutover with a way back.

## 2. Work Items

| ID | Item | Deliverable |
| --- | --- | --- |
| **I3.1** | **Evidence upload flow** — `POST /files/upload-intents` → direct PUT to object storage → `POST /files/{id}/complete` → poll until `ready` → attach to the record. Progress, cancel, retry, and size/type rejection all handled | The frontend has never had this; it is built, not adapted (contract §4.6) |
| **I3.2** | Upload failure states — file too large (`413`), wrong type, scan rejected, upload interrupted, and the "cannot submit while a file is `processing`" case surfaced **before** the student presses submit | Rejection at submit with no explanation is the worst version of this |
| **I3.3** | Evidence viewing — short-lived URLs fetched on demand via `GET /files/{id}`, never cached in application state or logged; expired URLs re-fetched transparently | Contract §9; evidence is photographs of minors |
| **I3.4** | Language preference persistence — `preferredLanguage` read from `/me` and written through `PATCH /me/profile`, with `localStorage` demoted to a cache; `Accept-Language` sent on every request | Contract §2.10; the choice follows the user across devices |
| **I3.5** | Localization sweep — every screen in `zh-CN` and `en`: no missing keys, no server-supplied localized enum, no empty dictionary name, dates and numbers formatted per locale while the wire stays canonical | Acceptance scenario §11.12 |
| **I3.6** | Notification rendering from `templateCode` (render-on-read), so switching language re-renders history correctly | B2.6 + B5.4 |
| **I3.7** | End-to-end acceptance — all twelve `APIContract.md` §11 scenarios run against the real stack, in both languages, on mobile and desktop | Recorded pass/fail per scenario in `00-Status.md` |
| **I3.8** | **Historical ledger import rehearsal** — real (anonymized) school data through validate → error report → correction → commit, with balances reconciled against the paper ledger afterwards | R6; the rehearsal, not the mechanism, is what de-risks launch day |
| **I3.9** | **UAT with real users** — teachers running review, students submitting records, an administrator running reports and an export; observed, not surveyed | Findings triaged into fix-before-launch and post-launch |
| **I3.10** | Production readiness — production data seeded (students, staff, classes, categories, rules, hour requirements), roles assigned per the school's actual structure, feature flags set, school info configured | D8 guardrail: a new school is a data-seeding exercise |
| **I3.11** | Cutover plan — go-live date at a **term boundary**, communications to students and staff, a support channel for the first two weeks, and a documented rollback position (paper process resumes; imported data re-importable) | Mid-term cutover collides with in-flight records and reviews |
| **I3.12** | Post-launch watch — first-week monitoring of ledger reconciliation, review SLA, error rates, upload rejection rate, and login failures, with a named person watching each | B6.11's alerts need an audience in week one |

**I3.1-I3.5 status (2026-08-05/06, `00-Status.md` §24/§26):** I3.1 was actually built during I2 hardening, not this phase (found while grep-sweeping for orphaned `client.ts` methods — the "evidence" step had no `<input type="file">` at all). I3.2-I3.5 done as described in the exit criteria below.

**I3.6 status (2026-08-06, `00-Status.md` §27): done, live-verified.** Originally deferred in an earlier pass of this doc as needing a `params` column (assumed interpolated templates); re-examined and that assumption was wrong — `NotificationTemplate` has always been 100% static per language (`titleZh`/`titleEn`/`bodyZh`/`bodyEn`, no interpolation ever implemented), so `templateCode` alone is sufficient to re-render on read. Fixed as a schema **drop**, not an addition: `V12__notification_render_on_read.sql` removes the `title`/`body` columns from `notification` entirely; `NotificationServiceImpl.notify()` now stores only `templateCode` + resource refs (no rendering at write time), and `listForUser()`/`markRead()` render fresh via `NotificationTemplateService.render(templateCode, resolvedLanguage)` on every read, using the *reader's* resolved language (from `Accept-Language` + `preferredLanguage`, the same `RequestLanguageService.resolve()` pattern already used by `CaseController`/`ProposalController`) rather than the language in effect when the notification was created. Read path wraps `render()` in try/catch with an empty-body fallback so one bad/renamed `templateCode` on a historical row can't 500 a whole notification list. No frontend change needed — `NotificationDto`'s wire shape is unchanged, only the *values* of `title`/`body` are now correct per-request rather than frozen at creation time. Live-verified end-to-end over real HTTP against the deployed server: the same notification row (same `id`, same `templateCode: "case.resolved"`) returned `"title":"Case Resolved"` with `Accept-Language: en` and `"title":"申诉/补交已处理"` with `Accept-Language: zh-CN` in the same request cycle. Covered by a new IT test, `NotificationReadPathIT.sameNotificationRendersInWhicheverLanguageTheReaderRequests()`, exercising the real HTTP `Accept-Language` path (not just the service layer, since language resolution happens in the controller). Disposable test data (`usr_i36_*`, one student, one case, one notification) created for live verification, cleaned up afterward.

**Terminology correction, 2026-08-06 (prompted by a written review, `Docs/clarification/01-i3-partner-school-terminology.md`): "partner school" was a sloppy catch-all this doc used for three genuinely different things, and that conflation was actively misleading — it made real, buildable work look infrastructure-blocked.** The three concepts, kept distinct from here on:

- **Target deployment school** — the actual school that will run TimeBank: configures its own students/teachers, runs UAT, sets a go-live date. This is the thing I3.8-I3.12 are really about, and the one genuine external dependency in this phase.
- **Partner organization** (`Design_CN.md` §5.3's 合作组织, contract §3.2's `organizationId`/`externalContact`) — the off-campus org (or on-campus club) a *student's own event proposal* names as a collaborator. Selected from existing `GET /organizations` records, or (by design, though not yet fully wired — see below) entered as a new name for the backend to create. This has never required a second school; it's ordinary proposal content, on the same tier as the proposal's title or planned dates.
- **"Partner school"** — not a concept this project's design or contract ever defines. Every place this doc previously used the phrase meant one of the two things above; neither needs a second school as a precondition.

**I3.7 does not need a target deployment school and was wrongly grouped with I3.8-I3.12; corrected 2026-08-06.** Its deliverable — recording pass/fail for the twelve `APIContract.md` §11 scenarios against the real stack, in both languages, on mobile and desktop — needs disposable test accounts driving the real UI, not real students/teachers or real historical data; it's the same live-verification technique already used throughout I0-I3.6 (`00-Status.md` §6 has the current per-scenario status). It is genuinely buildable now, unlike the institutional-acceptance half of I3.8-I3.12 below.

As of 2026-08-06 (`00-Status.md` §28), 8 of 12 scenarios are `✅` (including #8, teacher out-of-scope access, closed that pass — `cases` already had a dedicated test, `serviceRecords`/`accounts`/`eventProposals` did not and now do, all four passing against real MySQL), 1 is trivially true (#11, no WeChat/IM dependency exists anywhere in the codebase), and 3 remain open: #1, #4, #12.

**Scenario #1's remaining scope is bigger than previously written down.** The row only ever tracked "does approval generate a project" — proven — and, since §25.1, "does the admin review screen work" — proven for `approve`, not yet for `requestChanges`→resubmit. The same review that fixed the terminology above (`Docs/clarification/01-i3-partner-school-terminology.md`) pointed out a second, more important gap that was never in this row at all: `Design_CN.md` §5.3's step 3 ("组织安排") requires a proposal to carry co-organizers, a proposed supervisor, and a partner organization + contact — and the real create/update path does not correctly capture any of the three:

- `frontend/src/app/api/client.ts` hardcodes `coOrganizerStudentIds: []` on every real create/update call — the form's own `coOrganizers` free-text field is never actually sent to the backend at all.
- `proposedSupervisorId` (contract-required, `@NotBlank` on `CreateProposalRequest`) is populated from a plain text input (`mentorTeacher`) with no resolution to a real user id — the backend stores whatever string arrives, unvalidated. Named once before, in I2 hardening (`00-Status.md`), as found-but-not-fixed; still open.
- `organizationId` is populated from a single plain text input (`partnerOrg`) with no way to either search existing organizations or signal "this is a new one" — there is no `newOrganizationName`-shaped field anywhere in the contract, and `POST /organizations` requires `organizations.manage`, a permission students don't hold, so a student genuinely cannot create a new partner organization themselves under the current contract even if the frontend tried to call it directly.

None of this is a "no partner school" problem — it's a proposal-domain data-integrity gap, the same class of thing I2's independent review found and fixed elsewhere (event-proposal date range, project grade ids). **Scenario #1 should not be marked `✅` on the strength of "a project gets generated" alone** — the generated project's organizational fields need to actually reflect what the student entered, which they currently don't. Fixing this needs: a frontend fix to send the real `coOrganizerStudentIds`; a supervisor picker/search resolving to a real teacher id instead of free text; and a contract + backend change letting a proposal either reference an existing `organizationId` or supply a new organization's name for the backend to create and link within the same transaction (mirroring how proposal approval already creates a project transactionally, contract §3.2). None of this has been implemented yet — found and named here, not fixed, consistent with this project's standing practice for gaps discovered outside the pass that's actively fixing something else.

**I3.8-I3.12 are not uniformly blocked, and none of them are blocked on anything called a "partner school."** Each has a genuinely buildable **engineering-readiness** half — tooling, scripts, templates, rehearsals against representative synthetic data — and a separate **institutional-acceptance** half that does need the target deployment school's own data, people, or a real go-live date. Collapsing both into one "blocked" status (the previous version of this section) hid work that could start now:

| Item | Engineering readiness (buildable now, no school needed) | Institutional acceptance (needs the target deployment school) |
| --- | --- | --- |
| **I3.8** Historical ledger import rehearsal | Full validate→error-report→correct→commit→rollback cycle against representative synthetic data covering ordinary rows, duplicates, invalid rows, and corrections; acceptance scenario #7 | Reconciling an actual anonymized historical ledger to actual paper records — needs the school's real historical data to exist and be handed over |
| **I3.9** UAT with real users | Task scripts, an observation rubric, and a fix-before-launch/post-launch triage rule, rehearsed internally first | Real teachers/students/an administrator actually exercising the scripts and producing real findings — needs the school's own people and their real judgment calls |
| **I3.10** Production readiness | The seeding tooling and checklist (students, staff, classes, categories, rules, hour requirements, feature flags, school info) proven repeatable against representative data | The specific final roster, org structure, and configuration values — needs the school's actual data |
| **I3.11** Cutover plan | The term-boundary cutover template, communications templates, support-channel plan, and rollback procedure, fully drafted | The actual go-live date, named responsible people, and the school's written sign-off |
| **I3.12** Post-launch watch | Monitoring items, thresholds, a dashboard, and a response runbook, configured and rehearsed against synthetic alerts | Actually staffing the watch — inherently starts only after a real go-live date exists |

This table's right-hand column is the genuine, narrow dependency this phase has always had; the left-hand column is work this project can and should pick up now, the same way I3.1-I3.7 already have been.

## 3. Exit Criteria — Gate G5

**Revised 2026-08-06 (`Docs/clarification/01-i3-partner-school-terminology.md`) into three tiers, replacing one flat checklist.** The previous flat list read as though every item waits on the same thing; it doesn't. Tier 1 is deployment-readiness — provably done against real infrastructure and representative data, no target deployment school required. Tier 2 is what actually needs that school's own data, people, and approval before go-live. Tier 3 only starts once go-live has already happened, so it was never really a precondition for reaching go-live in the first place (flagged as a standing inconsistency in the old list, corrected here) — G5 governs whether go-live may *begin*, not whether week one goes well.

**Tier 1 — deployment readiness (buildable now, no school required):**

- [x] A student uploads evidence, submits a record, and a teacher approves it — **the upload+submit+evidence-display half live-verified 2026-08-05/06** (`00-Status.md` §24, §26); "on a phone" and "against production configuration" specifically not yet done (mobile-viewport spot-check only, no production deployment exists).
- [x] Upload rejection, interruption, and retry all behave predictably and explain themselves — I3.2, live-verified: a real scan-rejected file (`MIME_MISMATCH`) now surfaces a specific, real toast instead of silently appearing to succeed (`00-Status.md` §26). "Interrupted" (network drop mid-PUT) not separately tested.
- [x] Evidence URLs are short-lived, permission-checked, and never appear in logs or client state — I3.3, built and live-verified: `EvidenceLink` fetches `GET /files/{id}` fresh on every click, never persists `downloadUrl` in component state (`00-Status.md` §26).
- [ ] Every screen passes the localization sweep in both languages — I3.5's *static* half done (every `t()` key, including dynamic/template-literal ones, now resolves in both `en.json`/`zh.json` — found and fixed 8 real gaps including `common.unknownError`, silently broken on ~23 call sites; `00-Status.md` §26). Not done: dates/numbers formatted per-locale, a full manual click-through sweep of every screen (acceptance scenario #12).
- [ ] All twelve §11 acceptance scenarios pass, recorded individually — **8 of 12 done as of 2026-08-06** (`00-Status.md` §6, §28); open: #1 (blocked on the proposal organizational-fields gap named above, not just UI wiring), #4 (return→resubmit→approve chain never driven through the real UI), #12 (bilingual/responsive click-through).
- [ ] The import mechanism is rehearsed end to end against representative synthetic data (ordinary rows, duplicates, invalid rows, corrections, rollback by batch id) — I3.8's engineering-readiness half, table above.
- [ ] The production-seeding tooling is proven repeatable against representative data (students, staff, classes, categories, rules, hour requirements, feature flags, school info) — I3.10's engineering-readiness half.
- [ ] The cutover template (term-boundary timing, communications, support channel, rollback procedure) and the post-launch monitoring/runbook are drafted and rehearsed against synthetic conditions — I3.11/I3.12's engineering-readiness halves.
- [ ] G3 (restore drill) and G4 (load test) are green — these do not need a target deployment school either; both are blocked on infrastructure this project doesn't have yet (a second host, object storage, a production-sized staging environment — `00-Status.md` §20), an entirely separate, already-documented dependency.

**Tier 2 — institutional acceptance (needs the target deployment school specifically):**

- [ ] The import rehearsal reconciles to the school's actual paper ledger (if the school has historical data to import at all — some schools may not, in which case this item is marked not-applicable rather than blocked), and discrepancies are explained rather than absorbed.
- [ ] UAT findings from the school's own teachers, students, and an administrator are triaged, with fix-before-launch items closed.
- [ ] Production data reflects the school's actual roster, org structure, and configuration, and roles are assigned to real people.
- [ ] The cutover plan is agreed with the school in writing, including the actual go-live date and named responsible people.

**Tier 3 — post-launch (starts only once go-live has happened, not a precondition for reaching it):**

- [ ] I3.12's first-week watch is actually staffed and executed.

## 4. Risks and Watch-Outs

- **Upload is the largest untested surface in the frontend.** It is also the flow most likely to be used on a school network from a phone with a poor connection. Test on real devices, not on a laptop on office wifi.
- **UAT that only surfaces cosmetic feedback was not real UAT.** Give teachers a genuine review queue with anomalies and ambiguous cases, not five clean records.
- **The import is the one irreversible-feeling step at launch.** It is reversible by batch id — verify that in the rehearsal so the school knows it, rather than discovering it under pressure.
- **Go-live at a term boundary.** This is a schedule constraint disguised as a preference: mid-term cutover means migrating in-flight records and half-completed reviews.
- **The first two weeks decide institutional trust.** A ledger discrepancy in week one is remembered for years (`01-TimeBankDesign.md` §16). Staff the watch.

## 5. Hand-Off

Operation transfers to the school under B7.14's runbooks. Remaining backlog: the Chinese counterpart of `Docs/backend/Design.md`, post-launch UAT items, and — only then — Phase 3 planning.
