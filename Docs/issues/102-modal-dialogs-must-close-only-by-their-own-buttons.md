# 102 — Modal dialogs must close only by their own buttons

**Status:** Fixed, live-verified (§7).
**Raised:** 2026-09-18 by the user, as a consistency rule for the whole
frontend:

> For all modal dialogs, remove the upper-right `X` close button — they can only
> be closed by the buttons at the bottom of the dialog — and clicking outside
> the dialog must not close it.

## 1. Why this is worth doing beyond consistency

Every dialog in this app is a form or a decision: create a user, decide a case,
cancel a project, file an appeal. The two affordances being removed are the two
that get triggered *by accident* — a misjudged click lands on the backdrop, or
the pointer drifts to the corner. Both discard whatever was typed, silently and
with no undo. The bottom buttons are the ones a user presses on purpose.

## 2. Current state, surveyed

Every modal in this codebase is hand-rolled — a `fixed inset-0` overlay plus a
backdrop `div`. The vendored Radix primitives (`ui/dialog`, `ui/alert-dialog`,
`ui/sheet`, `ui/drawer`) have **zero consumers**, so nothing inherits their
behaviour and nothing is fixed by configuring them.

Across 20 component files:

| | Count |
| :--- | ---: |
| Backdrop overlays | 27 |
| …that close the dialog when clicked | **23** |
| `X` buttons in dialog headers | **19** |
| `Escape` key handlers | 1 |

The four backdrops that already ignore clicks are from issues 020/021, which
fixed this for a handful of dialogs. This finishes the job.

Nineteen other `<X>` icons in the codebase are **not** dialog closes and stay:
chip removal in `SearchPicker`, the mobile nav drawer in `AppShell`, the "clear
search" affordance, evidence-file removal, the full-page wizard back buttons in
`ServiceRecords`/`EventProposals` (those are routed views, not modals), and the
✗ glyph in `PasswordStrength`.

## 3. Two dialogs where the X is the only way out

Removing it mechanically would have produced a dialog with **no exit at all** —
a trapped user, which is strictly worse than the problem being fixed. Both get a
real footer:

- **`EvidenceViewerDialog`** — header is Download + X, and there is no footer.
- **`ProjectRegistrants`** — header X, footer has only bulk Confirm/Reject.

Each now has a **Close** button in a proper footer.

Every other dialog was checked and already has a footer Cancel/Close.

## 4. `DetailWorkbench.closeButton` becomes dead and is removed

The shared workbench took a `closeButton?: 'always' | 'none' | 'mobileOnly'`
prop, added by the 05-SearchFilterSortConsistency addendum so callers with a
footer Cancel could suppress the X. **All three callers already pass `"none"`**
— `AuditCenter`, `ProposalReview`, `CaseReview` — so the X it guards has not
rendered for some time. With the rule now absolute the prop can only ever hold
one value, so it and the button go.

## 5. Escape — removed, then restored on every dialog

Initially removed: the rule as stated was "only the buttons at the bottom", and
Escape is a dismissal path like the other two. The accessibility cost was
flagged at the time — the ARIA authoring practices expect a modal to be
dismissible from the keyboard.

**Reversed the same day on the user's decision: "restore the escape dismiss."**

Restored on **every** dialog rather than only on `EvidenceViewerDialog`, which
was the one that had it. Putting it back in one place would have left 1 of 20
dialogs keyboard-dismissible — the exact inconsistency this issue exists to
remove — and the accessibility argument applies to all of them equally.

**The rest of the rule is unchanged**: no header `X`, and backdrop clicks still
do nothing. Escape is defensible where those are not, because it is hard to
press by accident while a stray click is not.

### `useEscapeToClose`, and why it keeps a stack

`frontend/src/app/lib/useEscapeToClose.ts`. Two details that are not
decoration:

- **A module-level stack, so one `Escape` closes one dialog.** Dialogs can
  nest — a confirmation over the form it belongs to. If each mounted dialog
  listened on `window` independently, a single keypress would close the
  confirmation *and* the form under it, losing the work the confirmation was
  protecting. Only the last-registered handler responds.
- **Capture phase.** A dialog must still close when focus is inside an input
  that stops propagation of its own key events. `defaultPrevented` is still
  honoured, so a combobox that closes its own popover on Escape does not also
  close the dialog around it.

The stack is **defensive rather than exercised**: no path in the app today
opens two modals at once — what looks like nesting in `UserModal` is an inline
confirm inside the same dialog, so Escape there closes the whole dialog, which
is correct and loses nothing since the delete was never confirmed.

## 6. Keeping it that way

The rule is now stated in `Docs/frontend/Design.md` (and its `_CN` twin), since
the next dialog someone writes is where this regresses.

To check it later, match on `inset-0` and an `onClick` **without** assuming the
backdrop's colour or which element carries the handler — §8 is about exactly
that mistake:

```bash
grep -rn 'inset-0[^>]*onClick' frontend/src/app/components
```

Expect exactly two hits, both legitimate and neither a dialog: `AppShell`'s
mobile nav drawer and `ProjectManagement`'s dropdown-menu scrim. Anything else
is a regression.

## 7. Verification

Deployed and driven in a browser.

**Create User (`SystemAdmin`) — a form dialog, all three rules:**

| Check | Result |
| :--- | :--- |
| Header contains a close button | **0 buttons in the header** |
| Type into the form, then click the backdrop | dialog **still open** |
| …and the typed text | **survived** — `typed-then-clicked-outside` still in the field |
| `Escape` | **closes it** — restored per §5 |
| Footer Cancel exists | yes |
| Footer Cancel closes it | yes |

The "typed text survived" row is the one that matters: dialog-still-open could
in principle be a re-render, and this shows the form state was never discarded.

**Service Record detail — one of the three that had no exit after the X was
removed, and now has a footer:**

| Check | Result |
| :--- | :--- |
| Header buttons | 0 |
| Footer **Close** present | yes |
| Backdrop click | still open |
| Footer Close | closes it |

## 8. A late catch worth recording

The first sweep matched backdrops written as
`<div className="absolute inset-0 bg-black/50 …" onClick={…} />` and fixed 23 of
them. Three dialogs used different markup and were missed by that pattern —
`AccountManagement` and `ProjectManagement` paint their backdrop
`bg-foreground/20`, and `ProjectRegistrants` puts the handler on the **wrapper**
with `stopPropagation` on the panel instead of having a clickable backdrop at
all. Grepping for the visual class would have declared the job done with three
dialogs still dismissing on an outside click.

The same second pass separated the genuine non-dialogs, which keep their
outside-click dismissal: `AppShell`'s mobile nav drawer and
`ProjectManagement`'s dropdown menu. Neither holds unsaved input, and
click-away is the expected gesture for both.

## 9. Verification of the Escape restoration

Three different wiring mechanisms exist, so all three were exercised rather than
assuming one stands for the others:

| Mechanism | Dialog | Result |
| :--- | :--- | :--- |
| Sub-component mounted only while open | Create User (`UserModal`) | Escape **from a focused input** closes it — which is what proves the capture-phase listener |
| Flag-gated inline modal | Service Categories → new category | opens, Escape closes |
| `open`-prop component with an early return | Audit Center detail (`DetailWorkbench`) | opens, Escape closes |

And the rest of the rule still holds after the change — re-checked on the same
dialogs: **header has no `X`**, and **the backdrop still does not close**
(`DetailWorkbench` and Create User both verified explicitly).
