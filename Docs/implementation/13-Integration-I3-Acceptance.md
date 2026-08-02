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
| **I3.6** | Notification rendering from `templateCode` + params, so switching language re-renders history correctly | B2.6 + B5.4 |
| **I3.7** | End-to-end acceptance — all twelve `APIContract.md` §11 scenarios run against the real stack, in both languages, on mobile and desktop | Recorded pass/fail per scenario in `00-Status.md` |
| **I3.8** | **Historical ledger import rehearsal** — real (anonymized) school data through validate → error report → correction → commit, with balances reconciled against the paper ledger afterwards | R6; the rehearsal, not the mechanism, is what de-risks launch day |
| **I3.9** | **UAT with real users** — teachers running review, students submitting records, an administrator running reports and an export; observed, not surveyed | Findings triaged into fix-before-launch and post-launch |
| **I3.10** | Production readiness — production data seeded (students, staff, classes, categories, rules, hour requirements), roles assigned per the school's actual structure, feature flags set, school info configured | D8 guardrail: a new school is a data-seeding exercise |
| **I3.11** | Cutover plan — go-live date at a **term boundary**, communications to students and staff, a support channel for the first two weeks, and a documented rollback position (paper process resumes; imported data re-importable) | Mid-term cutover collides with in-flight records and reviews |
| **I3.12** | Post-launch watch — first-week monitoring of ledger reconciliation, review SLA, error rates, upload rejection rate, and login failures, with a named person watching each | B6.11's alerts need an audience in week one |

## 3. Exit Criteria — Gate G5

- [ ] A student uploads evidence, submits a record, and a teacher approves it — on a phone, in Chinese, against production configuration.
- [ ] Upload rejection, interruption, and retry all behave predictably and explain themselves.
- [ ] Evidence URLs are short-lived, permission-checked, and never appear in logs or client state.
- [ ] Every screen passes the localization sweep in both languages.
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
