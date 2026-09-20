# 138 — My Cases has no search, filter or sort

**Status:** **Done and deployed** 2026-09-20. Aquaticism's report: the student's My
Cases page should keep the same style as the other lists, which have
search/filter/sorting.

## 1. What it had

A row of status chips, and nothing else. No search, no second filter, no sort, no
page-size control, and an empty state that said the same thing whether the list
was genuinely empty or a filter had hidden everything.

`Docs/optimization/05-SearchFilterSortConsistency.md` settled this shape in
August and every other list follows it. My Cases arrived afterwards (issues
079–082) and missed it — which is the usual way a convention decays: not by being
overruled, but by the next screen not knowing about it.

## 2. Nothing new was needed server-side

`GET /cases` already accepted `search`, `filter[status]`, `filter[type]` and
sorting on `id/status/createdAt/submittedAt`. **The page simply never sent
them.** So this is a frontend change that adds no endpoint and no query.

That matters for how it was done: every control goes to the server. Filtering
`items` in the browser would have produced a page that looks identical and
searches one page of results — the kind of feature that is worse than none,
because it answers confidently and wrongly once the list outgrows a page.

## 3. What it has now

Exactly Service Records' furniture, deliberately, down to the class names:

- **Search** (`ListSearchInput`), committed on Enter/blur rather than per
  keystroke.
- **Status** and **Type** selects — §2.4's layout: search alone below `lg:`,
  filters as an N×1 grid beside it, one row at `lg:` and up.
- **Sort** by Submitted / Created / Status, an N×1 grid with no breakpoint
  condition (§2.5), clicking the active key flipping direction.
- **Empty state** that distinguishes "you have no appeals" from "nothing matches
  these filters" and offers a way out of the second (§2.6).
- **Page size**, which the list had been rendering without.

The type options reuse the student-side wording (`student.records.appeal` /
`.supplement`) rather than the admin twin — same two concepts, and this page
belongs to the student.

## 4. Verification

The proof is in the request, not the screen: a client-side filter would look the
same and be wrong.

```
controls        search 1 · selects 3 · sort ["提交时间 ↓","创建时间","状态"]
sort click      → sort=-createdAt
status filter   → filter[status]=DRAFT
type filter     → filter[type]=SUPPLEMENT
search          → search=zzz-no-such-case
empty state     offers a way out;  clearing → 1 row, every param dropped
page errors     []
```

Each control was exercised on the deployed page and each one changed the query
the server received.

## 5. What it must not do

- **Must not filter in the browser.** §2.
- **Must not invent a second layout.** §3 — the convention exists precisely so
  two lists do not drift.
- **Must not say "no cases" when a filter is hiding them.** §3.
- **Must not add an endpoint.** §2 — everything needed was already there.
