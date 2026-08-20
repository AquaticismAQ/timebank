# 031 — Sidebar brand block taller than the header bar, with a redundant subtitle

Status: **Fixed and live-verified (2026-08-20)**

## Symptom

Reported by the user with a screenshot: the sidebar's brand block ("Time Bank" logo + title) was visibly taller than the desktop header bar to its right, breaking the top-border alignment between the two, and carried a "Student Portal"/"学生入口" subtitle under the title that the user wanted removed. The icon was also judged oversized relative to the shrunk block.

## Root cause

`frontend/src/app/components/layout/AppShell.tsx`'s `SidebarContent` rendered the brand block with `py-5` padding and no fixed height, sized purely by its content (a `w-9 h-9` icon tile plus a two-line title/subtitle stack) — while the desktop header bar (`AppShell`'s own `<header>`) is a fixed `h-12`. Nothing tied the two heights together, so the sidebar block (title + subtitle, plus vertical padding) ended up taller than 48px.

## Fix

`frontend/src/app/components/layout/AppShell.tsx`:
- Brand block changed from `px-4 py-5` (content-sized) to `h-12 px-4` (fixed height matching the header bar), keeping `flex items-center` for vertical centering.
- Removed the subtitle (`brandSubtitle`) line entirely from the block — now just the icon tile and the "Time Bank"/"时间银行" title.
- Icon tile shrunk from `w-9 h-9` (icon `w-5 h-5`) to `w-7 h-7` (icon `w-4 h-4`) to fit the shorter row without looking cramped.
- `brandSubtitle` removed from `SidebarContentProps`/`sidebarProps` (dead there now) — the prop itself stays on `AppShellProps` since the desktop header bar still uses it as a fallback (`headerInfo ?? brandSubtitle`) when a page doesn't supply its own `headerInfo`.

## Test plan

- `tsc --noEmit` clean.
- Live: sidebar brand block's bottom border sits flush with the header bar's bottom border; no subtitle text under "Time Bank"/"时间银行"; checked in both light and dark theme.

## Resolution

Live-verified locally against the real backend (student portal, both themes) — brand block is now exactly 48px tall, aligned with the header bar, subtitle removed, icon visibly smaller. Deployed to `https://tb.example.com/` via `frontend/scripts/deploy.sh` and re-confirmed on the live server.

*Live-verified via browser click-through in both light and dark theme, on the real deployed frontend.*
