# 060 — Audit Center evidence had no preview and a broken download link; root cause traced to `deploy.sh`'s rsync recipe silently destroying all uploaded evidence on every backend deploy

Status: **Fixed and live-verified (2026-09-15)**

## Symptom

Reported directly by the user: in the Audit Center (admin/teacher), a service
record's evidence tab only offered a plain download link — no in-browser
preview for file types that support it (images, PDFs) — and even that
download link didn't work.

## Root cause

Two separate bugs, both real:

**1. No preview UI at all.** `AuditCenter.tsx`'s evidence tab rendered each
file through `EvidenceLink` — a plain text link with a file icon, click-to-
fetch-then-`window.open`. There was never any inline thumbnail/preview for
images or PDFs, even though the backend already fully supports it
(`GET /files/{id}/content` sends `Content-Disposition: inline` specifically
so a browser can render it in place, not force a download).

**2. The link itself didn't work — for two compounding reasons:**

- `EvidenceLink`'s click handler called `window.open(url, '_blank', ...)`
  only *after* an `await api.files.get(fileId)`. Most browsers' popup
  blockers treat a `window.open` that isn't a direct, synchronous result of
  the click event as unsolicited and silently swallow it — no console
  error, the tab just never opens. This affects every consumer of
  `EvidenceLink`, not just Audit Center (`ServiceRecords.tsx`,
  `StudentRecords.tsx`, `CaseReview.tsx`).
- Investigating why the link 500'd even when it did open uncovered a much
  bigger, genuinely destructive bug: **every evidence file the database
  currently references — all 17 rows, dating back to the very first seed on
  2026-08-07 — was missing from disk** on the shared test server
  (`NoSuchFileException` in `LocalFileStorage.open`, confirmed via server
  log and a direct file-existence check for every `evidence_file` row).
  `backend/scripts/deploy.sh`'s own documented deploy recipe is:
  `rsync -az --delete --exclude 'target/' --exclude '.git/' backend/ <server>:/opt/timebank-backend/`.
  `--delete` mirrors the destination to exactly match the local source
  tree — and `data/evidence-storage/` (where `LocalFileStorage` actually
  writes uploaded files, created only at runtime on the server, never part
  of the source tree) was never excluded. **Every single backend deploy
  following that recipe silently wipes every previously uploaded evidence
  file.** This happened at least twice in this session alone (once for
  issue 059's fix, once for this issue's fix) and, going by the database
  timestamps, has evidently been happening since the first real deploy —
  nobody had previously noticed because nobody tried to re-view *old*
  evidence after a *later* redeploy until now. The content is unrecoverable
  — this is local-disk-only, no-backup, test-stage storage by design (see
  `LocalFileStorage`'s own doc comment).

As a side effect of testing this, also found ~86 leftover ~631-byte dummy
files in `data/evidence-storage/` on the server, not referenced by any real
`evidence_file` row — artifacts from running `mvn verify`'s IT suite
directly on the server from `/opt/timebank-backend` earlier this session
(no `it` Spring profile override for `timebank.file.storage-path`, so
IT-test file writes landed in the same directory as production). Harmless
(trivial size, orphaned test fixture from `TestImages.validJpegBytes()`,
not from real data), left in place — cleanup not done as part of this fix.

## Fix

**Preview UI**: new `frontend/src/app/components/shared/EvidencePreview.tsx`
— a flex-wrapping grid of fixed-size (`w-24`/`h-24`) tiles, one per evidence
file. Fetches each file's fresh signed URL up front (on mount, one request
per file — still never cached beyond this component's own state, per
I3.3) so there's something to render immediately: an image (`mimeType`
starting `image/`) gets a real `<img>` thumbnail; anything else (PDF
included) gets a generic file icon. Every tile is a real `<a href target="_blank">`
with the URL already resolved by render time — clicking it is a normal,
synchronous navigation, which sidesteps the popup-blocker problem by
construction rather than by the open-blank-then-navigate workaround
applied to `EvidenceLink` below. `AuditCenter.tsx`'s evidence tab now
renders `<EvidencePreview files={detailRecord.evidenceFiles} />` instead of
a bare list of `EvidenceLink`s. Tile width is fixed regardless of count, so
the grid stays tidy whether a record has 1 file or many (verified live with
both).

**`EvidenceLink` popup-blocking** (shared component, so this also fixes
`ServiceRecords.tsx`/`StudentRecords.tsx`/`CaseReview.tsx`, not just Audit
Center): opens a blank tab synchronously inside the click handler, before
the `await`, then navigates that already-open tab once the real URL is
known — the standard workaround for browsers blocking a `window.open`
called after an async gap.

**`backend/scripts/deploy.sh`**: added `--exclude 'data/'` to the
documented rsync recipe in the header comment, with an explanatory note —
this is the actual fix for the data-loss bug, going forward. Per direct
discussion with the user: the *already-lost* evidence files' DB rows are
left as-is (not marked rejected/broken) — a future re-seed or manual
cleanup can address the historical gap; this fix only stops new losses.

## Test plan

- `tsc --noEmit`/`pnpm build`/i18n-parity clean.
- Live Playwright pass against the deployed server:
  - Reproduced the original bug first: opened Audit Center's evidence tab
    on a pre-existing record, got a real 500; confirmed via
    `journalctl -u timebank-backend` that it was `NoSuchFileException`, not
    a frontend bug.
  - Confirmed the scope of the data loss directly: queried all 17
    `evidence_file` rows from the live DB, checked each against
    `data/evidence-storage/` on the server — all 17 missing.
  - Since all historical evidence was gone, uploaded fresh evidence
    end-to-end (as `student001`) to actually exercise the new preview UI:
    one record with 1 image, a second with 3 images.
  - As `admin001`/`teacher001`, opened both records' Evidence tabs:
    confirmed a real inline thumbnail renders for the single-file case, and
    a tidy fixed-width row of 3 thumbnails for the multi-file case (no
    layout stretching/breaking); clicked a thumbnail, confirmed a new tab
    opened successfully showing the full image (not blocked, not a broken
    link).

## Resolution

`frontend/src/app/components/shared/EvidencePreview.tsx`: new component
(described above). `frontend/src/app/components/admin/AuditCenter.tsx`:
evidence tab now uses it instead of a bare `EvidenceLink` list.
`frontend/src/app/components/EvidenceLink.tsx`: fixed the popup-blocking
click handler (open-blank-then-navigate pattern). `frontend/src/i18n/en.json`/`zh.json`:
new `common.evidenceLoadFailed` key. `backend/scripts/deploy.sh`: rsync
recipe in the header comment now excludes `data/`, with an explanatory
comment naming this issue. Frontend rebuilt/redeployed
(`frontend/scripts/deploy.sh`); backend not touched by this fix (frontend +
one comment-only script change), no backend redeploy needed for this issue
specifically (it was already redeployed for issue 059 in this session).

No data-repair action taken on the 17 orphaned `evidence_file` rows, per
direct confirmation with the user — they'll continue to 404/500 on
preview/download until a future session addresses them (re-seed or manual
cleanup). The ~86 orphaned IT-test dummy files on the server were also left
in place (harmless, out of scope).

---
*Live-verified 2026-09-15 against the real deployed server via Playwright
MCP. Reproduced the original bug against a pre-existing record first
(real 500, confirmed root cause via server log and a full DB-vs-disk
existence sweep of every evidence_file row — all 17 missing). Since none of
the old evidence could be used to verify the fix, uploaded two fresh test
records as `student001` (one 1-file, one 3-file) specifically to exercise
the new preview UI, then reviewed both in Audit Center as `admin001`:
real image thumbnails rendered correctly in both the single- and
multi-file cases, and clicking a thumbnail opened a new tab with the full
image with no popup-blocking. Did not separately verify a PDF evidence file
(no PDF fixture on hand this session) — the PDF path (generic file-icon
tile, real `<a target="_blank">` link, server already sends
`Content-Disposition: inline`) is reasoned from the image path's proven
behavior and the pre-existing backend contract, not independently
click-proven. Did not verify the mobile/narrow-viewport rendering of the
multi-file grid — reasoned from `flex-wrap` being a standard, well-tested
CSS pattern, not independently screenshotted at a mobile width this
session.*

---

## Follow-ups on the preview tiles (merged here rather than filed separately)

`EvidencePreview` is this issue's component, so later changes to it are
recorded here instead of accumulating near-duplicate issue docs.

**2026-09-15 — the tile caption now shows the full filename.** Reported
directly by the user: the caption should show the whole name, wrapping as
needed, without ever extending past the thumbnail's own border. It was
`truncate` — one line, ellipsised — so
`supervisor-confirmation-signed-by-the-community-centre-director-2026-09-15.jpg`
read as `supervisor-confirm…`, and the only way to see the real name was
the `title` tooltip.

Changed to `break-words`, which breaks at a filename's natural points
(hyphens, dots) and only splits mid-word when a run genuinely cannot fit,
so the caption is bounded by the tile's own `w-24` and can never overflow
it. The grid also gained `items-start`, so tiles whose captions wrap to
different heights stay top-aligned instead of stretching the row.

Verified by measurement rather than by eye, against a deliberately
77-character filename, in both consumers (student record detail and Audit
Center):

| file | caption width | thumbnail border | overflows | lines |
| --- | --- | --- | --- | --- |
| `supervisor-confirmation-signed-by-the-…-2026-09-15.jpg` | 96px | 96px | no | 6 |
| `supervisor-confirmation.pdf` | 96px | 96px | no | 3 |
| `1.jpg` | 96px | 96px | no | 1 |

Full names render with no ellipsis in every case.

**Superseded parts of this doc.** The tiles described above as
`<a target="_blank">` links are no longer links — issue 072 replaced them
with buttons opening an in-app `EvidenceViewerDialog`, and issue 074 then
fixed the 300s signed-URL expiry that made those URLs die while a panel
sat open. The two caveats this doc closes with are also now resolved: the
PDF path was properly exercised in issue 072 (and needed a `blob:` URL,
because `X-Frame-Options: DENY` blocks framing the content URL), and the
multi-file grid was measured above.

## Database repair (2026-09-15, on request, confirmed before writing)

This issue originally ended with the destroyed `evidence_file` rows left
untouched. Asked later to "fix all the records with wrong or missing
evidence file path", the first job was correcting the premise:

- **No path was wrong.** `storage_key` equals `id` for every row, and no
  `service_record.evidence_file_ids` entry was dangling. The path scheme
  was never the problem.
- **17 rows had no file on disk** — this issue's destruction, still
  unrecoverable. No `UPDATE` can point a row at bytes that don't exist.

An early check claimed *all 45* rows were missing; the base path was wrong
(`storage_key` is bare, under `data/evidence-storage`), not the data. It
was caught because files downloaded minutes earlier appeared in the
"missing" list — a reminder to sanity-check a scary result against
something known to be true before acting on it.

Since the files cannot be restored, the only real question was what the
database should *say* instead. That was put to the user, who chose the
fullest option. Executed by a reviewed script, backup first
(`mysqldump` of the affected rows plus a pre-change dump of every
`service_record.evidence_file_ids`, under
`/root/timebank-evidence-repair-<timestamp>/` on the server):

1. **3 `DRAFT` records** had their dead references cleared (`[]`), so the
   student can simply re-upload.
2. **8 rows referenced by nothing were deleted** — 6 already unreferenced,
   plus 2 freed by step 1. One file was referenced by *both* a draft and a
   `REJECTED` record, so it was correctly retained rather than deleted.
3. **9 rows still attached to real records** (4 `APPROVED`, 3 `SUBMITTED`,
   1 `RETURNED`, 1 `REJECTED`) were marked `status='REJECTED'` with
   `rejection_reason` naming this incident. Leaving them `READY` meant the
   database asserting a download that cannot work; `REJECTED` is not a
   perfect fit semantically, but it is the status the UI already renders as
   an explicit "unavailable" tile, and it is *more* truthful than `READY`.

Verified afterwards: 45 → 37 rows; 28 `READY`, every one with its content
present; **0 rows claiming `READY` without a file on disk** — the
invariant worth re-checking after any future storage incident. Drafts read
`[]`. In the Audit Center the affected tiles now render as a warning with
the reason in the tooltip, not a failing preview, and are not clickable
into a viewer that would error.

**Not resolved by this, and deliberately surfaced rather than buried:**
four `APPROVED` records hold credited time coins (200/250/200/250
centi-coins) whose supporting evidence no longer exists. The repair makes
that visible; only a re-seed or a deliberate re-audit can actually settle
it.

