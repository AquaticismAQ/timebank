# e2e

Drives complete TimeBank workflows through a real browser against the real
deployed server. It has two jobs, and they pull in different directions, so it
serves both from one set of phases rather than two scripts that would drift:

- **Demonstration** — show school staff, full screen and narrated in Chinese,
  that a student's volunteer service becomes recognised hours and time coins.
- **Verification** — prove the same workflow still works, including the reject
  and return branches and the audit trail, with nobody watching.

```bash
pnpm install
pnpm e2e                                  # demo profile, visible, paced
pnpm e2e --profile verify --headless      # unattended regression shape
pnpm e2e --list                           # flows, profiles, gates
pnpm selftest                             # the harness's own pure-logic checks
```

## Profiles and gates

A **gate** is a named capability; a **profile** is a set of gates. A phase
declares the gate it needs, and the runner filters. `branch:reject` is not a
different code path through the review step — it is the same step the demo
profile happens not to schedule.

| Profile | Gates |
| :--- | :--- |
| `demo` *(default)* | `core` `tts` `pace` |
| `verify` | `core` `branch:return` `branch:reject` `audit` |
| `full` | everything |

`--gate X` adds one, `--no-gate X` removes one.

## Configuration

Nothing here contains a hostname, username or password.

| What | Where it comes from | Override |
| :--- | :--- | :--- |
| Server | `TIMEBANK_BASE_URL`, else `~/.timebank.env` | — |
| Accounts | `Docs/TestAccounts.md` (gitignored) | `E2E_ADMIN_USERNAME` / `_PASSWORD`, `E2E_STUDENT_USERNAME` / `_PASSWORD` |
| Evidence files | `../test-evidence` beside the repo | `EVIDENCE_DIR`, `--evidence-dir` |
| Browser binary | `PLAYWRIGHT_BROWSERS_PATH` (set in `~/.bash_profile`) | — |

Anything missing fails immediately, naming what to set. Playwright is pinned to
**1.63.0** because its bundled Chromium is revision **1243**, which is already
installed under the shared browsers path — check there before ever running
`playwright install`.

## What a run leaves behind, and why it is not cleaned up

A run performs the real workflow, so it writes real rows: a project, an
enrolment, service records, review decisions, a ledger transaction. Almost none
of it can be undone — projects only cancel or archive, records are immutable once
submitted, transactions are append-only. That is a deliberate property of the
system, not something a test suite should work around.

So everything a run creates is prefixed `[AUTO]` and carries the run id, and the
residue stays. Two consequences worth knowing:

- **Each run permanently consumes service-record time windows.** Approved and
  returned records block an overlapping window forever; rejected ones do not.
  The demo profile costs one window, `verify` costs two.
- **Capacity therefore comes from dates, not deletion.** `serviceDate` may be
  today or any past day, so the allocator takes today first and walks backwards
  only when today is full. It says so when it drifts — under heavy re-running you
  will see records dated earlier than the project they belong to, which the
  backend permits and which looks odd on screen.

## The browser is left open

Pass or fail. The end state — the approved record, the changed balance — is the
point of a demonstration, and it is what you most want to see after a failure.
The process therefore **waits for Ctrl-C** rather than exiting.

`--close` opts out, and `--headless` implies it. For anything unattended use
`--profile verify --headless`, or the job will hang forever.

## Adding a flow

One file in `src/flows/`, one line in `src/flows/index.ts`. The runner owns gate
filtering, narration, timing, screenshots and the exit code; a flow owns only its
phases. If a new flow needs a step that does not exist, put it in `src/steps/` so
the next flow gets it too — a flow file should read as prose about the workflow,
not as Playwright calls.

## Narration

Every phase is announced to the console and appended to `narration/<runId>.txt`.
That file is the **script**: prose only, no timestamps or ids, because it is what
gets spoken aloud once the wording has been reviewed. Speaking it is the `tts`
gate, wired to the local Qwen3 server (`../tts`).
