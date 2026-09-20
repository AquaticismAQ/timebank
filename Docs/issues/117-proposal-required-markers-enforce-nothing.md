# 117 — A proposal's required markers enforce nothing

**Status:** **Fixed and deployed** 2026-09-20, same day. Filed on Aquaticism's
report, from the proposal demonstration.
**Reported as:** fields in the proposal form are marked required with `*`, yet
the proposal submits anyway — the frontend needs validation.

## 1. What is wrong

`EventProposals.tsx`'s `field()` helper prints a red asterisk on every field it
renders — `opts?.required !== false`, and **no call site passes `false`** — and
then renders the `<input>`/`<textarea>` **without a `required` attribute**.

There is also no `<form>` element, no `formRef`, and no `reportValidity()`
anywhere in the file. So nothing is checked when a step is left, and nothing is
checked at submit. Sixteen fields claim to be mandatory and not one of them is.

Compare `ServiceRecords.tsx`, which does this properly: `required` on the inputs
and `formRef.current?.reportValidity()` on Next, with a comment explaining that
each step mounts only its own fields so the *departing* step is what has to be
validated (issues 058 and 066).

## 2. The two layers disagree about what is mandatory

The backend requires six things (`ProposalRequests.CreateProposalRequest`, plus
the table's NOT NULL columns):

| Required by the API | Form field |
| :--- | :--- |
| `title` `@NotBlank` | 活动名称 |
| `categoryId` `@NotBlank` | 服务类别 *(a select, always set)* |
| `plannedStartAt` / `plannedEndAt` `@NotNull` | 计划日期 + 预计时长 |
| `capacity` `@Positive` | 预计志愿者人数 |
| `proposedSupervisorId` `@NotBlank` | 拟邀请指导教师 |
| `proposed_recognized_minutes` NOT NULL | 建议认定时长 |

Everything else — objective, description, expected outcome, location, format,
participants, tasks, safety plan, resource needs, recruitment plan, eligibility,
evidence plan — is **optional server-side**.

So the form marks ten fields mandatory that the system does not require, and
enforces none of the six that it does.

## 3. What it should do

**The asterisk should mean "you cannot submit without this", and that set is the
one the API defines.** Marking more would have the UI refuse what the API
accepts — a second, undocumented rule that no other client would know about, and
that nobody could see by reading the contract.

- `required` becomes **opt-in** in `field()`, so a field is starred only when it
  is genuinely mandatory, and a starred field carries a real `required`
  attribute. Numeric ones get `min` so that zero is refused too.
- The wizard gets a `<form>` and validates on Next, the same way
  `ServiceRecords.tsx` does and for the same reason: a step's fields are only
  mounted while that step is showing, so a check at the end sees none of them.
- **The mentor picker needs its own check.** It is a `SearchPicker`, not a native
  input, so `reportValidity()` cannot see it — and it is one of the six the API
  truly requires. Without an explicit guard it stays exactly as broken as
  everything else.

Three of the six can actually be left blank today: 活动名称, 计划日期 and
拟邀请指导教师. The numeric fields ship with sensible defaults and the category
select is pre-selected, which is why this has not produced a server error in
practice — the form is saved by its defaults rather than by its validation.

## 4. Verification

Checked against the deployed build, by leaving a required field empty rather
than by reading the code:

| Attempt | Result |
| :--- | :--- |
| Next with 活动名称 empty | Stays on step 1; the offending `<input>` reports *"Please fill out this field."* |
| Next once it is filled | Advances to step 2 |
| Next on 组织安排 with no mentor chosen | Stays on step 3, and says *请先选择拟邀请的指导教师* |

The second row matters as much as the first: a guard that blocks everything is
not validation, it is a broken button. The proposal demonstration still passes
end to end, which is the same check from the other side — a complete proposal is
not obstructed.

## 5. What it must not do

- **Must not mark a field required that the API accepts empty.** That invents a
  rule the contract does not state (§2).
- **Must not rely on `reportValidity()` alone.** It cannot see the pickers.
- **Must not validate only at the end.** Each step mounts only its own fields.
- **Must not make an asterisk decorative again.** Either it is enforced or it is
  not shown.
