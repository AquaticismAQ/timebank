# 143 — "Save Draft" described something that did not happen

**Status:** **Fixed and deployed 2026-09-21.** Three things Aquaticism reported
together, all versions of the same fault: a control named for an outcome it does
not produce.

## 1. "Revise & Resubmit" was too long

Issue 142 renamed the returned-record action from *Resubmit* to *Revise &
Resubmit*. Accurate, and too wide for a table cell that already carries View.
**Revise / 修改** says the part that was missing — that you change it first —
and the resubmit is what the form you land in is for.

## 2. Revising a returned record does not make a draft

The wizard's second button said **Save Draft**, and its toast said **Draft
saved**. Neither was true when revising: `saveRecord()` PATCHes the existing
record, which stays `RETURNED`. No draft is created, and a student who went
looking for one found nothing.

Now the label and the toast depend on what is open:

| Open | Button | Toast |
| :--- | :--- | :--- |
| a new record | Save Draft | Draft saved |
| a returned record | **Save** | **Record saved** |

One flag, `editing?.status === 'returned'` — the same condition the code already
used to decide PATCH-versus-POST. The wording now agrees with the request.

## 3. The appeal dialog's Save Draft did nothing visible

Clicking it toasted *Draft saved* and, from the student's point of view, nothing
happened.

**It was not broken.** `api.cases.create` ran, the draft was stored, and it is
there in My Cases. The problem is where the dialog was opened from: from a
service record, it closes back onto a list of **records**, so the case draft it
just created appears nowhere the student is looking.

So the button is now gated on `allowDraft`, **off by default**, and the question
it asks is about the caller rather than the dialog: *will the thing you just
saved be visible when this closes?*

- **My Cases** passes it — that page lists cases and refetches, so the draft
  appears immediately.
- **Service Records** does not.

Removing it everywhere was the other reading of the report, and it would have
deleted a working feature from the one screen where it works.

## 4. Verification

On the deployed page, in Chinese:

```
1  returned-row actions: ["查看", "修改"]

2  save button says: 保存
   toast:            记录已保存
   the record stayed RETURNED: "RETURNED"
   drafts that now exist:      0          ← none was created, which was the whole confusion

3  appeal opened from a record:   ["取消", "提交"]
   appeal opened from My Cases:   ["取消", "保存草稿", "提交"]
```

Row 2's last two lines are the ones that matter: the old copy claimed a draft,
and this shows there is none and the record is untouched in status.

## 5. What it must not do

- **Must not say "draft" for a PATCH to an existing record.** §2.
- **Must not remove Save Draft from My Cases.** §3 — it works there.
- **Must not leave a button whose result is invisible.** §3.
