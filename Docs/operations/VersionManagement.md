# Version management

TimeBank had no version-tracking mechanism before 2026-08-20 — no git tags,
no `CHANGELOG.md`, and both `backend/pom.xml`'s `<version>` (stuck at
`0.1.0-SNAPSHOT` since project init) and `frontend/package.json`'s
`"version"` (still the Vite scaffold default, `0.0.1`) were dead fields
nothing read. This doc is the scheme that replaces that: one product
version, a real API to read it, and a release checklist to keep it honest.

## The scheme

**One semver number for the whole product**, `MAJOR.MINOR.PATCH` (optional
`-SNAPSHOT` suffix for an in-progress, not-yet-released state), even though
frontend and backend are separately built and separately deployed
(`frontend/scripts/deploy.sh` / `backend/scripts/deploy.sh`). They ship
together often enough, and the UI only ever needs to show one number, that
maintaining two independently-incrementing version tracks would be
overhead with no real consumer — nothing in this repo needs the frontend
and backend versioned apart from each other yet.

- **`backend/pom.xml`'s `<version>` is the source of truth.** It's what
  actually reaches production: `spring-boot-maven-plugin`'s `build-info`
  goal (added to the `<build>` in the same change that introduced this doc)
  stamps it into `target/classes/META-INF/build-info.properties` at every
  `mvn package`, which Spring Boot auto-detects as a `BuildProperties` bean.
  `GET /api/v1/system/version` (`SystemVersionController`, contract §4.17)
  reads that bean and returns it — the version is never hand-typed into
  application code a second time.
- **`frontend/package.json`'s `"version"` is kept in lockstep by hand** at
  every release, purely so the frontend's own manifest doesn't lie about
  what it ships alongside — but nothing in the running app reads this
  field. Every UI surface that displays a version (sidebar, System Admin →
  System Info) fetches it live from `GET /api/v1/system/version` instead.
  This matters: a frontend deployed slightly out of step with the backend
  (or vice versa — nothing enforces atomic deploys) still shows the real
  backend's version, not a stale bundled number.
- **Bump rule of thumb** (loose, not tool-enforced — this is a
  single-institution internal system, not a published library other teams
  pin against): `PATCH` for bug fixes and small UI tweaks (issue-doc-sized
  changes), `MINOR` for a shipped feature/phase-plan (an `Docs/optimization/
  0N-*.md` or an implementation-plan phase landing), `MAJOR` reserved for a
  genuine breaking milestone (e.g. the Phase 3 POV launch). Don't overthink
  a given bump — consistency matters more than precision here.

## Release checklist

1. Bump `backend/pom.xml`'s `<version>` and `frontend/package.json`'s
   `"version"` to the same new number, in the same commit as the change(s)
   being released (or a small dedicated commit if bundling several fixes).
2. Commit normally (this repo's existing identity/attribution conventions
   apply — see `CLAUDE.md`).
3. Tag the release commit: `git tag -a v<version> -m "<one-line summary>"`.
   Annotated, not lightweight, so the tag carries a message and its own
   object. Tags are **not pushed automatically** — this repo's git-safety
   convention treats pushing (commits or tags) as a shared-state action
   that needs explicit confirmation, same as any other push.
4. Deploy backend first (`backend/scripts/deploy.sh`), then frontend
   (`frontend/scripts/deploy.sh`) — same order this repo's other
   multi-part deploys already use.
5. Verify live: `GET /api/v1/system/version` returns the new number: the
   sidebar (desktop) and System Admin → System Info should show it within
   a page refresh.

No CI automates any of this yet (this repo has no CI/CD pipeline at all as
of this writing — deploys are the manual scripts above, run by a
collaborator). If one is ever added, tagging on release and verifying the
version endpoint post-deploy are the two steps worth automating first.

## Where things live

| What | Where |
| --- | --- |
| Source-of-truth version number | `backend/pom.xml`'s `<version>` |
| Build-time stamping | `spring-boot-maven-plugin`'s `build-info` execution in `backend/pom.xml` → `target/classes/META-INF/build-info.properties` |
| Runtime read | `cn.timebank.common.web.SystemVersionController` (`GET /api/v1/system/version`) |
| Contract | `Docs/integration/APIContract.md`/`_CN.md` §4.17 |
| Frontend client method | `api.system.version()` (`frontend/src/app/api/client.ts`) |
| Frontend display: sidebar | `frontend/src/app/components/layout/AppShell.tsx` — `SidebarContent`'s brand row, desktop sidebar only (`version` prop only passed to the persistent `<aside>` instance, not the mobile hamburger drawer) |
| Frontend display: System Admin | `frontend/src/app/components/admin/SystemAdmin.tsx` — System Info panel's "Version" row |
| Frontend manifest (kept in lockstep, not read at runtime) | `frontend/package.json`'s `"version"` |

## Baseline

`v0.1.1` (2026-08-20) is the first version tracked under this scheme — it
covers this doc, the version API, and the two display fixes together (see
`Docs/issues/032-*.md` for the UI-display half). Everything shipped before
it (B0–B7, I0–I3, `Docs/optimization/0N-*.md`, issues 001–031) predates
version tracking and has no tag; `integration-test/STATUS.md`'s dated fix
history is the closest thing to a changelog for that period.
