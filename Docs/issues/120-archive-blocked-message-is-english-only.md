# 120 — The archive-blocked message is English only

**Status:** **Fixed and deployed** 2026-09-20, same day. Filed on Aquaticism's report,
straight after issue 119.
**Reported as:** missing i18n for the error message when archiving a completed
project.

## 1. What is wrong

Archiving a completed project is refused while confirmed students still have
unreviewed service records, and the refusal reads:

> 1 confirmed student(s) still need their service record reviewed before this
> project can be archived. Use Cancel and archive to override.

— in English, on a Chinese page, to a teacher. Two faults in one sentence: the
language, and the fact that it names a button issue 119 removed.

## 2. Why the backend does not simply translate it

**This backend authors no localized copy, deliberately.** It has no
`MessageSource` and no `messages.properties`; its i18n is entirely "resolve the
request's language, then pick the matching per-row column"
(`LanguageResolver` + `LocalizedNames.pick` over `name_zh`/`name_en` pairs).
`iam.application.SlipCopy` writes this decision down at length, and it is the
one place that even authors literals — for text the *server* prints onto paper.

API error messages are English-only by that design, and every user-facing string
otherwise comes from `frontend/src/i18n/*.json`. Translating this one message
server-side would introduce a second translation home for a single caller.

**There is already a pattern for exactly this**, and it is not a special case:
`ServiceRecordServiceImpl` emits `FieldErrorDetail("startAt",
"OVERLAPPING_RECORD", …)` and `OVERLAPPING_RECORD_ID` carrying the clashing
record's id, and `ServiceRecords.tsx` renders its own localized wording around
that data. The server sends *facts and a code*; the client sends *words*.

Contract §2.6 puts `fieldErrors` on the error envelope generally, not only on
`VALIDATION_FAILED`, so a `409` may carry one — and `GlobalExceptionHandler`
already serializes whatever the exception holds, whatever the code.

## 3. What it should do

- `archive()` throws a state conflict carrying
  `fieldErrors: [{ field: "status", code: "UNREVIEWED_RECORDS", message: "<count>" }]`.
  The count is the fact; the code is the discriminator.
- `ProjectManagement.tsx` recognises that code and shows its own message, with
  the count interpolated.
- The English sentence stays (for any non-UI client) but **stops naming
  *Cancel and archive***, which no longer exists on a completed project
  (issue 119 §2 logged this as open; it is closed here). It points at reviewing
  the records instead — the only route left, and the right one.

`ApiException.stateConflict` gains a two-argument form. Nothing else changes:
the status is still `409`, the shape is still contract §2.6's.

## 4. Scope, stated plainly

**This fixes one message, not the backend's error copy in general.** Every other
`ApiException` message is still English — the overlap block's own sentence, the
version-conflict text, the not-found messages. That is the documented design
(§2), and the ones users routinely read should get this same treatment one at a
time, each with a code worth having. A sweep that invented a code per existing
message would mostly be inventing discriminators nobody asked for.

## 5. Verification

The envelope, from the deployed server against a real blocked project:

```
API status: 409 STATE_CONFLICT
API fieldErrors: [{"field":"status","code":"UNREVIEWED_RECORDS","message":"1"}]
API message names the removed button: false
API message: 1 confirmed student(s) still need their service record reviewed
             before this project can be archived.
```

Then through the real UI, clicking Archive on that project, in both languages —
the count comes from the server's own SQL, not from the probe:

```
zh-CN menu:  ["报名名单","查看","复制为草稿","归档"]
zh-CN toast: 暂时无法归档：还有 1 名已确认的同学的服务记录尚未审核，请先在报名名单中完成审核。
en menu:     ["Registrants","View","Copy to Draft","Archive"]
en toast:    Cannot archive yet — 1 confirmed student(s) still need their service
             record reviewed. Review them from the registrant list first.
```

Red-checked by changing the emitted code to something else: the integration
test's assertion fails, and only that one.

## 6. What it must not do

- **Must not add a `MessageSource` for one message.** §2.
- **Must not match on the English text client-side.** That is the same mistake
  issue 118 §6 forbids for the browser's own validation strings.
- **Must not bury the count in the sentence only.** A number a client has to
  parse out of prose is not an API.
- **Must not name an action that does not exist.** §3.
