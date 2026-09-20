# 139 — A demo launcher ran the next demo too

**Status:** **Fixed 2026-09-20.** Aquaticism's report, from a live run: the project
demonstration finished and the proposal demonstration started on its own.

## 1. Cause

`demo-project.sh` ended with `exec pnpm e2e "$@"` — no flow id. `run.ts` reads
"no flow id" as "run every flow in turn", so the project demo was followed
straight into the proposal one. `demo-proposal.sh` already named its flow, which
is why only one launcher did this.

## 2. Fix

One line:

```sh
exec pnpm e2e project-workflow "$@"
```

Both launchers now name their flow. One script, one demonstration.

## 3. What was tried and reverted

I first also rewrote `run.ts` so the `demo` profile would refuse to run with no
flow id, on the reasoning that the launchers are gitignored (they hold
credentials) and so a fix there is not version-controlled. Aquaticism's answer: that is
solving a problem nobody raised. **The launcher is the thing you run; naming its
flow is the whole fix.** `run.ts` and the e2e README are back at HEAD, and the
harness's documented behaviour — no flow id means all flows — is unchanged.

Recorded because the reasoning will look tempting again: a guard is worth
building when a convention is *already documented and still missed* (issue 138's
list check); it is not worth building for a one-line bug in a file one person
runs by hand.

## 4. What it must not do

- **Must not leave a demo launcher's flow implicit.** §1.
- **Must not change the harness default** to fix a launcher. §3.
