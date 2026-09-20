# 126 — Dialogs scroll the page behind them

**Status:** **Fixed and deployed** 2026-09-20, same day. Filed on Aquaticism's report:
a dialog with nothing to scroll passes the wheel to the page, and one that does
scroll passes it on once it reaches its end.

## 1. Two symptoms, one cause

**The document is this app's scroller.** `AppShell` is `min-h-screen`, so it
grows with its content and `<main>`'s `overflow-auto` never becomes a scroll
container — measured rather than assumed:

```
scrollingElement: HTML   docScrollable: true   2976 > 700   mainScrollable: false
```

Nothing holds the page still while a dialog is open, so a wheel event over the
dialog reaches the document either way:

| Aquaticism's case | Why |
| :--- | :--- |
| Dialog has nothing to scroll | the wheel passes straight through to the document |
| Dialog scrolled to its end | the browser *chains* the remainder to the document |

Measured before the fix, wheeling inside an open dialog:

```
tall dialog open: page 0 -> 1500
```

## 2. Why `overscroll-behavior` is not the whole answer

`overscroll-contain` is the usual advice, and it only addresses the second row:
it stops a scroll *container* handing on at its boundary. The first row has no
container and no boundary — there is nothing for it to contain. So the page
itself has to be held still, and `overscroll-contain` is worth having as well,
for scrollers inside a dialog (a long note in a `textarea`) and for any shell
that does not take part in the lock.

## 3. Where the lock hangs off

`useEscapeToClose` is already the place every modal announces that it is open —
19 files register with it, and it already reasons about nesting with a stack. So
the new `useBodyScrollLock` is called from there, and being open now means both
things: Escape closes you, and the page behind holds still.

**Three shells do not register with it** and ask for the lock directly:
`ChangePasswordDialog`, `StudentRecords` (both mounted only while open, so the
flag is implicit) and `AuditCenter`'s decision modal (inline JSX, so it passes
its own flag). `ProjectManagement`'s `EditModal` is a fourth — the one shell in
that file with no Escape registration.

Deliberately *not* done: giving those four `useEscapeToClose` as well. It would
have been one line each and it changes keyboard behaviour Aquaticism did not ask about;
whether every dialog should close on Escape is issue 102's question, not this
one.

### The depth counter

Dialogs nest — a confirmation over the form it belongs to. Locking and unlocking
independently would have the inner dialog's unmount hand the page back while the
outer one is still open, so the lock is depth-counted and releases on the last
close. The previous inline styles are captured and restored rather than cleared:
setting `overflow: ''` would discard a value someone else had set.

The scrollbar's width is added back as padding while the lock holds, or
everything on the page shifts sideways as a dialog opens. Zero on
overlay-scrollbar platforms, where there is nothing to compensate for.

## 4. Verification

On the deployed build. **The control line matters most**: a page that never
scrolls would pass every other check here.

```
CONTROL no dialog:         page 0 -> 2276      ← still scrolls
tall dialog open:          page 21 -> 21       (was 0 -> 1500)
  body overflow while open: hidden
panel scrolled to its end: true
at panel bottom:           page 21 -> 21       ← chaining stopped
after close:               body overflow ""    page 21 -> 2276
short dialog open:         page 43 -> 43
```

And the failure mode worse than the bug — a page left permanently frozen —
checked on the shell that has no Escape handler, so it can only be closed by its
button:

```
edit dialog open:                 hidden
edit dialog closed via Cancel:    (none)
page scrollable again:            0 -> 800
nested confirmation open/closed:  hidden → (none)
```

**Not exercised: two dialogs open at once.** Every release path checked here was
a single dialog, so the depth counter's reason for existing is argued rather than
demonstrated. The route I tried (a records modal with a detail opened on top of
it) did not open a second shell. Worth a look the first time a confirmation is
opened over a form.

## 5. What it must not do

- **Must not rely on `overscroll-contain` alone.** §2.
- **Must not lock and unlock per dialog.** §3 — the inner close would free the
  page under the outer one.
- **Must not clear `overflow` on release.** Restore what was there.
- **Must not forget the scrollbar gap.** A sideways jump on every open is a
  second bug traded for the first.
- **Must not leave the page locked.** Worse than what was reported; §4 checks it
  on the shell with no keyboard escape.
