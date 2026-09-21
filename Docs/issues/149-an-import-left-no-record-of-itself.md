# 149 — An import left no record of itself

**Status:** **Fixed and deployed 2026-09-21.** Aquaticism: *"fix i-09."* The §12
self-assessment's I-09 row, and the last item on §12.8 that is ours rather than
the school's.

## 1. The gap

`Docs`' own rule (§7.4, and I-09 in the checklist) is that **every import and
export** writes an audit row carrying operator, time, IP, file digest and
affected rows. Export did. Import did not.

The data changes were traceable — a roster import writes one `student.create`
per row — but nothing recorded **the import**: who uploaded which file, when,
from where, and how many rows it put in. The file's SHA-256 and the row counts
existed only on the `job` row, which is an operational record of a background
task, not an audit record: it is mutated in place as the job progresses and
carries no IP at all.

A rejected import left no trace whatever. Since issue 146 made a file with any
bad row a complete no-op, "somebody uploaded a roster of 200 real children and
it bounced" is now a thing that can happen and leave nothing behind.

## 2. Why this was not simply a missing call

The commit runs on a background executor. `RequestContext` — which is where
`AuditService` gets the request id, the IP and the user agent — is a
`ThreadLocal` populated by the servlet filter, so on a worker thread every audit
row written during an import already came out with `no-request` and a null IP.
That was true for the per-row `student.create` entries too; the import summary
row would simply have joined them.

So the fix has two halves:

- **Carry the request's identity onto the worker.** `RequestContext.snapshot()`
  on the request thread, `runWithin(...)` around the whole commit on the worker,
  clearing in a `finally` — a pooled thread that kept a stale context is exactly
  the hazard `RequestContext`'s own javadoc warns about. Every audit row an
  import writes now carries the IP and request id of the person who asked for
  it, not just the new one.
- **Write the two entries.** `import.create` on upload, `import.commit` inside
  the commit transaction.

## 3. What gets recorded

| Action | Written | Carries |
| :--- | :--- | :--- |
| `import.create` | On upload, on the request thread | type, SHA-256, byte size |
| `import.commit` | **Inside the commit transaction** | type, SHA-256, rows committed |

`import.commit` sits inside the transaction deliberately, for the same reason the
job's terminal status moved there in issue 146: an audit row saying a file was
imported must not survive a commit that rolled back, and one written afterwards
in its own transaction would.

Recording the upload as well as the commit is what gives a rejected import a
trace. It is also the honest split: the upload is the decision a person made,
the commit is what it did — the same distinction `export.create` draws when it
records the request rather than the completed file.

## 4. Verification

**On the deployed server**, through `GET /audit-logs` — the query a person would
actually run, because a call to `AuditService` that lands nowhere retrievable is
not an audit trail. Two imports: one rejected at validation, one committed.

```
rejected import
  import.create   actor Teacher Wei Chen   perm imports.create   ip <requester>
                  {"type":"students","bytes":165,"sha256":"e0684b60…"}
  (no commit row — nothing happened, and nothing claims otherwise)

committed import
  import.create   actor Teacher Wei Chen   perm imports.create   ip <requester>
                  {"type":"students","bytes":166,"sha256":"fed3f8b6…"}
  import.commit   actor Teacher Wei Chen   perm imports.commit   ip <requester>
                  {"type":"students","sha256":"fed3f8b6…","rowsCommitted":1}
```

**The context bridge, measured before and after on real rows.** `student.create`
is written by the import's worker thread and existed before this change, so it
is the one place a real comparison was available:

```
written by an import earlier today, before the fix:
  student.create   ip (null)           request_id no-request
written by the import above, after it:
  student.create   ip <requester>      request_id req_9v3m0rc585…
```

That request id is the same one on the `import.commit` row, so the summary and
every row the import created are tied to one another and to one person.

**Tests.** 373 unit tests green, including two new ones on the commit
transaction: a committed import records itself with the digest and row count,
and a rejected one records nothing. `ImportExportIT` 6/6, with the assertion made
through `AuditLogQueryService.search` rather than a mock. Red-checked at both
levels — remove the call and the unit test fails alone, and the IT reports
`["import.create"]` where it expected both.

**Left behind:** one probe student (`VH149B…`), deactivated; the rejected
import's job cancelled.

## 5. What it must not do

- **Must not claim an import that rolled back.** §3.
- **Must not attribute an import to nobody.** The IP and request id are the
  uploader's, carried across the thread boundary, not blank.
- **Must not leak the context onto a pooled thread.** Cleared in a `finally`.
