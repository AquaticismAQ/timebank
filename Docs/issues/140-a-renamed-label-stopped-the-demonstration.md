# 140 — A renamed label stopped the demonstration

**Status:** **Fixed 2026-09-20.** Aquaticism's report: the project demonstration timed
out after `Verified Duration` became `Verified Duration (hours)` (issue 132).

## 1. The defect is not the stale string

The step looked up its field with an **exact** label match, so adding a unit to
the label broke it. That much is obvious. The part worth recording is that there
were **three copies** of that locator — in the project, proposal and
service-record steps — and only the proposal one had already been changed to
match the *start* of the label:

| Copy | Matched |
| :--- | :--- |
| `steps/proposal.ts` | `starts-with(normalize-space(.), …)` — tolerant |
| `steps/project.ts` | `normalize-space(.) = …` — exact |
| `steps/serviceRecord.ts` | `normalize-space(.) = …` — exact |

Someone met this problem once, fixed it where they met it, and the other two
copies could not learn from it. A duplicated helper does not share a lesson.

## 2. Fix

One `field()` in `harness/stage.ts`, matching by prefix. That also subsumes the
required-marker asterisk the service-record steps were handling by passing
`'服务日期 *'` including the marker.

## 3. The other half: say what went wrong

A renamed label surfaced as:

```
Timeout 30000ms exceeded.
  waiting for locator('xpath=//label[normalize-space(.)="认定时长"]/…')
```

— which says nothing about *why* the label is gone, in the middle of a run with
an audience. It now says:

```
Could not fill 「认定时长」 — no form control under a label starting with it.
  labels on screen: 项目名称 * · 项目摘要 · 详细描述 · 服务类别 · 认定时长（小时） · 活动开始 · …
  if one of those is the field, the label was renamed: update the step, not the app.
```

The renamed label is in the list, beside the one being looked for.

Two details that came from testing the message rather than assuming it read
well: it is **scoped to the open dialog** (the labels behind it are not what the
step was looking at — the first version listed the page-size control), and each
label is **flattened**, because a label wrapping a `<select>` reports every
option on its own line.

## 4. Verification

Both flows pass headless:

```
PASS  project-workflow
PASS  proposal-workflow
```

The message was red-checked by pointing a step at a label that does not exist,
which is how the two problems in §3 were found.

## 5. What it must not do

- **Must not match a label exactly.** §1 — labels gain units and markers.
- **Must not keep a private copy of a shared locator.** §1.
- **Must not fail with only an xpath.** §3.
