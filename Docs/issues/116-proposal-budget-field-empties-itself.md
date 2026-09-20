# 116 — A proposal's budget field empties itself on the way back

**Status:** **Fixed and deployed** 2026-09-20, same day. Filed on Aquaticism's
report, from the proposal demonstration.
**Reported as:** the script fills *Budget or Other Support Needs*; after the
teacher requests changes, reopening the proposal shows it empty — the original
text is gone.

## 1. What happens

The form has two fields where the backend has one.

| Form field | Backend |
| :--- | :--- |
| 物资与场地需求 `materialNeeds` | `resourceNeeds` |
| 预算或其他支持需求 `budgetNeeds` | *(the same one)* |

- **Writing** joins them: `[materialNeeds, budgetNeeds].filter(Boolean).join('\n')`
  (`api/client.ts:527,557`).
- **Reading** does not split them back: the whole stored value goes into
  `materialNeeds` and `budgetNeeds` is set to the empty string
  (`api/client.ts:1533-1534`).

So a student who fills both, gets changes requested, and reopens their proposal
sees the budget box blank and their budget text sitting at the bottom of the
materials box. Nothing was deleted in the database — but the student is looking
at a form that has silently rearranged what they wrote, and the obvious reaction
is to type the budget again, which then duplicates it.

## 2. The existing justification is wrong

`client.ts:482-486` says the lossy split is safe because "an unedited resave
rejoins to the same original string since `formToApiData`'s
`.filter(Boolean).join('\n')` drops the blank half."

That is true of the *stored value* and irrelevant to the *user*. It reasons about
whether the database survives a no-touch resave, when the complaint is that a
student's own writing is not where they put it. Data integrity and not losing
someone's work are different claims, and the comment answers the first while
appearing to answer the second.

## 3. Why this cannot be fixed by splitting better

The join is `material + '\n' + budget`, and both fields are multi-line textareas.

| Split rule | Fails when |
| :--- | :--- |
| On the first `\n` | Materials is more than one line — its tail lands in the budget |
| On the last `\n` | Budget is more than one line — its head stays in materials |

There is no separator-based rule that survives both, because the join threw away
the boundary. Any "smarter" split is a guess that will be wrong on exactly the
proposals that were written most carefully.

## 4. What it should do

**Make the form match the data: one field.** 物资、场地与预算需求, mapping
straight onto `resourceNeeds`, with a placeholder that asks for all three.

- Nothing to split, so nothing to lose.
- No migration, and existing proposals read back exactly as written — a stored
  `"materials\nbudget"` simply shows as those two lines in one box, which is what
  the student typed.
- One fewer field on a six-step form that is already long.

**Considered and rejected: a real `budget_needs` column.** It would keep the two
boxes, and it is the better shape if the school ever needs budget separately —
for a report, or an approval threshold. Nothing asks for that today, and it costs
a migration, a DTO field, and a backfill decision for every existing proposal
(whose two halves are already fused beyond recovery). Worth revisiting the moment
a budget needs to be queried rather than read.

## 5. How it was fixed, and how it is kept fixed

One field: 物资、场地与预算需求 / *Resource Needs*, mapping straight onto
`resourceNeeds`. The join and the empty-string read are gone, `budgetNeeds` is
removed from the form state, the api client, the type, the teacher's detail view
and both i18n bundles, and the surviving field's placeholder now asks for all
three things.

No migration: an existing proposal's stored `"materials\nbudget"` reads back as
those two lines in one box, which is what the student typed in the first place.

**The demonstration now proves it every run.** `reviseAndResubmit` reads the
field back when the student reopens a returned proposal and asserts the original
text is still there — the exact symptom reported. Red-checked by asserting on
text that was never written, which fails as it should:

```
断言失败：学生按意见补充后重新提交
  被退回后重新打开提案时，之前填写的物资、场地与预算需求应当原样保留
```

Worth keeping in mind: the flow passed end to end *before* this guard existed,
with the student's text disappearing. A run that only checks each status is
reached will happily watch someone's work vanish.

## 6. What it must not do

- **Must not guess the boundary.** §3 — any split is wrong on some real input.
- **Must not keep two boxes backed by one field.** That is the defect, not the
  symptom.
- **Must not migrate existing values.** They are already joined; splitting them
  now would be the same guess made once, permanently.
