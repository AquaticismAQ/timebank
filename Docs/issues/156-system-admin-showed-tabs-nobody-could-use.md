# 156 — System Admin showed tabs nobody could use

**Status:** **Fixed and deployed 2026-09-22.** Aquaticism: the *Audit Logs* tab should be
hidden for a non-student user who does not hold the audit-log permission (today
only a view permission exists) — and then, the same for System Admin's other
tabs.

## 1. What it did

Four of System Admin's nine tabs were in the list unconditionally:

```tsx
{ key: 'rules',         label: …, icon: Shield },
{ key: 'logs',          label: …, icon: FileText },
{ key: 'categories',    label: …, icon: Tags },
{ key: 'organizations', label: …, icon: Building2 },
```

The other five already asked (`users.read`, `roles.read`, `imports.create`, any
`exports.*`, and Settings' sysadmin-only blocks). So the mechanism existed and
four tabs had simply never been wired into it.

For a teacher signed in today that means two dead tabs. **Audit Logs** renders
its filters, its sort controls and an empty list, because every request behind it
is a `403` — the screen looks like a log with nothing in it, which is a very
different claim from "you cannot see this". **Service Categories** is worse in
kind: it is a management screen, so it offers create, rename and retire actions
that all fail at the server.

A tab that opens onto a permission failure is not a smaller version of the
feature. It tells the reader the system has no audit entries, or that a category
they just tried to add did not save for some unexplained reason.

It also **asks the server for data it cannot have**: the audit list and its facet
dropdown fetch on page mount, not on tab click, so a teacher opening System
Admin fires two `403`s before touching anything.

## 2. Fix

Each tab now names the permission its content actually needs:

| Tab | Gate | Why that one |
| :--- | :--- | :--- |
| Users | `users.read` | already |
| Roles | `roles.read` | already |
| Rule Settings | `rules.read` | reading rule versions; editing is separately `rules.manage` |
| Data Import | `imports.create` | already |
| Data Export | any `exports.*` | already |
| Audit Logs | `auditLogs.read` | the reported case |
| Service Categories | `rules.manage` | it is a management screen; reading categories alone is every authenticated user |
| Partner Organizations | `organizations.read` | |
| Settings | **none** | see below |

And the three fetches behind gated tabs no longer run for someone who cannot read
them — the audit list, its facets, the user page and the role list.

### Why Settings stays ungated

Every row in it is either readable by any authenticated user (`GET /school/info`,
the version, the academic-year start from `GET /lifecycle/config`) or already
hidden behind a sysadmin role check of its own (the Phase 3/4 feature toggles,
the School Info edit button). There is a `settings.read` permission, but nothing
on this tab is behind it. Gating the tab on it would hide facts a teacher is
allowed to see, which is the opposite of the fix.

It is also what keeps the tab list from ever being empty, so no role can land on
System Admin and find a page with no tabs at all.

### Hidden, not disabled

Consistent with what Settings' own feature toggles already do and say: a school
admin cannot act on them, and a dead switch invites the belief that someone
could. The same reasoning applies to a whole tab.

The control is still the server's `@PreAuthorize`, not this list. Hiding a tab is
a courtesy to the reader, never the boundary.

## 3. Verification

**On the deployed site, measured before and after the same deploy, as two
different people.**

Signed in as `teacher001`, who holds `rules.read` and `organizations.read` and
holds none of `auditLogs.read`, `rules.manage`, `users.read`, `roles.read`,
`settings.read` — read from `GET /me` on the live server rather than from the
seed file, so the permissions under test are the ones the server actually grants:

```
before   Rules · Data Export · Audit Logs · Categories · Organizations · System
after    Rules · Data Export ·                           Organizations · System
```

And the requests the page fires on mount, from the network panel:

```
before   403  /audit-logs
         403  /audit-logs/facets
         403  /roles
         403  /users
after    (none — all four gone)
         200  /rules            ← the control: still fetched
```

That last line is the part that matters. Turning every list off would produce an
identical "no more 403s", and `/rules` still returning 200 for the same teacher
is what shows the gates read a permission rather than a blanket.

**The tab that stayed is not a stub.** Rule Settings renders the real published
rule version for the teacher — rounding, minutes per coin, both stages'
requirements — with its "only system admins can modify rules" notice. Hiding it
would have removed something they can use.

**The admin control:** as `admin001`, all nine tabs are present, and both newly
gated tabs still work — Audit Logs loads its 4,634 rows and its action facets,
Service Categories loads its six categories.

Not tested by writing: the claim that Service Categories' actions would have
403'd for a teacher rests on `rules.manage` being absent from `GET /me`, which is
the same check the server makes. Attempting a real create to prove it would have
left a category on the shared server if the reasoning had been wrong in the
opposite direction.

## 4. What it must not do

- **Must not hide a tab the user can use.** A teacher keeps Rule Settings
  (read-only), Data Export, Partner Organizations and Settings.
- **Must not leave a visible tab that 403s.** §1.
- **Must not fetch for a hidden tab.** §2.
- **Must not be treated as the permission boundary.** The server's checks are
  unchanged and remain the only enforcement.
