# 066 — "Next" on the evidence step submits the record immediately, skipping the Confirm & Submit declaration

Status: **Fixed and live-verified (2026-09-15)**

## Symptom

Found during the three-student end-to-end verification pass. In the
Submit Service Record wizard, clicking **Next** on step 3 (Evidence) did
not land on step 4 (Confirm & Submit) — the record was created and
submitted right then, and the wizard closed straight back to the records
list.

The student therefore never saw step 4 at all, which is the only place
that shows the review summary and the declaration they are agreeing to:

> ⚠️ By submitting, you declare that the above information is true and
> accurate, with no false reporting or exaggeration. Any false information
> will be handled according to school regulations.

That makes this more than a navigation glitch: the submission's stated
consent step was being bypassed silently.

It was also intermittent, which is why earlier sessions' wizard work
(issues 058/061/062/063) never hit it — see "Root cause".

## Root cause

The two navigation buttons are a ternary at the same position inside the
same parent, with no `key`:

```tsx
{step < STEP_KEYS.length - 1 ? (
  <button type="button" disabled={!nextEnabled} onClick={...}>Next</button>
) : (
  <button type="submit">Confirm Submit</button>
)}
```

React reconciles those two branches onto the **same** `<button>` host
node — same element type, same position — and only patches its
attributes. So for one physical click:

1. The browser dispatches `click`; React runs the "Next" `onClick`, which
   calls `setStep(3)`.
2. React re-renders and, because the node is reused, mutates that very
   node's `type` from `"button"` to `"submit"` and drops the handler.
3. The browser then performs the click event's **default action** — and by
   that point the node it is acting on is a submit button, so it submits
   the form, invoking `handleSubmitRecord`.

The default action is evaluated after event dispatch completes, which is
what lets a discrete-priority React 18 update sneak in between the handler
and the default action. That timing is also why it looked intermittent:
driving the wizard with a synthetic `element.click()` from a script
sometimes lost the race and behaved "correctly", while real mouse input
reproduced it every time. Verification that uses real clicks caught it;
source review and scripted clicking both did not.

## Fix

Give the two branches distinct `key`s (`key="next"` / `key="submit"`), so
React treats them as different elements and unmounts one before mounting
the other instead of mutating a live node's `type` mid-click.

This is deliberately the minimal fix rather than, say, moving the submit
to an `onClick` handler: the form-level `onSubmit` is still the right
place for the submit path (it keeps native constraint validation and the
Enter-to-submit behaviour the earlier wizard issues depend on), and the
actual defect is purely the shared DOM node.

## Test plan

- `pnpm build` clean.
- Live Playwright pass against the deployed server as `spencer`, using
  real clicks (not scripted `element.click()`): walked a fresh record
  through source → service info → evidence and clicked **Next** once on
  the evidence step. The wizard now stops on step 4 showing the full
  summary and the declaration text, with **Confirm Submit** as the only
  forward action; a separate click on that button is what actually
  submits. Reproduced the old behaviour on the previous deploy immediately
  beforehand (single Next click → record submitted, list re-rendered).

## Resolution

`frontend/src/app/components/student/ServiceRecords.tsx` — explicit `key`
on each branch of the Next/Confirm Submit ternary, with a comment naming
the reconciliation hazard so the keys don't get "cleaned up" later.
