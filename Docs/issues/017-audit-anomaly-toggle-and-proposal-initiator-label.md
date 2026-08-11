# 017 — Two live-verification findings from the DataView Optimization Phase 5 pass

Status: **Fixed and live-verified (2026-08-11)**

## Symptom

Found while live-verifying `Docs/clarification/04-DataViewOptimization.md`'s Phase 3 work (Audit Center / Case Review / Proposal Review detail workbenches) against `https://tb.example.com/`, two unrelated real defects:

1. **Audit Center — "Only Anomalies" toggle button shows the wrong visual state on a deep link or refresh.** Navigating directly to `/admin/audit?...&filter[hasAnomaly]=true` (or refreshing a page that already has the filter active) correctly narrows the list to anomaly records, but the toggle button itself renders in its inactive (gray) style instead of its active (red) style — the data is right, the affordance lies about it.
2. **Proposal Review — the "Student Initiator" field in the new Organization & Safety tab shows a confusing sentence instead of a name.** Opening any proposal's detail workbench showed: *"Student Initiator: Jia Ming (current logged-in user, auto-filled)"* as the field's **value**, with the real initiator's name never appearing — regardless of which proposal was open or who actually submitted it.

## Root cause

1. `AuditCenter.tsx` kept its own local `showAnomaly` boolean state, toggled by the button's own `onClick`, instead of deriving the button's active/inactive style directly from `params.filter?.hasAnomaly` (the value `useUrlSyncedPagedApi` actually reads from and writes to the URL). On a normal click this local state and the URL happened to move in lockstep, masking the bug — but the local state's own `useState(false)` initializer never looked at the URL on mount, so a deep link or refresh left the button stuck on its default "off" appearance while the real filter (driven by `params`, not the local boolean) was already "on".
2. `ProposalReview.tsx`'s new Organization & Safety tab reused `t('student.proposals.initiator')` for the field label. That key is not a plain label — it's a long, context-specific sentence written for the *student's own create-proposal form*, where it explains that the initiator field auto-fills with whoever is currently logged in (`"Student Initiator: Jia Ming (current logged-in user, auto-filled)"`). A parallel, actually-plain label already existed at `student.proposals.detailLabels.initiator` ("Student Initiator") — along with equivalents for every other field this tab needed (`coOrganizers`, `mentorTeacher`, `partnerOrg`, `contactInfo`, `recruitmentMethod`, `eligibility`, `proofMethod`, `riskMeasures`, `materialNeeds`, `budgetNeeds`, plus the Activity Content tab's `goal`/`description`/`expectedOutcomes`/`plannedDate`/`location`/`activityFormat`/`targetParticipants`/`volunteerTasks`) — but the wrong (top-level, form-context) key was used for all of them, not just `initiator`. Only `initiator` happened to visibly break, because it's the only one of those keys whose top-level and `detailLabels` versions differ in more than capitalization/punctuation.

## Fix

1. `AuditCenter.tsx`: removed the redundant `showAnomaly`/`setShowAnomaly` local state entirely. The button's `onClick` and its active-style class both now read `params.filter?.hasAnomaly` directly — the single source of truth `useUrlSyncedPagedApi` already maintains, same pattern already used for the grade filter and sort controls on this page.
2. `ProposalReview.tsx`: every `t('student.proposals.X')` field-label call in the detail workbench's Activity Content and Organization & Safety tabs switched to `t('student.proposals.detailLabels.X')`, the parallel key set that was already built for exactly this read-only-display use case.

## Test plan

- Live: deep-link `/admin/audit?...&filter[hasAnomaly]=true` and confirm the toggle renders active (red) immediately, without needing to click it.
- Live: open any proposal's Organization & Safety tab and confirm "Student Initiator" shows as a plain label with the real initiator's name as the value, in both English and Chinese.
- `pnpm build` clean before and after.

## Resolution

- `frontend/src/app/components/admin/AuditCenter.tsx` and `frontend/src/app/components/admin/ProposalReview.tsx` updated.
- Deployed via `frontend/scripts/deploy.sh`; both fixes confirmed live via Playwright (anomaly toggle: `bg-red-100 ... border-red-300 ... text-red-700` class present on load with the filter in the URL; initiator field: "学生发起人" / "Yun Li" rendered correctly, both languages).

---
*Live-verified 2026-08-11 against `https://tb.example.com/` via Playwright MCP, logged in as `admin001`. Both findings surfaced during the same Phase 5 verification pass that also confirmed (not a new bug — re-encountering already-logged issue 011's root cause) that the Proposal Review category-filter dropdown shows stale-language options after an in-session language toggle, same as issue 011's grade dropdown; not re-filed since 011 already covers the cause and is deliberately left open.*
