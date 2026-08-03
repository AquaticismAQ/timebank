# B3F · Cloud File Storage — MinIO/OSS Migration

> Follow-up to B3 (`05-Backend-B3-CoreLoop.md`). Appended rather than inserted into the B0–B7 sequence — per `00-Status.md` §10's "never renumber" rule — since it was scoped out of B3 after B3.4/B3.5 had already been built against a local-disk adapter for test purposes (`00-Status.md` §14/§15).

| | |
| --- | --- |
| **Depends on** | B3.4/B3.5 (file module, built against local disk) |
| **Blocks** | Nothing hard-blocking — a parallel/deferred track like B7 — but should land before pilot go-live handles evidence at real volume; feeds B6.5 (storage lifecycle tiering) and B7 (backup/restore scope) |
| **Effort / calendar** | ~2 pw (not yet scheduled) |
| **Design refs** | `Design.md` D9 (Evidence Storage), §16.3 (retention/lifecycle) |

## 1. Goal

Replace the local-disk `FileStorage` adapter B3.4 used for test-stage development with a real S3-compatible object store — MinIO on infrastructure sized for it, Aliyun OSS in production — matching D9's original recommendation, with **zero change to any calling code**: the `FileStorage` port (`store`/`open`/`size`/`delete` against an opaque `storageKey`) is the seam, and only the adapter behind it changes.

## 2. Why this was split out of B3

B3.4/B3.5's exit criteria (a `PENDING`/`PROCESSING`/`REJECTED` file can't be attached to a submission; evidence download issues a short-lived URL and audits cross-student access) do not depend on *which* storage backend sits behind the `FileStorage` port — they were fully provable against local disk, and were proven that way (`00-Status.md` §15). What local disk cannot provide is D9's actual reasons for choosing S3-compatible storage in the first place: no file-serving load on the app instance, storage-class lifecycle tiering, and headroom for the corrected volume estimate (~200,000 files/year, ~800 GB–1 TB over ten years, `Design.md` D9). Building B3.4 against local disk was an explicit, scoped trade: prove the feature now, revisit the storage decision as its own checkpoint once host/credential decisions are ready — not a reversal of D9.

## 3. Scope

**In:** resolving where MinIO actually runs (the shared test server's own disk/RAM constraints, found in B0 — 2 CPUs, 3.8 GB RAM, ~5.2 GB free disk — were the reason this was deferred rather than installed alongside B3.4; this phase must resolve that, not defer it again), an `S3FileStorage` adapter (presigned PUT/GET) behind the existing `FileStorage` port, migrating any evidence rows/bytes created during the B3.4 local-disk test period, Aliyun OSS credentials and endpoint configuration for production, and storage lifecycle tiering (Standard → Infrequent Access → Archive, `Design.md` §16.3).

**Out:** real AV/virus scanning — still a separate, tracked gap (`FileScanner`'s port exists; only magic-byte/MIME/size checks are real). Resolving it is a work item here (B3F.6) but may itself be re-deferred with a named trigger rather than solved by this phase.

## 4. Work Items

| ID | Item | Deliverable |
| --- | --- | --- |
| B3F.1 | Resolve the MinIO host question | Either a dedicated host or a concrete, re-verified resource budget on the shared test server — not a repeat of B3.4's deferral |
| B3F.2 | `S3FileStorage` adapter (presigned PUT/GET) | Implements the existing `FileStorage` port; `LocalFileStorage` remains available for local dev/CI |
| B3F.3 | Migrate B3.4-era local-disk evidence | Existing `evidence_file` rows' bytes copied to the object store, `storage_key` updated in place — no `service_record.evidence_file_ids` reference changes |
| B3F.4 | Aliyun OSS production credentials | Environment-injected, never committed (matches every other secret in this project) |
| B3F.5 | Storage lifecycle tiering | Standard → IA (~90 days) → Archive (~1 year), per `Design.md` §16.3 |
| B3F.6 | Real AV scanning — resolve or explicitly re-defer | If deferred again, name the owner and the trigger, the same discipline as B0.14's CI trigger |

## 5. Exit Criteria

- [ ] Evidence upload/download round-trips through the real object store end to end, demonstrated on the real frontend with the mock client disabled.
- [ ] Every B3.4-era locally-stored file is migrated (or explicitly, deliberately discarded — this is test data, not production evidence).
- [ ] Production OSS credentials are environment-injected; nothing committed.
- [ ] Lifecycle tiering rule is active and verified against a real object (not just configured).
- [ ] The AV-scanning gap has a decision recorded, not a silent carry-forward.

## 6. Hand-Off

B6.5's storage lifecycle tiering and B7's backup/restore scope extend to cover evidence objects once this phase lands — before it, both are implicitly database-only, since evidence bytes live on local disk outside any backup scope B7 defines.
