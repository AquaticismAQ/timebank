# 154 — The sidebar badges were fetched once a sign-in

**Status:** **Fixed and deployed 2026-09-22.** Aquaticism: every sidebar item with a
number in a badge should update after the user acts — reading a notification
leaves the count unchanged until the browser is refreshed.

## 1. Why it never updated

```tsx
useEffect(() => {
  api.notifications.unreadCount().then(setUnreadCount).catch(() => {});
  …
}, []);
```

An empty dependency array is "once per mount", and the layout is the route
*parent*: it does not remount when you move between the pages inside it. So this
runs once per sign-in. The number was not stale for a moment — it was stale for
the session.

That is worse than showing nothing. A badge is read as a fact about right now,
and one that disagrees with the page it points at teaches people to distrust all
of them: a reviewer who has just cleared a queue and still sees "7" learns that
the number means "some time today".

Four badges have the problem, not just notifications — Audit Center, Case Review
and Proposal Review count queues that the same session empties.

## 2. Fix

Two halves, because they answer different questions.

**The precise half.** A small context (`BadgeCounts`) exposes one thing —
`refresh()` — which the page that just changed something calls. Marking a
notification read, deciding a case, a proposal, or a record, singly or in a
batch, now says so immediately.

**The cheap half.** The layout also re-reads its counts on every navigation.
That covers what no call site can: a decision made on a different screen, in
another tab, or by a colleague while you were reading.

### Why a refresh signal and not a shared number

The page that changed something does not know the new count. Marking one
notification read is not "minus one" if another has arrived since; approving a
record can clear two queue entries. Only the server knows. So the page reports
*that something changed* and the layout asks — a local decrement would drift, and
drift in a badge is invisible until somebody counts.

### One thing that came along with it

The Student Portal's header hours and coins refresh on the same signal. They are
not badges and were not in the report, but they are the same staleness sitting an
inch away: a student whose record was just approved should not see yesterday's
total beside a corrected badge.

## 3. Verification

**On the deployed site, in a browser, with a control each time.**

The reported case — reading one notification:

```
badge 通知中心 22
  click one unread notification
badge 通知中心 21          no reload
```

A queue badge, forced rather than waited for: a case filed through the API so
there was something real to decide, then resolved in the workbench.

```
badge Case Review 7     Audit Center 10
  resolve the case
badge Case Review 6     Audit Center 10   ← the control: unrelated badge unmoved
```

The control matters more than the change here. A `refresh()` that re-read
everything and redrew every badge would look identical on the badge under test,
and this shows the other one holding still.

**A wrong turn worth recording.** The first attempt clicked a notification that
was *already read*, so nothing changed and the fix appeared not to work. The
network panel is what settled it: the `PATCH` and all four count re-reads had
fired correctly — the server's answer simply had not changed. Filtering to
**unread** first is what made the test able to fail.

The second attempt then found no decision buttons at all, because that browser
was signed in as a teacher, who holds `cases.read` but not `cases.decide`. That
is the permission model working; it is also a reminder that "the button is
missing" has two very different explanations.

**Left on the server:** one resolved case titled `[BADGE-154]`, since a decision
is terminal.

## 4. What it must not do

- **Must not compute the new count in the browser.** §2.
- **Must not leave a count only a reload can fix.** §1.
- **Must not fail a page that has no layout above it.** The context defaults to
  a no-op rather than throwing, since a badge is decoration to the page that
  signals it.
