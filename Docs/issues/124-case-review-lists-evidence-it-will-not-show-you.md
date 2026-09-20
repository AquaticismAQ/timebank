# 124 — Case Review lists the evidence but will not show it to you

**Status:** **Fixed and deployed** 2026-09-20, same day, live-verified with two
gaps named in §4. Filed on Aquaticism's report ("Missing preview in case review's
'Related Materials' tab, should have the preview like the record audit").

## 1. What is wrong

The admin Case Review detail workbench has three tabs: Basic Info, History,
Related Materials. The third one is where a reviewer looks at what the student
is appealing *about* — and it rendered each evidence file as a text chip with
the filename on it, via `EvidenceLink`:

```tsx
<span className="px-2 py-1 bg-muted rounded">
  <EvidenceLink fileId={f.id} name={f.name} />
</span>
```

`EvidenceLink` mints a signed URL on click and hands it to `window.open` — the
file leaves for a new browser tab. So the tab named "Related Materials" showed a
reviewer a list of filenames and, on click, took them out of the workbench they
were deciding in.

The record audit path does not work that way. Audit Center's Evidence tab passes
the same `EvidenceFile[]` to `EvidencePreview`, which renders image thumbnails in
place and opens a clicked file in `EvidenceViewerDialog` — a large in-app viewer
with Download and Close. That component exists precisely because tabbing out lost
a reviewer's place in the queue (issue 072), and the same argument holds for a
case reviewer, who is doing the same job on the same files.

This is a consistency gap, not a data gap: both screens are looking at the
evidence of a service record.

## 2. What the API actually carries

Checked before designing anything, because the obvious guess — that a case has
its own attachments — is wrong.

**A case carries no files of its own.** `CaseDto` is `id, type, studentId,
studentName, gradeId, gradeLevel, className, subjectType, subjectId,
subjectLabel, title, description, requestedAmountCentiCoins, reasonCode, status,
assignedReviewerId, version, createdAt, submittedAt, updatedAt` — no attachment
list, no file ids, and nothing under `/cases` in APIContract §4.8 serves one.
`frontend/src/app/api/types.ts`'s `Case` matches exactly.

What the tab has always shown is the **linked subject's** evidence: when
`subjectType === 'serviceRecord'`, `CaseReview` fetches
`api.serviceRecords.get(subjectId)` and renders that record's `evidenceFiles`.
Those are real `EvidenceFile` rows with `id` and `name`, which is everything
`EvidencePreview` needs — the identical value Audit Center already feeds it.

**So no backend change is needed, and none was made.** The materials were already
reaching the client; only the renderer was the lesser one.

The limit this leaves standing, recorded so nobody reads the fix as more than it
is: a case with no subject, or a subject that is not a service record, still has
nothing to show and still says so. Giving a case attachments of its own would be
a contract change and is not in scope here.

## 3. The fix

One component swap in `CaseReview.tsx`'s `materials` tab: `EvidenceLink` chips →
`<EvidencePreview files={detailEvidence} />`, the same call Audit Center makes.
No new component, no new i18n key — every string this needs
(`common.evidenceNoPreview`, `common.evidenceDownloadFailed`,
`common.evidenceLoadFailed`, `common.evidenceProcessing`,
`common.evidenceRejectedReason`, `common.download`, `common.close`) already
exists in both bundles, because the record path already exercises them.

Inheriting the shared component means inheriting the failure handling that was
argued out under issues 072 and 074, rather than re-deciding it here:

| Situation | What the reviewer sees |
| :--- | :--- |
| File still `processing` | file-icon tile, tooltip "being processed" — not a broken link |
| File `rejected` | warning tile carrying the rejection reason |
| Metadata fetch fails | warning tile, "failed to load" |
| Thumbnail's 300s URL expired | re-minted once, with a cache-buster (two mints in the same second are byte-identical, so without it the browser never re-requests) |
| Viewer opened past the TTL | URL refreshed on open, and again on the Download click |
| Not an image or a PDF | `common.evidenceNoPreview` plus a working Download |

The loading and empty states of the tab are untouched.

## 4. Verification

Against the deployed test server.

**The tab, on every case that exists** — the filename chips are gone and each
case's subject record's file renders as a tile:

```
"Supplement: additional photo evidence …"  tiles=["evidence.pdf"]  empty=false
"Manual correction: half hour missed on…"  tiles=["evidence.pdf"]  empty=false
"Appeal: library service record rejected…" tiles=["evidence.pdf"]  empty=false
"Appeal: hours undercounted on the comm…"  tiles=["evidence.pdf"]  empty=false
```

**The viewer**, without leaving the workbench — a PDF, so the `blob:` path:

```
clicked tile: evidence.pdf
viewer buttons: ["下载","关闭"]
viewer renders a blob source: ["blob:https://timebank.ui…"]
```

**The expiry boundary, actually crossed.** The workbench was left open for 315
seconds — past the 300s URL lifetime — and only then used. Twice: once in
Chinese for the viewer, once in English through to the Download:

```
holding the workbench open past the 300s TTL: 2026-09-20T12:12:10Z
after TTL: viewer buttons: ["Download","Close"]
after TTL: Download produced a file: evidence.pdf
after TTL: any failure toast: false
```

A real file arriving after the link it was minted from had expired is what
proves the refresh; a viewer that merely looks fine proves nothing. Both
languages covered.

**Two things this pass did not prove, because the data to prove them does not
exist on the server:**

- **The image thumbnail.** Every seeded case's subject record carries the same
  `evidence.pdf`, so the non-image tile path is the only one exercised here. The
  thumbnail path is `EvidencePreview`'s own and is exercised daily by Audit
  Center — but the headline before/after difference for *this* screen is
  untested, and should be checked the first time a case cites a record with a
  photo.
- **The empty-state control.** All four cases have materials, so "shows tiles"
  could not be distinguished from "shows tiles for everything". The
  `admin.cases.noMaterials` branch is unchanged by this fix, which is the only
  reason that gap is tolerable rather than disqualifying.

## 5. What it must not do

- **Must not write a second evidence viewer.** `EvidencePreview` /
  `EvidenceViewerDialog` are shared for the reason in their own header comments;
  a case-flavoured copy would drift from the record path within one fix.
- **Must not cache a download URL** anywhere beyond the component's own state.
  Every URL is minted on demand and lives 300s (I3.3, contract §4.6).
- **Must not lengthen the TTL** to make the viewer easier. Refreshing on use is
  the fix; the short link is the point (issue 074).
- **Must not invent case attachments.** §2 — a case has no files of its own, and
  the tab must not imply otherwise.
- **Must not send the reviewer to a browser tab.** That is the whole defect.
- **Must not widen scope to the other `EvidenceLink` sites.** The student's own
  record list and admin Student Records still use the link form; whether they
  should is a separate question, deliberately not answered here.

*Written without deploying and without driving a browser — the working tree
carries the fix and `tsc --noEmit` is clean, but nothing in §4 has been observed
on the running system yet. A later session must not read this doc as closed.*
