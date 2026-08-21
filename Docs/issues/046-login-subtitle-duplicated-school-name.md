# 046 — Login page subtitle duplicated the school name

Status: **Fixed and live-verified (2026-08-21)**

## Symptom

The login page showed the school name twice: "Example City HD Example City HD Student Volunteer Service Management Platform" (English) and the equivalent Chinese duplication.

## Root cause

`LoginPage.tsx` renders `{schoolInfo?.name ?? ''} {t('auth.login.subtitle')}`, prepending the real school name fetched from `GET /school/info` at runtime. But `auth.login.subtitle` in both `en.json` and `zh.json` was itself a hardcoded string that already included the school name ("Example City HD Student Volunteer Service Management Platform" / "示例中学学生志愿服务管理平台") — a leftover from before the subtitle was made dynamic, never updated when `schoolInfo?.name` was added in front of it.

## Fix

Trimmed the school name out of the `auth.login.subtitle` i18n string in both `en.json` and `zh.json`, leaving just the generic platform description ("Student Volunteer Service Management Platform" / "学生志愿服务管理平台"). The dynamic `schoolInfo?.name` prefix at `LoginPage.tsx:90` now supplies the school name exactly once.

## Test plan

i18n key/placeholder parity verified (no mismatches). `pnpm build` clean.

## Resolution

Deployed to `https://tb.example.com/`. Live-verified via Playwright in both languages: English shows "Example City HD Student Volunteer Service Management Platform"; Chinese shows "Example City HD 学生志愿服务管理平台" — no duplication in either.

*Live-verified via Playwright against the real deployed frontend in both languages. No backend or database changes.*
