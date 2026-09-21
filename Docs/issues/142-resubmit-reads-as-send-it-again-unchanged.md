# 142 — "Resubmit" reads as sending it again unchanged

**Status:** **Fixed and deployed 2026-09-21.** Aquaticism's report, refined on the
second message: not a missing Edit action — the word *Resubmit* made it look
like the record would go back as-is.

## 1. Nothing was missing

A returned record already offered the edit form. Measured on the deployed page
before changing anything:

```
returned rows: 6
row actions:   ["查看", "补充重提"]
clicking 补充重提 opens: 编辑退回记录   ← "Edit Returned Record"
```

So the flow Aquaticism described — teacher returns, student edits, student resubmits —
was all there. The button opened a full wizard with the record loaded.

**The defect was that the action was named for its ending rather than its
beginning.** 补充重提 / *Resubmit* reads as "send it again", so someone looking
for a way to *change* the record does not recognise it, and someone who does
click it half expects to have already resubmitted.

## 2. The word already existed

The proposal flow has the same situation — a reviewer sends it back with
comments — and already solved the naming:

| | English | Chinese |
| :--- | :--- | :--- |
| `student.proposals.reviseResubmit` | Revise & Resubmit | 按意见修改并重提 |
| `student.cases.respond` | Respond & Resubmit | 补充并重新提交 |
| **`student.records.resubmit`** (was) | **Resubmit** | **补充重提** |

Service records were the odd one out. So this is not a new coinage: the record
action now reads **Revise & Resubmit / 修改并重提**, matching the family. The
Chinese drops 按意见 that the proposal card carries, because the row already
shows the reviewer's reason immediately above the button — the phrase would be
repeating what the reader is looking at.

`修改`, not `编辑`: you revise a record a teacher has commented on; `编辑` is what
you do to your own draft, and the draft row two lines up already uses it.

The key was renamed with the value (`resubmit` → `reviseResubmit`), so the
bundle does not go on calling it the thing that was confusing.

## 3. Verification

Both languages, and the row height, because the label got longer and these
buttons sit in a table cell:

```
zh  ["查看", "修改并重提"]        row height 79   opens 编辑退回记录
en  ["View", "Revise & Resubmit"] row height 79   opens Edit Returned Record
```

Same height as before, so nothing wrapped. Desktop table and mobile card both
changed; the icon is now a pencil rather than a paper plane, for the same reason
as the words.

## 4. What it must not do

- **Must not imply the record goes back unchanged.** §1.
- **Must not invent a fourth phrase** for something the app already says two
  other ways. §2.
- **Must not say 编辑** for a returned record. §2 — that word belongs to drafts.
