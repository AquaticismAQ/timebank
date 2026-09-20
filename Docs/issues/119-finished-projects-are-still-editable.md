# 119 — A finished project is still offered for editing

**Status:** **Fixed and deployed** 2026-09-20, same day. Filed on Aquaticism's
report, from Project Management.
**Reported as:** three things on the admin Project Management page — an archived
project must not be editable (the action should read *View*, with no *Save
Changes*), a completed project likewise, with *Cancel and archive* removed from
it, and any non-draft project should offer *Copy to draft*.

## 1. What is wrong

The actions menu offers **Edit** on every project the actor may update,
whatever its status — and the dialog it opens has a working *Save Changes*.

That is not merely a mislabelled button. `ProjectServiceImpl.updateDraft` has
**no status guard at all**: the edit really does go through. So an admin can
today rewrite a completed project's recognised hours, capacity or dates after
its service records have been credited against it, or edit a project that was
archived a year ago. The method's name says `updateDraft`; nothing enforces it.

Two statuses are terminal in this sense:

| Status | Why it must be read-only |
| :--- | :--- |
| `COMPLETED` | Its hours are already credited to students' accounts. Changing `recognizedMinutes` afterwards silently disagrees with every transaction that cites it. |
| `ARCHIVED` | Terminal in the state machine — nothing may leave it. |

Every other status is legitimately editable: a `PUBLISHED` or `IN_PROGRESS`
project may need its location corrected.

## 2. Cancel and archive on a completed project

`Cancel and archive` shows for anything that is not already archived, completed
included, where it reads as nonsense — you cannot abandon something that has
already finished.

**It is not decorative there, though, and this is the part worth recording.**
`archive()` refuses to archive a `COMPLETED` project while confirmed students
still have unreviewed service records, and its own error text says *"Use Cancel
and archive to override."* `cancel()` is that override: it bypasses the
readiness gate and requires a reason (`02-PorjectOrganization.md` §2.1).

Aquaticism's call, asked and answered: **remove it anyway.** Reviewing the outstanding
records is the real fix, and an override that silently archives a project with
unreviewed work is the wrong affordance to keep within one click of it. The
consequence, accepted deliberately: a completed project with unreviewed records
cannot be archived from the UI until those records are reviewed.

**Left open:** the backend's error text still names the removed button. It is a
one-line message change, but it belongs with whatever decides what that message
should say instead, not smuggled in here — logged as follow-up rather than
silently fixed.

## 3. Copy to draft

There is no way to reuse a project. A termly activity that runs again has to be
retyped, and "retype it" is how eligible-grade lists and evidence requirements
drift apart between two runs of the same thing.

**The trap:** a copy carries the original's dates, which are in the past. Publish
such a draft untouched and `syncTimeBasedStatus` walks it straight to `ARCHIVED`
in the same request — registration window elapsed, nobody enrolled — destroying
the copy without an error. So copying **creates the draft and then opens it for
editing**, with the dates in front of you. Aquaticism's call, asked and answered.

The copy is a `POST /projects`, not a new endpoint: there is nothing to compute
server-side that the create path does not already do. Two details it must get
right — `Project.eligibleGrades` reads back as localized grade *names* while
create expects grade **ids** (the same conversion `toForm` already does, and
round-tripping names as ids would corrupt the list), and the title needs a marker
so two identical rows are tellable apart.

## 4. Enforcement

Aquaticism's call, asked and answered: **both layers.** The API rejects an update to a
completed or archived project with `409 STATE_CONFLICT`; the UI shows *View*.

UI-only would have left the rule holding exactly as long as every client chose to
honour it — and this repo's own convention is that status machines are
backend-owned and the frontend displays state rather than deriving it
(`CLAUDE.md`, APIContract §3). A stale tab is enough to break the other version.

## 5. Verification

**The API guard, against real rows on the deployed server** — the two that must
refuse, and one that must not, because a guard that rejects everything proves
nothing:

```
PATCH COMPLETED: 409 STATE_CONFLICT — A project in status COMPLETED cannot be edited.
  title unchanged: true
PATCH ARCHIVED:  409 STATE_CONFLICT — A project in status ARCHIVED cannot be edited.
  title unchanged: true
PATCH DRAFT (control): 200
```

Red-checked from the other side too: with `requireEditable` commented out,
exactly the two new integration tests fail and
`updateIsStillAllowedOnAPublishedProject` still passes.

**The actions menu**, read off the deployed page, one status at a time:

| Status | Menu |
| :--- | :--- |
| 已结束 COMPLETED | 报名名单, **查看**, **复制为草稿**, 归档 — no Edit, no Cancel and archive |
| 已归档 ARCHIVED | 报名名单, **查看**, **复制为草稿** |
| 草稿 DRAFT | 编辑, 发布, 取消并归档 — **no** Copy to draft |
| 已发布 / 进行中 | 报名名单, 编辑, 复制为草稿, 取消并归档 |

**The read-only dialog, measured by trying to change it** rather than by reading
a `disabled` attribute — which would have been the wrong measurement anyway, since
a `fieldset`'s disabling is an inherited state that a descendant's own
`.disabled` property does not report:

```
archived: title unchanged after typing: true
archived: grade selection unchanged:    true
heading 项目详情 · footer ["关闭"] · no Save
draft:    heading 编辑项目 · footer ["取消","保存修改"] · title accepted typing: true
```

**Copy to draft**, run on a harness `[AUTO]` project so the row it leaves behind
matches the clutter already there:

```
a new draft exists: true      status: DRAFT
title: [AUTO] … 社区图书馆志愿服务 （副本）
summary/description/location/capacity/recognizedMinutes/categoryId/
deliveryScope/startAt/registrationCloseAt/evidenceRequirements: no mismatch
eligibleGrades source: ["Grade 6"]   copy: ["Grade 6"]   survived: true
edit dialog opened on the copy: 编辑项目 · 取消/保存修改
```

The grade line is the one worth keeping: names-stored-as-ids is the failure this
would have had, and it is invisible until someone reads the list back.

The copy was then cancelled (`Verification copy for issue 119; discarding.`) —
projects have no hard delete by design.

## 6. What it must not do

- **Must not make `CLOSED`/`IN_PROGRESS` read-only.** A running project still
  needs correcting; only the two terminal statuses are frozen (§1).
- **Must not leave the read-only dialog's fields typeable.** A form that accepts
  input and cannot save is worse than either half.
- **Must not invent a duplicate endpoint.** §3.
- **Must not copy grade names as grade ids.** §3.
- **Must not silently drop the archive override's existence.** §2 records what
  was given up and why.
