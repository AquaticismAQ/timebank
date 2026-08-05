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

**I3.8-I3.12 are out of scope for this stage and unstarted, by design, not oversight** — every one of them needs a real partner school (UAT with real teachers/students, historical data to rehearse the import against, an actual go-live date to cut over to). There is no partner school yet, so none of these can be attempted, simulated, or rehearsed with synthetic data without the exercise being fiction. This is the same category of blocker as B7's infrastructure gaps (named, not silently skipped) — see `00-Status.md` for the standing status.

**I3.7 was previously grouped with I3.8-I3.12 above; that was wrong, corrected 2026-08-06.** Its deliverable — recording pass/fail for the twelve `APIContract.md` §11 scenarios against the real stack, in both languages, on mobile and desktop — needs disposable test accounts driving the real UI, not real students/teachers or real historical data; it's the same live-verification technique already used throughout I0-I3.6 (`00-Status.md` §6 has the current per-scenario status). It is genuinely buildable now, unlike I3.8-I3.12.

As of 2026-08-06 (`00-Status.md` §28), 8 of 12 scenarios are `✅` (including #8, teacher out-of-scope access, closed this pass — `cases` already had a dedicated test, `serviceRecords`/`accounts`/`eventProposals` did not and now do, all four passing against real MySQL), 1 is trivially true (#11, no WeChat/IM dependency exists anywhere in the codebase), and 3 remain open: #1 (changes-requested→resubmit→approve chain never driven through the real UI), #4 (return→resubmit→approve likewise — proven at the backend only), and #12 (a full bilingual, mobile+desktop click-through — `zh-CN` is spot-checked, `en` never separately re-verified live). Building toward I3.1-I3.6 (the rest of the "buildable engineering half") is complete; I3.7's remaining three scenarios and I3.8-I3.12 resume once picked up / once a partner school is engaged, respectively.

## 3. Exit Criteria — Gate G5

- [x] A student uploads evidence, submits a record, and a teacher approves it — **the upload+submit+evidence-display half live-verified 2026-08-05/06** (`00-Status.md` §24, §26); "on a phone" and "against production configuration" specifically not yet done (mobile-viewport spot-check only, no production deployment exists).
- [x] Upload rejection, interruption, and retry all behave predictably and explain themselves — I3.2, live-verified: a real scan-rejected file (`MIME_MISMATCH`) now surfaces a specific, real toast instead of silently appearing to succeed (`00-Status.md` §26). "Interrupted" (network drop mid-PUT) not separately tested.
- [x] Evidence URLs are short-lived, permission-checked, and never appear in logs or client state — I3.3, built and live-verified: `EvidenceLink` fetches `GET /files/{id}` fresh on every click, never persists `downloadUrl` in component state (`00-Status.md` §26).
- [ ] Every screen passes the localization sweep in both languages — I3.5's *static* half done (every `t()` key, including dynamic/template-literal ones, now resolves in both `en.json`/`zh.json` — found and fixed 8 real gaps including `common.unknownError`, silently broken on ~23 call sites; `00-Status.md` §26). Not done: dates/numbers formatted per-locale, a full manual click-through sweep of every screen.
- [ ] All twelve §11 acceptance scenarios pass, recorded individually.
- [ ] The import rehearsal reconciles to the paper ledger, and discrepancies are explained rather than absorbed.
- [ ] UAT findings are triaged, with fix-before-launch items closed.
- [ ] Production data is seeded and roles are assigned to real people.
- [ ] The cutover plan — including rollback — is agreed with the school in writing.
- [ ] G3 (restore drill) and G4 (load test) are green; go-live does not proceed without them.

## 4. Risks and Watch-Outs

- **Upload is the largest untested surface in the frontend.** It is also the flow most likely to be used on a school network from a phone with a poor connection. Test on real devices, not on a laptop on office wifi.
- **UAT that only surfaces cosmetic feedback was not real UAT.** Give teachers a genuine review queue with anomalies and ambiguous cases, not five clean records.
- **The import is the one irreversible-feeling step at launch.** It is reversible by batch id — verify that in the rehearsal so the school knows it, rather than discovering it under pressure.
- **Go-live at a term boundary.** This is a schedule constraint disguised as a preference: mid-term cutover means migrating in-flight records and half-completed reviews.
- **The first two weeks decide institutional trust.** A ledger discrepancy in week one is remembered for years (`01-TimeBankDesign.md` §16). Staff the watch.

## 5. Hand-Off

Operation transfers to the school under B7.14's runbooks. Remaining backlog: the Chinese counterpart of `Docs/backend/Design.md`, post-launch UAT items, and — only then — Phase 3 planning.
