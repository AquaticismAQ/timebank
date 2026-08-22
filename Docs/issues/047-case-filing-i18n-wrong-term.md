# 047 — Case-filing i18n keys use "申请" (request) instead of "申诉" (appeal)

Status: **Fixed, live-verified (2026-08-22)**

## Symptom

In the Student Portal → Service Records page, the case-filing feature (filing an appeal/case against a review decision) displayed incorrect Chinese text: "发起申请" (File a Request), "我的申请" (My Requests), and "暂无已提交的申请" (No requests filed) — all using "申请" (request/application) instead of the correct domain term "申诉" (appeal/case). The English side similarly showed "File a Request" and "general request" instead of "File a Case" and "general case".

## Root cause

Four i18n keys in `zh.json` under the `student.records` namespace used the wrong Chinese term for "case" — "申请" (request/application) instead of "申诉" (appeal/grievance). Two parallel keys in `en.json` used "request" instead of "case". The rest of the codebase (e.g. `admin.cases.title: "申诉处理"`, `permissions.cases: "申诉与复核"`) already used the correct term consistently — these four were simply authored with the wrong near-homophone.

## Fix

`frontend/src/i18n/zh.json`:
- `student.records.fileRequest`: "发起申请" → "发起申诉"
- `student.records.myCases`: "我的申请" → "我的申诉"
- `student.records.noCases`: "暂无已提交的申请" → "暂无已提交的申诉"
- `student.records.supplementNoRecordOption`: "不关联具体记录——一般性申请" → "不关联具体记录——一般性申诉"

`frontend/src/i18n/en.json`:
- `student.records.fileRequest`: "File a Request" → "File a Case"
- `student.records.supplementNoRecordOption`: "No specific record — general request" → "No specific record — general case"

## Test plan

1. Log in as `student001`, navigate to Service Records
2. Switch to Chinese — verify "发起申诉" button and "我的申诉" tab label
3. Click the "我的申诉" tab — verify empty state shows "暂无已提交的申诉" (or shows real cases if any exist)
4. Switch to English — verify "File a Case" button and "My Cases" tab label

## Resolution

Fixed and live-verified via Playwright against the local dev server proxied to the real backend (`student001`). Chinese verification confirmed "发起申诉" and "我的申诉" render correctly; English verification confirmed "File a Case" and "My Cases". TypeScript check and production build both pass clean.

*Verified via Playwright against the local dev server (proxied to real backend). Not yet deployed to the shared test server — the fix will be visible on `https://tb.example.com/` after the next `frontend/scripts/deploy.sh` run.*
