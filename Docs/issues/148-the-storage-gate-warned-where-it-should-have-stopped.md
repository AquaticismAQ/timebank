# 148 — The storage gate warned where it should have stopped

**Status:** **Fixed and deployed 2026-09-21.** The last code item left open by the
§12 self-assessment. Aquaticism: *"now fix D-08."*

## 1. The gap

`LocalStorageProductionGate` refuses to start when evidence files are on the
local-disk adapter and `timebank.environment` says `production`, `prod` or
`staging`. Anything else — including an unset variable, which resolves to
`unspecified` — produced a log warning and started normally.

Its own javadoc claimed the check was "an allow-list of environments known to be
safe for local disk, not a deny-list, so a new environment name cannot
accidentally inherit permission". The code was the opposite: a deny-list of
three names, with everything else permitted. The gap was named rather than
hidden, with the reason given — failing on `unspecified` would have broken local
development and the test suite — and the fix named too: make those contexts set
the variable, then let the default become fatal.

The shape that matters is a production deployment that simply *forgets* the
variable. That is not an exotic case; it is the most likely way to end up in
production on the test-stage adapter, and it was the one case the gate waved
through. A warning in a startup log is not a control.

## 2. Fix

The check becomes the allow-list it always said it was: **on local disk, any
environment not explicitly recognised as non-production refuses to start.**

Two messages, because "you told me this is production" and "you told me nothing"
need different instructions, but both now end the same way. The recognised
non-production names are unchanged apart from adding `it`, for the integration
suite.

What had to move first, so the default could become fatal:

- **Integration tests** (`@ActiveProfiles("it")`) had no `timebank.environment`
  at all. A new `application-it.yml` sets it to `it`.
- **The Docker stack** already set `local`, and the shared test server already
  sets `test-server` — nothing to do.
- **A bare `mvn spring-boot:run`** now fails with a message saying which values
  are accepted. That is the intended cost: the application already refuses to
  start without six other variables for the same reason, and one more that
  protects children's photographs is not the one to make optional.

## 3. Verification

**Forced on the Docker stack**, because proving a startup gate means actually
failing to start, and doing that to the shared box to watch it break is the
definition of what the local stack is for.

```
TIMEBANK_ENVIRONMENT=uat, evidence on local disk
  container        crash-loops, readiness never comes up
  one-shot run     exit code 1
  log              "timebank.environment is 'uat', which is not a recognised
                    non-production environment, and evidence files are stored on
                    local disk. Refusing to start rather than guessing… Set
                    TIMEBANK_ENVIRONMENT to one of ci, dev, development, it,
                    local, test, test-server, or configure object storage…"

control: the same image, TIMEBANK_ENVIRONMENT=local
  container        up, 0 restarts, health UP
```

The exit code is checked separately from the crash loop on purpose: the class
claims it "aborts with a non-zero exit code", and under `restart: unless-stopped`
a container that dies and is restarted looks the same whatever it exited with.
A one-shot `docker compose run --rm` is what actually answers it.

**And on the deployed server**, which is the other half of the control — a gate
that refuses everything is not a gate:

```
TIMEBANK_ENVIRONMENT=test-server   service active, readiness UP, ping ok
                                   no gate complaint in the startup log
```

**Unit tests**: 23 in `LocalStorageProductionGateTest`, including the five
unrecognised names and `prod-eu` — a name that reads as production to a human,
belongs to neither list, and used to start. Red-checked: restore the permissive
branch and exactly those seven assertions fail while the production and
object-storage ones still pass.

**Integration suite** under the new `it` profile: `ImportExportIT` 6/6,
`ProjectLifecycleIT` 12/12, `HistoricalImportWindowIT` 4/4. `FileServiceIT` has
one failure (`staffDownloadsAreWatermarkedAndTheOwningStudentsAreNot`) which is
pre-existing — confirmed by stashing this change and re-running it to the same
result, rather than assumed from the subject matter.

## 4. What it must not do

- **Must not start on local disk under an unrecognised environment.** §2.
- **Must not fire when real object storage is configured.** The gate is about
  the local-disk adapter, and an unset environment is not its business otherwise.
- **Must not be disarmed by a rename.** The adapter is matched by class name
  across a module boundary; the existing test asserting that coupling stays.
