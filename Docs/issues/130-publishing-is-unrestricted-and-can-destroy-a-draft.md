# 130 — Publishing is unrestricted, and can destroy a draft

**Status:** **Fixed and deployed** 2026-09-20, same day. Filed on Aquaticism's report,
which closes the follow-up issue 129 §4 left open and adds a second rule.

## 1. Two ways publishing went wrong

**Out of grade.** Issue 129 stopped a teacher *editing* a project outside their
grades and deliberately left `publish` alone, because the report said edit. That
left an incoherent pair of powers: a teacher could not open a Grade 7 draft, but
could publish it to the whole school. Aquaticism's answer: hide the action, and
enforce it.

**Out of date.** A draft whose registration window has already closed could be
published, and doing so is not merely pointless — it is destructive.
`syncTimeBasedStatus` runs on the very next read: `PUBLISHED` →
`REGISTRATION_OPEN` → and, with nobody enrolled, straight to `ARCHIVED`. The
draft someone had been preparing is gone, with a success toast and no error
anywhere. Issue 119 §3 documented this trap for *copied* projects and worked
around it by opening the copy for editing; this closes it for every draft.

## 2. The rules

- `publish` applies the same grade check as `updateDraft`
  (`requireWithinActorsGrades`), including its ownership clause — a teacher
  publishes their own cross-grade draft as before.
- A `DRAFT` whose `registrationCloseAt` is not in the future is refused with
  `409` and `PROJECT_REGISTRATION_WINDOW_PASSED`, carrying the date so the
  client can say when.

Checking `registrationCloseAt` alone is enough: `validateDateOrdering` already
requires it to be on or before `startAt`, so a future close implies a future
start. The check is scoped to `DRAFT` because a project that is already live has
passed this point legitimately.

**The auto-archive path is untouched** and still reachable the way it actually
happens — a project published with a live window whose window later elapses with
nobody signed up.

## 3. A dialog, not a toast

Aquaticism asked for an error dialog, and the distinction is the right one: the answer
to this refusal is *"go and change the dates"*, which is an instruction rather
than a notification. A toast that vanishes in four seconds is the wrong shape
for something you have to act on. The dialog names the project, the date
registration closed, what publishing would do, and carries an **Edit dates**
button that opens the edit form on that project.

## 4. Two fixtures this broke, and why that is the right outcome

Two existing tests published drafts with already-closed windows as a shortcut:

- `ProjectLifecycleIT.registrationOpenArchivesAutomaticallyWithZeroRegistrants…`
  reached the auto-archive state directly.
- `ProjectAcademicYearScopeIT` seeded a *past year's* project, which by
  definition has a closed window.

Both now reach their state the way a real row does — published with a live
window, then dated afterwards. The assertions are unchanged; only the route is,
and the new route is the one production takes.

## 5. Verification

**The grade rule, with the control on the same line:**

```
API publish  Grade 6 draft                      -> 403 PROJECT_OUT_OF_YOUR_GRADES
API publish  Grade 6,7,8,9,10 draft             -> 200 PUBLISHED      (includes grade 10, teacher005's)
draft menu, grade 10 => ["编辑","发布"]
draft menu, grade 7  => ["查看"]                  ← Publish is not offered at all
```

**The stale-window rule**, by staling a real draft's window and pressing Publish
in the page:

```
无法发布该项目
[AUTO] … 社区图书馆志愿服务 (copy)
它的报名已于 2026-09-20 截止。现在发布会立即关闭并归档该项目，因为已经没有人能报名了。
请先把报名时间和活动时间改到未来的时间段，然后再发布。
[关闭] [修改时间]   → 修改时间 opens 编辑项目 on that project
```

The draft's original close date was put back afterwards. Nine integration tests
cover both rules, including the control that a live window still publishes — a
guard that refused every draft would pass every other assertion here.

## 6. What it must not do

- **Must not block publishing a draft the actor owns.** §2 — the ownership
  clause from issue 129 carries over.
- **Must not make the auto-archive path unreachable.** §2.
- **Must not check `startAt` instead.** §2 — ordering already ties them, and
  `registrationCloseAt` is the boundary that actually archives the project.
- **Must not answer with a toast.** §3.
- **Must not leave the action visible and fail on click.** §1.
