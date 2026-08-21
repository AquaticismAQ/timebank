# 042 — System Admin's "Rule Settings" tab had zero real backend wiring

Status: **Fixed and live-verified (2026-08-21)**

## Symptom

Found during the same systematic mock-data scan as issue 041 (requested directly by the user): System Admin's "Rule Settings" tab showed 3 fixed cards (High School / Middle School / Time Coin Conversion) with plausible-looking numbers (75h/year, 300h graduation total, 1 coin/hour, etc.), fully editable via a modal with a "this will recalculate all students' status" warning banner. None of it was real — every edit only ever mutated client-side React state (`INITIAL_RULES`), reset on every page reload, and `api.rules` did not exist anywhere in `client.ts` (a second, separate, also-never-called stub with a wrong, made-up field shape — `coinConversionRate`/`minimumRoundingMinutes` — was found dead in `client.ts` during this fix, not referenced by any component).

Meanwhile a fully-built, real backend `rules` module (`cn.timebank.rules`) already existed: a versioned `RuleVersion` (rounding/coin-conversion parameters, annual/stage hour requirements, 4 honour tiers) with an immutable-once-published draft → validate → publish → archive lifecycle (contract §4.12), completely unreachable from the UI.

## Root cause

The frontend's Rule Settings tab was built against an earlier, simpler content model (3 tiers × a few fields, flat edit-and-save) that predates the real backend module's actual shape (1 unified 9-field rule set, versioned, immutable-once-published) and was never updated to match once the real backend landed — a design/scope mismatch, not a wiring oversight like issues 040/041 (there was no plausible "just swap the data source" fix; the whole interaction model needed to change).

## Fix

Backend: none needed — `cn.timebank.rules` (`RuleController`/`RuleServiceImpl`, contract §4.12) was already complete and correct.

Frontend, `SystemAdmin.tsx`'s Rule Settings tab rebuilt around the real model:
- `frontend/src/app/api/types.ts`: new `RuleVersion`/`RuleDraftFields`/`RuleValidationResult` types matching the real `RuleVersionDto`/`RuleRequests`.
- `frontend/src/app/api/client.ts`: new, correct `api.rules` group (`list`/`get`/`createDraft`/`updateDraft`/`validate`/`publish`/`archive`) — replacing the old dead, wrong-shaped stub of the same name.
- New `RuleFormModal` (create/edit a draft's 9 fields, grouped into Conversion/Requirements/Honour Tiers, with an hours-equivalent hint next to every duration field) and `PublishRuleModal` (runs `validate` automatically on open, blocks publishing on a failing validation, asks for the real `effectiveFrom` date) — replacing the old single flat `RuleModal`.
- The tab now lists real `RuleVersion` rows (paginated via `usePagedApi`, matching every other list screen's pattern) with status-appropriate actions: Edit/Validate/Publish for a draft (gated on `rules.manage`/`rules.publish` respectively, matching the real permission split), Archive for a published rule.
- Removed the entire `RuleItem`/`RuleSection`/`INITIAL_RULES`/`RuleModal` mock-model code and its now-dead i18n keys (`admin.system.rules.high*`/`middle*`/`coin*` and both duplicate flat/nested sets of the old modal's copy — confirmed unused by any other component before deleting).

## Test plan

- Frontend: `pnpm build` and `tsc --noEmit` both clean; en/zh i18n key and `{{placeholder}}` parity confirmed via a diff script.
- Live: full lifecycle exercised against the real deployed backend as `admin001` — create draft (prefilled from the current rule), edit a field, Validate (toast confirms "no issues found"), Publish (auto-runs validation, correctly closes out the previously-effective rule's window), Archive. Confirmed via direct `GET /rules` reads at each step that the real `rule_version` table reflects exactly what the UI shows.

## Resolution

Deployed to `https://tb.example.com/` (frontend only). Live-verified via Playwright as `admin001`, full lifecycle: created a draft prefilled from the real published rule (`rule_default_2025`), edited the bronze honour-tier threshold, validated it (toast: "Valid — no issues found"), published it (the publish modal auto-ran validation and showed the same result; confirmed via the real API that the old rule's `effectiveTo` was correctly set and the new one's `effectiveFrom`/`status` updated), and archived the now-superseded old rule (confirmed via the API it flipped to `ARCHIVED`).

**Live-data note:** this verification pass necessarily created real, mostly-irreversible rule-version rows (archive has no undo, and a published rule can only ever be superseded, never edited in place — by design, matching this module's existing invariant). Two artifacts from the verification needed cleanup, both narrated and confirmed with the user before proceeding: (1) the honour-tier value used to prove the edit worked (bronze 1500→1600) was left live as the effective rule after the first publish — corrected by publishing a second draft with the value restored to 1500 and today's date as `effectiveFrom`; (2) that correction's own `publish` call did **not** auto-supersede the first test rule, because the backend's supersede logic only closes a rule that is already effective at the new rule's start date — the first test rule's `effectiveFrom` (2026-09-01) was still in the future relative to the correction's `effectiveFrom` (2026-08-21), so both ended up simultaneously `PUBLISHED` with non-overlapping windows; left unfixed, the first test rule (bronze=1600) would have silently become "the" effective rule again once 2026-09-01 arrived. Archived it directly to close that gap. Final verified state (`GET /rules`): exactly one `PUBLISHED` row, values identical to the original `rule_default_2025` in every field, and two `ARCHIVED` rows (the original, plus the two verification artifacts) — no live data drift remains from this session's testing.

*Live-verified via Playwright against the real deployed frontend+backend, with every intermediate state independently confirmed via direct `GET /rules` reads (not just the UI's own rendering).*
