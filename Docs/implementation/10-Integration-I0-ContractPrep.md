# I0 · Contract Preparation

> Phase I0 of `01-Overview.md`. The work that must happen **before** any real call is made: freeze what the backend must accept, lift what the backend must return, and build the HTTP layer the mock client was designed to be replaced by.

| | |
| --- | --- |
| **Depends on** | B0.3–B0.6 (envelope and list primitives fixed) |
| **Blocks** | I1, I2, I3. **Gate G1** |
| **Effort / calendar** | 3 pw (≈2 frontend, ≈1 backend) · W4–W6 |
| **Design refs** | `Design.md` D10; `APIContract.md` §10.1, §12, §13 |

---

## 1. Goal

Close the two gaps identified in `APIContract.md` §13.2 while they are still cheap: the **write surface has never been exercised** (no request body has ever been sent), and the response types live inside the mock file rather than in the API layer.

## 2. Scope

**In:** types module extraction, request-body freeze, HTTP transport layer, per-domain mock/real toggle, the four contract divergences that require frontend changes, development environment plumbing.

**Out:** swapping any actual call (I1), wiring any mutation to a real endpoint (I2). I0 builds the machinery; it changes no screen's behaviour.

## 3. Work Items

| ID | Item | Deliverable |
| --- | --- | --- |
| **I0.1** | **Lift the types** — move the payload interfaces from `frontend/src/app/mock/data.ts` into `frontend/src/app/api/types.ts` **unchanged**; `mock/data.ts` keeps fixtures only; components and the client import types from the API layer | Deleting the fixtures later removes no type information (`APIContract.md` §12.3) |
| **I0.2** | **Freeze the request bodies** — joint backend/frontend review of `APIContract.md` §13.4, endpoint by endpoint, resolving every open question about required fields, error codes, and reason-code vocabularies | The G1 sign-off artifact. This is the single highest-value hour of the phase |
| **I0.3** | HTTP transport — a `fetch` wrapper handling base URL, `credentials: 'include'`, CSRF token, `Accept-Language`, and JSON encoding | One place, not per method |
| **I0.4** | Envelope handling — unwrap `{data}`, surface `{page}` where a screen paginates, map `{error}` to a typed `ApiError` carrying `code`, `message`, `fieldErrors`, and `requestId` | The mock's bare arrays become the exception, not the shape |
| **I0.5** | Error-to-UI mapping — `422` → per-field form errors, `409` → conflict prompt, `403`/`404` → not-available state, `429` → retry guidance, `5xx` → generic failure. **Never render a raw error object or log one** (contract §9) | A shared handler; screens opt out only deliberately |
| **I0.6** | Idempotency and concurrency helpers — generate and attach `Idempotency-Key` for record creation, review decisions, account adjustments, and import commits; attach `If-Match`/`version` on updates and retain the server's current version from a `409` | Contract §2.8; built once so I2 cannot forget it per screen |
| **I0.7** | **Per-domain toggle** — `VITE_API_BASE_URL` plus a per-domain switch (`projects: real, records: mock`), so domains swap independently as backend phases land, with the mock as fallback | This is what makes I1 and I2 incremental rather than a big-bang cutover |
| **I0.8** | Divergence 1 — rename proposal status `needsRevision` → `changesRequested` and add `underReview` (contract §3.2) | Backend owns status machines; the frontend follows |
| **I0.9** | Divergence 2–3 — coin amounts become integer **centi-coins** with a single display formatter; `Account`, `AccountTransaction`, `StudentProgress`, and `Student` stop carrying decimal coin values | Closes R8 before integration rather than during it |
| **I0.10** | Divergence 4 — remove the duplicated `*Hours` fields from the wire types; hours are derived from minutes in a shared formatter | One canonical unit per quantity |
| **I0.11** | Divergence 5 — category and dictionary names come from the server; the fixtures stop embedding i18n keys (`mock.categories.*`), and components key off `categoryId` | Contract §2.10; prepares for B2's localized dictionaries |
| **I0.12** | Dev environment — Vite proxy or CORS configuration, cookie behaviour when the SPA runs locally against the **remote test server** (cross-origin `SameSite` and `Secure` implications over HTTPS), and a documented "point the frontend at the test server" recipe | One documented command, and the cookie/CSRF path works from a developer machine |

## 4. Exit Criteria — Gate G1

- [x] `types.ts` exists in the API layer; no component imports types from `mock/`.
- [x] The frontend still builds and every screen still works **entirely on mocks** — I0 changes no behaviour. (Verified via `pnpm build`, a standalone `tsc --noEmit`, and a Playwright click-through of both portals in default/mock config — see `00-Status.md` §21.)
- [x] Request bodies for every §13.4 endpoint are reviewed and signed off by both sides, and the contract is updated where the review changed anything. (Found and fixed real drift, not just doc gaps — see `00-Status.md` §21.)
- [x] A single call can be pointed at a real backend by flipping one domain's toggle.
- [x] Envelope unwrapping, typed errors, idempotency keys, and concurrency are exercised by at least one call against B1's `/me`. (Concurrency is body-field `version`, never `If-Match` — the real backend never reads that header; contract corrected to match, see `00-Status.md` §21.)
- [x] Statuses, coin units, hour derivation, and dictionary naming match the contract.
- [x] A developer following the written recipe reaches a working SPA-against-test-server session in one attempt, including login cookies and CSRF. (Proven live via Playwright through the dev proxy with a disposable test user, then deleted — see `00-Status.md` §21 for two proxy-specific fixes the recipe needed beyond `changeOrigin`.)

## 5. Risks and Watch-Outs

- **I0.2 is the phase.** Everything else here is mechanical; the request-body freeze is the item that prevents R12 from becoming schedule damage in I2. Do not let it become a document review done alone — it needs both sides in the same conversation.
- **Big-bang swap temptation.** Without I0.7, integration becomes one enormous cutover where nothing works until everything works. The toggle costs an afternoon.
- **Unit changes look trivial and are not.** A missed centi-coin conversion renders a balance 100× wrong in a way that looks plausible on small numbers. Convert in exactly one formatter, and grep for direct balance rendering afterwards.
- **Do not "improve" screens while touching them.** The UI is built and reviewed; I0 through I3 wire it up. Feature changes belong in a separate stream with their own review.

## 6. Hand-Off

I1 can begin the moment B1 and B2 expose their first endpoints, swapping domain by domain with a working fallback behind every toggle.
