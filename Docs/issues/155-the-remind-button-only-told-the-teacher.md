# 155 — The Remind button only told the teacher

**Status:** **Fixed and deployed 2026-09-22.** Aquaticism: Student Management has a
*Remind* action; clicking it toasts "reminder sent to …" and nothing happens on
the student's side — it should appear in the student's notifications.

## 1. What it did

```tsx
<button onClick={() => toast.success(t('admin.students.toastRemindSent', { name: student.name }))}>
```

That is the whole handler, in both the table row and the mobile card. There is no
request, no endpoint, no notification. The toast is the entire feature — a
leftover from the mock era that was never wired up when mock mode was removed.

It is worse than a missing button. A teacher who clicks it believes they have
nudged a student who is behind on their hours, and stops worrying about them. The
button does not fail; it lies, and it lies in the direction that makes someone
stop paying attention.

## 2. Fix

`POST /students/{id}/reminders` sends a real notification, through the same path
every other notification in the system takes: a seeded bilingual template, one
`notification` row per recipient, rendered in the **reader's** language when they
open it rather than the sender's when it was written.

Four things it has to get right beyond "send something":

- **Scope.** The endpoint resolves the student through
  `StudentService.getDetail(id, auth, …)`, which is the same scope-checked read
  the detail page uses. A teacher cannot remind a student they cannot see; an
  out-of-scope id 404s rather than reporting who exists.
- **A student with no login.** `POST /students` provisions no account — only the
  roster import does — so a hand-added student has a roster row and no way in. A
  reminder to them cannot arrive, so the endpoint refuses with a named code
  rather than reporting success to a teacher for a message nobody will read.
  That refusal is the entire point of this issue; swallowing it would reproduce
  the bug in a new place.
- **The audit trail.** Sending a student a message is an act with a person behind
  it, and it is now recorded as one.
- **Where the code lives.** Finding the student's account needs `UserService`,
  and `UserServiceImpl` already injects `StudentService`. A method *on*
  `StudentServiceImpl` would therefore close a bean cycle and the application
  would refuse to start. `StudentReminderService` sits beside `StudentService`
  instead of inside it and is called straight from the controller, which keeps
  the graph a graph: `StudentController → StudentReminderService → UserService →
  StudentService`. Same shape as `ProjectPublishNotifier` — a notifier in the
  module that triggers the notification, reaching other modules only through
  their application interfaces.

## 3. Verification

**On the deployed server, in a browser, as two different people.**

The reported case, measured at the database with an untouched control:

```
                       total  reminders
28339473 (Alex L.)       133          0
10007944 (Jordan W.)       9          0    ← control, never clicked
  admin001 clicks Remind on Alex L.  → toast "Reminder sent to Alex L."
28339473                 134          1
10007944                   9          0    ← unmoved
```

**Both refusals forced, not reasoned about.** Two students on the live roster
already had the two failing shapes, so neither had to be constructed:

```
Lane H. (HGHZNQ8M)      no account row at all
  → "This student has no login account yet, so a reminder would reach nobody…"
Probe Clean Row (T146C22703)  account PENDING_ACTIVATION
  → "This student's account is not active, so a reminder would reach nobody…"
notifications written to either: 0
```

The second one is the sharper test. That account *exists*; only its status makes
it unreachable, so an implementation that looked up an account and stopped
thinking would have reported success.

**Rendered in the reader's language, not the sender's.** The reminder was sent
from an admin session whose UI was in **English**, and arrives in the student's
Chinese portal as 志愿服务时长提醒 / 老师提醒你关注本学年的志愿服务时长进度…. The
same stored row fetched with `Accept-Language: en` comes back as "A Reminder
About Your Service Hours". One row, two languages, neither of them the sender's.

**Scope, with the control that makes it mean something.** Signed in as
`teacher001`, who sees 10 of the 58 students:

```
std_2f771b02262f3351c687  GET 404 → POST /reminders 404 RESOURCE_NOT_FOUND
std_bbc7a0ec17765e7b6baa  GET 404 → POST /reminders 404 RESOURCE_NOT_FOUND
std_5d7f2d0780cb88b46257  GET 200 → Remind clicked in the UI → sent (135, 2)
```

Both refused students have **`ACTIVE` accounts** — they are perfectly
remindable, and only scope stops them. And the third line is the control:
without a teacher successfully sending one, two 404s prove only that something
was broken.

**The audit trail, through a real query rather than the table.** `GET
/audit-logs?filter[action]=student.remind` returns both sends, each naming who:

```
admin001    student.remind  std_5d7f2d…  students.read  17:19:13
teacher001  student.remind  std_5d7f2d…  students.read  17:22:21
```

Neither refusal produced a row, which is right — nothing happened.

**Left on the server:** two reminder notifications for Alex L. (`28339473`).

## 4. What it must not do

- **Must not report success for a message that was not sent.** §1 is the whole
  issue.
- **Must not let a teacher reach a student outside their scope.** §2.
- **Must not write the message in the sender's language.** Templates render per
  reader, like every other notification.
