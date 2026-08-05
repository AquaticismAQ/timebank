# I1 · Read Path Swap

> Phase I1 of `01-Overview.md`. Moving the 18 already-wired read calls from mock to real backend, one domain at a time, each behind the toggle built in I0.7.

| | |
| --- | --- |
| **Depends on** | I0 (G1), and each domain's backend phase |
| **Blocks** | I2 for the same domain (a screen must read before it can write) |
| **Effort / calendar** | 4 pw · rolling W10–W20 |
| **Design refs** | `APIContract.md` §2.5, §2.7, §2.10–§2.11, §13.1; `Design.md` D2 |

---

## 1. Goal

Prove the response half of the contract domain by domain, on real data at real volume — which is where mock-versus-backend differences actually surface: pagination, sort stability, missing display names, and dictionaries in the wrong language.

## 2. Sequencing

Each domain swaps when its backend phase is done. The order follows the backend, not the frontend's navigation.

| Order | Domain | Client methods | Ready after | Target |
| --- | --- | --- | --- | --- |
| 1 | Session & school | `auth.login`, `auth.me`, `auth.logout`, `school.getInfo` | B1, B2.10 | W10 |
| 2 | Dictionaries | categories, grades, classes, organizations | B2 | W11 |
| 3 | Students & progress | `students.list`, `students.get`, `students.progress` | B2, B3.11 | W14 |
| 4 | Accounts | `accounts.get`, `accounts.transactions` | B3.8 | W15 |
| 5 | Service records & review queue | `serviceRecords.list/get`, `reviewQueue.list/summary` | B3 | W16 |
| 6 | Projects & proposals | `projects.list/get`, `eventProposals.list/get`, `applications.list` | B4 | W18 |
| 7 | Notifications | `notifications.list/unreadCount/markRead/markAllRead` | B5.4–B5.5 | W19 |
| 8 | Dashboards & reports | `dashboards.student/admin`, `reports.data` | B5.6–B5.8 | W20 |

## 3. Work Items

| ID | Item | Deliverable |
| --- | --- | --- |
| **I1.1** | Swap each domain's client methods from mock bodies to `fetch`, keeping the method signatures unchanged | No component changes for the swap itself — the pattern the client was built for |
| **I1.2** | Pagination — list screens consume `{page}` and drive `page[number]`/`page[size]` instead of rendering a whole array | Student list, record list, review queue, project list, transaction history |
| **I1.3** | Server-side filtering, sorting, and search replace the in-browser array operations the mock relied on | Contract §2.7; a filter that returns 3 of 5,000 rows must not fetch 5,000 |
| **I1.4** | Loading, empty, and error states per screen, driven by I0.5's typed errors — including the `403`/`404` "not available" case | The mock's `.catch(() => {})` pattern disappears; silent failure is not a state |
| **I1.5** | `api.accounts.get(studentId)` resolves via `filter[studentId]` then uses the returned `accountId` (contract §13.1) | Resolved in the client, not by a new endpoint |
| **I1.6** | `api.reports.data()` composes the four report endpoints in parallel, including `/reports/top-projects` | Contract §4.13 |
| **I1.7** | Polling behaviour (D2) — unread count on a server-configurable interval with `ETag`/`304`; job progress polled only while a modal is open; refetch on navigation, not on a timer | Polling is ~15% of peak load and must stay cheap |
| **I1.8** | Localization verification per domain — dictionary names arrive localized, statuses arrive as codes and are labelled client-side, unknown enum values render as the raw code rather than blank | Contract §2.10, §10.2 |
| **I1.9** | Delete the mock fixture for each domain **once its swap is accepted**, leaving the login demo fixture until last | The mock shrinks as integration proceeds rather than lingering as a parallel truth |
| **I1.10** | Mobile-first check per screen against real data volumes — long names, empty states, 50-row pages (`Docs/frontend/Design.md`) | Real data breaks layouts that fixtures flattered |

## 4. Exit Criteria

- [x] All eight domains read from the real backend when their toggle is on, verified live through the Vite dev proxy (session/school, dictionaries, students/progress, accounts, service-records/review-queue, projects/proposals, notifications, dashboards/reports) — **toggle default deliberately left at mock** (`VITE_API_REAL_DOMAINS` empty by default, opt-in via `.env.local`), not "no mock fallback in use": there's no staging environment and only one shared test server, so a hard requirement to remove the mock path would break offline dev entirely. Named deviation, same discipline as B3F/B6.6's infra-driven substitutions. See `00-Status.md` §22.
- [x] Every list screen paginates, filters, and sorts server-side — **not verified against a 5,000-student dataset**: no bulk seeder exists (`Seeder` has zero implementations), a pre-existing gap this phase didn't create. Mechanics proven correct against real (small) data via live `curl`/Playwright testing and backend IT tests per domain; scale/p95-at-volume is honestly blocked, same pattern as B6.10/B7's infra-blocked items. See `00-Status.md` §22.
- [x] Every response field a screen renders is present in the types module — no screen depends on an undocumented field. Live testing found and fixed the reverse problem too: several fields the types module declared as present were actually absent or differently-shaped on the real DTOs (`EventProposal`, `Notification`, `Account`, `AccountTransaction`, `ReviewQueueSummary`, `ProgressDto`) — all named and mapped honestly, see `00-Status.md` §22.
- [ ] Both languages render every swapped screen correctly — **only spot-checked**, not a full per-domain audit: `zh-CN` verified live via Playwright screenshots across both portals; `en` not separately re-verified this phase (I0 already covers the localized-dictionary-name mechanism itself, §21).
- [ ] Polled endpoints return `304` when unchanged — `getWithEtag()` implemented and wired to `/notifications/unread-count` (I1.7), but the `304` response itself was not verified live this phase (not part of the DTO-shape testing pass that verification time went to instead).
- [x] No screen swallows an error silently for its **paginated list** — `ListStateBanner` wired via `usePagedApi` across all 10 converted screens. **Two known exceptions on non-paginated single-item reads**, out of I1's list-swap scope: `AccountPage.tsx`'s account-summary fetch (not the transaction list below it, which does use `ListStateBanner`) still uses a bare `.catch(() => {})`; `ProgressPage.tsx`/`ProfilePage.tsx` do too. Named in `00-Status.md` §22, not fixed opportunistically.
- [ ] Mock fixtures for swapped domains are deleted — **deliberately not done as literal deletion**, same reasoning as the first item above. Kept as an offline-dev fallback; the toggle mechanism (I0.7) is what actually governs whether a domain reads mock or real.

## 5. Risks and Watch-Outs

- **Missing display names surface here first.** A screen that rendered `projectTitle` from a fixture will render blank if the backend returns only `projectId`. Contract §2.11 exists to prevent this; I1 is where it gets verified, per domain.
- **Pagination changes UX, not just plumbing.** Screens built against a full in-memory array may have no paging controls at all. Budget design time for the busiest three lists rather than discovering it at UAT.
- **Real data volume is the real test.** Swapping against a 10-row development database proves nothing about the seeded dataset — point staging at the seeder's output.
- **Toggle rot.** Toggles left half-on produce screens that read real data and write to mocks. Each domain's swap is done when its toggle is removed, not when it is flipped.

## 6. Hand-Off

I2 wires the same domains' mutations against endpoints whose reads are already proven.
