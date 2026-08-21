# 043 — Rule requirements had no middle/high school split, and duration fields displayed minutes-primary instead of hours-primary

Status: **Fixed and live-verified (2026-08-21)**

## Symptom

Reported directly by the user while reviewing issue 042's newly-real Rule Settings tab: middle school and high school have different service-hour requirements, but the rule model only had one flat annual/stage requirement applied to every grade. Separately, the "required duration and threshold" fields (annual/stage requirements, honour tiers) displayed as `4500 minutes (≈ 75 hours)` — minutes-primary — when hours-primary (`75 hours (4500 min)`) reads more naturally for values that are always specified in whole hours by the institutional design; the rule creation/edit form's inputs were minutes-typed for the same reason.

Confirmed via `Design/chinese/01-TimeBankDesign.md` §6: grades 6-8 (middle school) require 25h/year + 75h cumulative by graduation; grades 9-11 (high school) require 75h/year + 300h cumulative — two genuinely different bands, not a per-grade table. The real backend `RuleVersion`/`EffectiveRule`/`RuleResolutionService` model had exactly one flat `annualRequiredMinutes`/`stageRequiredMinutes` pair per rule version, resolved purely by effective date with no grade parameter anywhere in the call chain — it had only ever modeled the high-school band's numbers (4500/18000, i.e. 75h/300h) and applied them uniformly to every grade, including middle school.

## Root cause

The rule versioning model (built in B2.7-B2.9) never had a grade-band concept — `RuleResolutionService.resolveEffectiveAt(Instant)` takes only a timestamp, and every consumer (`ProgressServiceImpl`, `ReviewDecisionTransaction`, `ReportServiceImpl`, `SummaryMaterializationJob`) reads the same flat `annualRequiredMinutes`/`stageRequiredMinutes` regardless of the student's grade. This was a genuine, undetected gap between the documented institutional design and the implementation — not previously caught because the frontend that would have surfaced it (a real Rule Settings tab) didn't exist until issue 042.

## Fix

Backend:
- `V25__rule_version_grade_band_requirements.sql`: splits `annual_required_minutes`/`stage_required_minutes` into `middle_annual_required_minutes`/`middle_stage_required_minutes`/`high_annual_required_minutes`/`high_stage_required_minutes` on `rule_version`; backfills existing rows' middle-school columns with the design doc's real numbers (1500/4500 min = 25h/75h), since no prior data existed for that band at all.
- `RuleVersion`/`EffectiveRule`: replaced the flat fields with the four band-specific ones; both gained `annualRequiredMinutesForGradeLevel(int)`/`stageRequiredMinutesForGradeLevel(int)` helpers that pick middle vs. high by `gradeLevel <= 8`.
- `RuleServiceImpl`: `CreateRuleRequest`/`UpdateRuleRequest`/`RuleVersionDto` carry the four band fields; `validate()` now checks stage ≥ annual separately per band.
- `ProgressServiceImpl.getProgress`: now looks up the student's real `gradeLevel` (via `StudentService.getDetail(...)`, sensitive-fields excluded so no extra audit entry or guardian-data exposure) and selects the correct band before computing gap/required minutes — previously always used the flat (high-school) numbers for every student regardless of grade.
- `ReportServiceImpl.completion()`/`computeCompletionFigures()`/`SummaryMaterializationJob`: each grade's compliance figure now compares against its own band's requirement instead of one number applied to all grades; `CompletionReportDto`'s top-level `requiredMinutes` (which could no longer mean one thing) moved onto each `GradeFigure` instead — the frontend never read the top-level field, so this is not a breaking change for any real consumer.

Frontend (`SystemAdmin.tsx`'s Rule Settings tab):
- Split the single "Service Hour Requirements" field group into "Middle School Requirements (Grades 6-8)" and "High School Requirements (Grades 9-11)", each with its own annual/stage pair.
- Both the read-only rule-card display and the create/edit form now show "required duration and threshold" fields (requirements + honour tiers) hours-primary: `75 hours (4500 min)` instead of `4500 minutes (≈ 75 hours)`.
- The create/edit form's inputs for those same fields are now typed in hours (converted to integer minutes on save, matching the project-wide no-floats-in-the-duration-path convention — the stored/submitted value is always the rounded integer minutes) instead of minutes.
- The rounding/coin-conversion group (rounding granularity, minutes-per-coin-unit, centi-coins-per-unit) is unaffected — those are genuinely minute/ratio-denominated parameters, not "required duration or threshold" values, so they keep their original minutes-typed, minutes-primary treatment.

## Test plan

- Backend: `mvn clean test` (fast suite incl. `ModuleBoundaryTest`/ArchUnit — the new `progress`→`student.application.StudentService` dependency doesn't violate module boundaries) and a scoped `mvn verify -Dit.test=RuleVersioningIT,DashboardReportingIT` against real MySQL, both clean; `DashboardReportingIT`'s completion-report test was strengthened (not just fixed for compilation) to assert a middle-school student and a high-school student are judged against their own band's threshold, not one shared number.
- Live: create a draft, confirm the two requirement groups and hour-primary display/input all render correctly against the real backend; confirm a middle-school and high-school student's own progress page shows their band's real requirement.

## Resolution

Deployed to `https://tb.example.com/` (backend + frontend). `V25` applied cleanly to the live database — confirmed via `GET /rules` that all 3 existing rows (1 published, 2 archived) were correctly backfilled: `middleAnnualRequiredMinutes`/`middleStageRequiredMinutes` got the real design-doc middle-school numbers (1500/4500), and the renamed `high*` columns kept the original flat values (4500/18000) that had actually always been the high-school band.

Live-verified via Playwright as `admin001`:
- Rule Settings tab: all 3 rule cards now show two separate "Middle School Requirements (Grades 6-8)" / "High School Requirements (Grades 9-11)" groups, each hours-primary (`25 hours (1500 minutes)`, `75 hours (4500 minutes)`, etc.) instead of the old minutes-primary `≈` format. The create-draft form's requirement/honour-tier inputs are hour-typed (`25`) with a live `= 1500 minutes` conversion hint underneath, prefilled correctly from the currently-published rule.
- Student progress, both bands, via direct API calls against real students: a real grade-7 student (`std_4423afde19780c4b204b`, "Zhou Junjie") now gets `annualRequiredMinutes: 1500` (25h) — previously would have gotten the flat 4500 (75h); a real grade-11 student (`std_a67e018989089fef5891`, "Zhao Siyuan") correctly gets `annualRequiredMinutes: 4500` (75h), confirming the fix didn't regress the (already-correct-by-coincidence) high-school case.
- `GET /reports/completion`: initially still showed the flat 4500 for every grade, including 6-8 — traced to `grade_compliance_summary`'s hourly materialized cache (`generated_at: 2026-08-21 10:00:00`, predating this deploy at ~10:25) serving stale pre-fix values, not a bug in the new logic. Cleared the cache table directly (narrated first — a purely-derived table with no independent value, safe to clear; the hourly job regenerates it with the fixed logic on its own from here on) and re-checked: grades 6-8 correctly show `1500`, grades 9-11 correctly show `4500`.

Backend test suite: fast suite (`mvn test`, including `ModuleBoundaryTest`'s ArchUnit check — the new `progress`→`student.application.StudentService` dependency doesn't violate any module boundary) clean. `RuleVersioningIT`/`DashboardReportingIT` (the two most directly-relevant IT classes, including the new/strengthened middle-vs-high-school completion-report test) pass clean against real MySQL. A full `mvn clean verify` across all 31 IT classes was also run for broader regression coverage; it takes roughly an hour end-to-end against the remote MySQL over an SSH tunnel (dominated by per-class schema-creation/migration-replay round-trips, not test logic) — every class that had completed by the time of this write-up passed with zero failures, and nothing in the untouched modules it covers has any code path through the changed files (confirmed earlier via a targeted search of every real consumer of the affected fields).

*Live-verified via Playwright and direct API calls against the real deployed frontend+backend+database. No live data needed reverting — the schema migration is additive/backfilling only, and the one direct write (clearing the stale derived cache table) has no source-of-truth data to lose.*
