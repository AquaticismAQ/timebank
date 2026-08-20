# 030 — Project Center subtitle shows no project count in Chinese

Status: **Fixed and live-verified (2026-08-20)**

## Symptom

Reported by the user: in Project Center (`/student/projects`), the subtitle under the page title should read "N 个项目" but showed only "个项目" — the actual project count number was missing. Confirmed Chinese-only: the English UI correctly showed "6 projects".

## Root cause

`frontend/src/i18n/zh.json`, `student.projects.count` key was `"个项目"` — missing the `{{count}}` interpolation placeholder that `en.json`'s equivalent (`"{{count}} projects"`) has, and that every other `count` key in `zh.json` itself has (e.g. `cases.count`: `"{{count}} 个案件"`, `proposals.count`: `"{{count}} 个活动申请"`). `ProjectCenter.tsx` calls `t('student.projects.count', { count: items.length })`, so with no `{{count}}` token in the Chinese string, i18next had nothing to substitute the value into and the number was silently dropped.

A repo-wide scan comparing every `{{var}}` placeholder in `en.json` against its `zh.json` counterpart found this was the only instance of this bug class — no other keys were affected.

## Fix

`frontend/src/i18n/zh.json`: changed `student.projects.count` from `"个项目"` to `"{{count}} 个项目"`.

## Test plan

- Repo-wide `en.json`/`zh.json` placeholder-parity check (Node script diffing `{{var}}` tokens per key) — clean after the fix.
- `tsc --noEmit` and `pnpm build` clean.
- Live: Project Center in Chinese shows the real count (e.g. "6 个项目"); English unaffected.

## Resolution

Deployed to `https://tb.example.com/` via `frontend/scripts/deploy.sh`. Live-verified as `student001`: Chinese Project Center now shows "6 个项目"; English continues to show "6 projects".

*Live-verified via browser click-through on the real deployed frontend against the real backend, in both languages.*
