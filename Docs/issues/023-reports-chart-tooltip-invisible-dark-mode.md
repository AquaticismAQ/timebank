# 023 — Reports chart tooltips invisible in dark theme

Status: **Fixed and live-verified (2026-08-12)**

## Symptom

Reported directly by the user: hovering the Project Distribution pie
chart in dark theme showed only a blank/black tooltip box, no text.
Fine in light theme.

## Root cause

`ReportsPage.tsx` has four recharts `<Tooltip>` instances. The
Participation & Completion Rate tab's two (bar charts) set
`contentStyle` (background/border) *and* `labelStyle`/`itemStyle`
(`color: 'var(--foreground)'`). The Project Distribution pie chart and
Service Hours Trend line chart's `<Tooltip>`s only set `contentStyle` —
`labelStyle`/`itemStyle` were never added, so their text fell back to
recharts' default color. Against the dark theme's `--card` background
(dark) that default reads as invisible, producing the reported "blank
box"; against light theme's `--card` (near-white) it happened to still
be legible, which is why it looked fine there.

## Fix

Added the same `labelStyle={{ color: 'var(--foreground)' }}` /
`itemStyle={{ color: 'var(--foreground)' }}` pair to both remaining
`<Tooltip>`s (pie chart and line chart), matching the two that already
had it.

## Test plan

- Frontend: `tsc --noEmit` and `pnpm build` clean.
- Live: dark theme, Project Distribution tab, hovered a pie sector
  (`path.recharts-sector`) — confirmed readable white-on-dark tooltip
  text via screenshot. Service Hours Trend not separately screenshotted
  this session (same code pattern, same fix, not re-verified per-pixel).

## Resolution

`frontend/src/app/components/admin/ReportsPage.tsx` — two `<Tooltip>`
call sites (Project Distribution pie chart, Service Hours Trend line
chart) gained `labelStyle`/`itemStyle`. Frontend redeployed.

---
*Live-verified 2026-08-12 against `https://tb.example.com/` via Playwright
MCP, logged in as `Teacher Wei Chen`, dark theme: hovered a
`path.recharts-sector` element on the Project Distribution pie chart and
confirmed the tooltip ("Campus O : 3") now renders in visible white text
on the dark card background, screenshot confirmed. Service Hours Trend's
identical fix was not independently click-verified this session — same
code pattern, not expected to differ, but noted as a gap.*
