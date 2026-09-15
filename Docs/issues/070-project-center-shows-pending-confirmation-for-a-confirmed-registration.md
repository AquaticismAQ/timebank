# 070 — Project Center reports "Pending Confirmation" for a registration the server already confirmed

Status: **Fixed and live-verified (2026-09-15)**

## Symptom

Found during the full re-verification of the submit → audit workflow the
user asked for after issues 064–069.

A student registers for a project with free capacity. The card's button
immediately reads **"Pending Confirmation"**, implying a teacher still has
to approve the registration. The server had already set the enrollment to
`CONFIRMED` in that very response:

```
GET /api/v1/applications
[{ "projectTitle": "Core Workflow Re-verification — Evening Reading Room",
   "status": "CONFIRMED" }]
```

Two further symptoms from the same cause:

- **No way to cancel.** The "Cancel Registration" button is gated on
  `proj.myApplicationStatus`, which is still stale/absent right after
  registering — so the student who just registered has no visible way to
  back out until they reload the page. That is precisely the gap the
  cancel action was added to close.
- **The seat count doesn't move.** The card still read `0/10 people`
  immediately after a successful registration.

The stale state clears on any reload, which is why it survived earlier
passes: every previous check looked at the list *after* a navigation, and
saw the correct "Registered" label.

## Root cause

`ProjectCenter.tsx` tracked registrations in a local `Set<string>` of
project ids and threw away the server's answer:

```tsx
const [applied, setApplied] = useState<Set<string>>(new Set());

const handleApply = async (proj: Project) => {
  await api.applications.create(proj.id);        // returns the real Application
  setApplied(prev => new Set([...prev, proj.id]));  // ...discarded
  toast.success(...);
};
```

A set membership carries one bit — "this student pressed register" — so
the label function had to guess a status from it, and guessed `pending`:

```tsx
if (applied.has(proj.id) || proj.myApplicationStatus === 'pending')
  return t('student.projects.pendingConfirm');
```

But `EnrollmentServiceImpl.register` never produces `PENDING` on this
path. It locks the project row, counts confirmed seats, and returns
`CONFIRMED` when under capacity or `WAITLISTED` when full:

```java
long confirmedCount = repository.countByProjectIdAndStatus(projectId, EnrollmentStatus.CONFIRMED);
EnrollmentStatus status = confirmedCount < snapshot.capacity()
        ? EnrollmentStatus.CONFIRMED : EnrollmentStatus.WAITLISTED;
```

So the optimistic label was wrong in *both* real outcomes: a confirmed
seat was shown as pending, and — worse — a student who registered for a
full project and was actually **waitlisted** was also told "Pending
Confirmation", hiding the fact that they do not have a place.

There is no missing `refetch()` either; `handleApply` never called one,
unlike `handleCancel` which does.

## Fix

Stop inferring status from a boolean, and use what the server returned.
`applied` becomes a `Map<string, ApplicationStatus>` holding the real
status from `api.applications.create`'s response, and `handleApply` calls
`refetch()` so `enrolledCount` and `myApplicationStatus` refresh from the
server too. The label/disabled/cancel-visibility logic reads the local
status first and falls back to `proj.myApplicationStatus`, so the card is
correct both in the moment and after the refetch lands.

The success toast now also reflects the real outcome — a waitlisted
registration says so rather than claiming a place.

## Test plan

- `tsc --noEmit` and `pnpm build` clean; en/zh key parity verified.
- Live Playwright pass against the deployed server: register for a project
  with free capacity and confirm the button reads **"Registered"** (not
  "Pending Confirmation") with **"Cancel Registration"** available
  immediately and the seat count incrementing, all without a reload;
  cross-check `GET /applications` reports `CONFIRMED`.

## Resolution

`frontend/src/app/components/student/ProjectCenter.tsx` — `applied` is now
a `Map<projectId, ApplicationStatus>` populated from the registration
response, plus a `refetch()` after a successful registration.
`frontend/src/i18n/{en,zh}.json` — `student.projects.waitlistedSuccess`
for the waitlisted outcome.

## Waitlist branch — verified (2026-09-15, follow-up)

Originally flagged as "not covered": reasoned from the backend's
`confirmedCount < capacity` rule but not reproduced live, because no
seeded project was at capacity. Covered properly on request by creating a
throwaway **capacity-1** project ("Waitlist Verification — Single Seat
Shift") and filling it:

| Step | Result |
| --- | --- |
| Student A registers | `0/1` → `1/1`, button **"Registered"** + Cancel, toast "Registration successful" |
| Student B sees the full project | button reads **"Join Waitlist"** |
| Student B registers | button reads **"On Waitlist"** + Cancel — *not* "Pending Confirmation"; seat count correctly stays `1/1` |
| Toast | "You are on the waitlist … a place will open up only if someone cancels." |
| `GET /applications` | `WAITLISTED` |
| After a full page reload | still **"On Waitlist"** — so the server-derived path (`myApplicationStatus === 'waitlisted'`) maps correctly too, not just the optimistic one |

Two guards worth recording as verified at the same time, since a
waitlisted student must not be able to claim hours:

- The waitlisted project is **absent** from the record wizard's
  "Select a project you're confirmed for" dropdown.
- Bypassing the UI entirely and `POST`ing a project-sourced record against
  it is refused server-side: `400 INVALID_REQUEST — "You do not have a
  confirmed registration for this project."`

The throwaway project was archived afterwards.

This follow-up is also what surfaced **issue 071** (cancelling a
registration permanently blocked re-registering), found on the cancel path
this issue had touched.
