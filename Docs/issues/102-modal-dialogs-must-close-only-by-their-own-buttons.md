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

## 5. Escape

One dialog handled `Escape` (`EvidenceViewerDialog`). Removed, because the rule
as stated is "only the buttons at the bottom", and Escape is an
accidental-dismissal path like the other two.

**This is a deliberate accessibility trade, and worth stating plainly.** The
ARIA authoring practices expect a modal dialog to be dismissible with Escape.
The counter-argument here is that these dialogs are unsaved-work forms rather
than transient popovers, every one has a visible, keyboard-reachable Cancel, and
losing a half-filled form to a stray keypress is its own accessibility problem.
Easy to reverse if you would rather keep Escape — it is one handler in one file.

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
| `Escape` (dispatched at document, window and the focused input) | dialog **still open** |
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
