# 074 — Evidence download/preview fails after 5 minutes: the signed URL is minted once and never refreshed

Status: **Fixed and live-verified (2026-09-15)**

## Symptom

Reported directly by the user, on issue 072's new viewer:

> the 'supervisor-confirmation.pdf' can not download, browser downloader
> show 'site wasn't available'

Not PDF-specific, and not a transport problem. Reproduced by decoding the
token on the Download link and replaying the request after its lifetime:

```
GET /api/v1/files/{id}/content?token=…     (32s past the token's expiry)
400 INVALID_REQUEST — "Download link has expired."
```

Chrome surfaces a failed download of a same-origin URL as
"site wasn't available", which reads like a network/DNS failure and hides
the real 400.

Clicked immediately the same button works (verified with 294s left on the
token) — which is exactly why the original verification pass missed it: I
tested Download on an image seconds after opening the dialog, and never
left a panel open long enough to cross the boundary.

## Root cause

`DownloadTokenService` issues an HMAC-signed, time-boxed token with
`TTL_SECONDS = 300` — B3.5's stand-in for a real object store's presigned
URL (contract §4.6), whose whole job is "bounding how long a leaked link
stays useful". That five-minute lifetime is a deliberate security
property, not an accident.

`EvidencePreview` fetches each file's metadata — and therefore mints that
URL — **once, when the tile mounts**, and then holds it for the lifetime of
the component. Nothing re-issues it. So every use of that URL more than
five minutes after the evidence panel opened fails:

- **Download** → the browser download error the user hit.
- The viewer's image `<img src>` → broken image.
- The viewer's PDF `fetch` → falls through to "can't be previewed".
- The tile thumbnail itself, if it re-renders after the boundary.

This predates issue 072 — `EvidencePreview` has minted the URL on mount
since issue 060. What 072 changed is how easy it is to hit: evidence is
now something you *linger* in (open a record, read the fields, study the
timeline, then click a thumbnail, then click Download), and there is now a
visible Download button that fails loudly instead of a link you clicked
straight away. A reviewer working carefully through one record was always
going to cross five minutes.

## Fix

Re-issue the URL at the moment of use. The TTL is deliberately short and
is **not** lengthened — the point is that a leaked link dies quickly, and
trading that away to avoid a re-fetch would be the wrong trade on an API
serving minors' evidence.

- **The viewer refreshes on open.** `EvidenceViewerDialog` now takes the
  `fileId` and re-fetches metadata when it mounts, so the image/PDF render
  from a URL that is seconds old regardless of how long the panel behind
  it has been sitting there.
- **Download re-fetches on click.** The button is now a handler that
  fetches fresh metadata and then triggers the save through a programmatic
  anchor, so it works however long the dialog has been open. (An `await`
  before a download is safe — unlike `window.open`, a download triggered
  from a user gesture isn't popup-blocked, which is the trap issue 060
  hit with `EvidenceLink`.)
- **Stale thumbnails self-heal.** A tile whose `<img>` fails to load
  re-fetches its metadata once and retries, so a queue left open past the
  boundary doesn't fill with broken images. The retried `src` carries a
  `&_retry=1` cache-buster — see "Proving the retry" below for why that is
  load-bearing rather than decoration.

Failures are surfaced rather than silent: a download that can't be
prepared raises a toast instead of doing nothing.

## Test plan

- `tsc --noEmit`, `pnpm build`, en/zh parity — clean.
- Live against the deployed server, driving the clock rather than
  assuming:
  - **Diagnosis first**: decoded the token on the live Download link
    (`fileId:expiry:hmac`), replayed the request 32s past its expiry, and
    got `400 "Download link has expired."` — the 400 Chrome was hiding
    behind "site wasn't available". The same click seconds after opening
    had succeeded with 294s left on the token, which is what made this
    look intermittent.
  - **The failing case, fixed**: opened the PDF viewer, held it open
    **328 seconds** (past the 300s TTL), clicked Download — succeeded,
    and the saved file is a real 642-byte `%PDF-1.4`, byte-for-byte the
    size of the source, not an error page.
  - **Stale panel, fresh open**: with the evidence panel 351s old (its
    tile URL already dead), clicking the tile still rendered the PDF —
    the refresh-on-open path.
  - **Image path unaffected**: student detail view → thumbnail →
    full-size image loaded (1268×1032) and Download saved `1.jpg`.

## Proving the retry (and the second bug it exposed)

The thumbnail self-heal was initially shipped **unproven** and flagged as
such — "a one-line retry, inference not proof". Asked to prove it, it
turned out not to work.

Forcing the failure deterministically (Playwright `route` returning the
exact `400 "Download link has expired."` body for the *first* content
request only, passing everything after through) showed:

| | metadata fetches | content requests | thumbnail |
| --- | --- | --- | --- |
| Retry as first shipped | 3 | **1** (the forced 400) | `naturalWidth: 0` — broken |
| After the cache-buster | 3 | **2** — forced 400, then `…&_retry=1` | `naturalWidth: 1268` — recovered |

The `onError` handler *was* firing and the metadata *was* being re-fetched.
The retry still did nothing, because of a property of the token itself:
`DownloadTokenService.issue` signs `fileId + ":" + (now + 300)`, so two
mints **within the same second produce a byte-identical token** — and
therefore an identical URL. React re-rendered with an unchanged `src`, so
the browser never re-requested.

That is not purely an artifact of the fast synthetic retry: it is a real
latent hole, since it makes the retry silently conditional on a second
boundary being crossed between the two mints.

Fixed by appending `&_retry=1` to the retried `src`, which guarantees a
fresh request regardless of token identity. The endpoint reads only
`token` (`@RequestParam String token`), so the extra parameter is ignored
server-side.

Happy path re-checked afterwards: an evidence tile that loads first time
issues exactly **one** content request, with no `_retry` parameter — the
cache-buster only appears on an actual failure.

## Resolution

`frontend/src/app/components/shared/EvidenceViewerDialog.tsx` — takes
`fileId`, refreshes metadata on open, and re-fetches on download click.
`frontend/src/app/components/shared/EvidencePreview.tsx` — passes `fileId`
through and retries once on thumbnail load failure.
`frontend/src/i18n/{en,zh}.json` — `common.evidenceDownloadFailed`.
