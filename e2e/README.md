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

## Running the demonstration

```bash
./demo-project.sh                 # narrated, full screen, browser left open
./demo-project.sh --cleanup       # tidy earlier [AUTO] runs first
./demo-project.sh --no-gate tts   # silent
./demo-project.sh --beat 1500     # slower pauses
```

`demo-project.sh` is **not in git** — it carries the test-server account
credentials, so it is gitignored and refuses to run if that ever stops being
true, or if it is found tracked. This repo has twice rewritten its history to
remove leaked data; a credential file that quietly became trackable is how a
third would start.

It also does the things that are easy to forget and painful to discover
mid-demonstration: checks the browser install, generates the evidence files if
they are missing, and **starts the TTS server and waits for it** — a cold start
is about six seconds *per line*, so discovering a stopped server during the demo
means sitting through it rather than fixing it. Everything after the script name
is passed straight to `pnpm e2e`.

Without it, `pnpm e2e` does the same thing given the right environment.

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
| Evidence files | `../demo-evidence` beside the repo — see below | `EVIDENCE_DIR`, `--evidence-dir` |
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

### `--cleanup`

```bash
pnpm e2e --cleanup-only        # tidy earlier runs, run no flow
pnpm e2e --cleanup             # tidy first, then run
```

It acts **only** on things whose title begins with `[AUTO]`, which is the entire
safety mechanism and is deliberately not configurable — this runs against a
shared server holding a curated dataset (`integration-test/STATUS.md`).
Idempotent: a second pass cancels nothing and reports what it skipped.

| What | Why |
| :--- | :--- |
| Cancels `[AUTO]` projects | They can never auto-archive: archive requires every confirmed student's record to be terminal, and a returned record never is |
| Deletes stray drafts | A run that died mid-flight leaves one, and a draft is the only record that may ever be physically deleted |
| **Counts, but cannot touch, submitted records** | They are sitting in a teacher's queue. Deciding them would put a review in the audit log that nobody made |
| **Counts, but cannot touch, approved and returned records** | Immutable by design, and each still holds its service time window |

Driven through the API rather than the browser, unlike everything else here:
cleanup is maintenance, not a workflow anyone is being shown.

## The files a demonstration uploads

They come from **`../demo-evidence`**, generated by
`pnpm tsx scripts/make-demo-evidence.ts`:

| File | Why |
| :--- | :--- |
| `service-confirmation.jpg` | The ordinary case |
| `supervisor-confirmation-…-2026-09-18.jpg` | A long filename, real input for how the UI renders one |
| `service-confirmation.pdf` | A different MIME type |

All three are valid uploads (`FileServiceImpl:37` allows `image/jpeg`,
`image/png`, `application/pdf`; the cap is 20 MB). `verify` uses all three, one
per record.

**They are deliberately not the real test files.** `../test-evidence` holds a
genuine volunteer log sheet carrying a real school's name and crest, large
enough to read from the back of a room. Nothing about that is in git — both
folders sit outside the repo — but a demonstration puts whatever it uploads on a
projector, which is the same exposure this repo twice had to rewrite its history
to undo (`Docs/issues/057`), arriving by a route no scrub would have caught. The
demo set is the same *shape* of document built from the established placeholder
names (`Docs/README.md` §2.8).

Generated rather than committed, because binary fixtures rot and nobody can
review a JPEG in a pull request. `EVIDENCE_DIR` or `--evidence-dir` still points
anywhere you like, including at the real files.

## Making it watchable

A demonstration is not a passing test played slowly. `src/harness/stage.ts` wraps
every interaction so it goes **say it → point at it → do it → pause**:

- **Said first.** `现在打开项目中心`, `现在点击立即报名`, `现在填写服务日期`. An
  audience cannot follow a click they were not told was coming, and account
  switches especially — on screen a login is a half-second flicker.
- **Pointed at.** A red ring is drawn around the target and a round cursor moves
  to its centre, both in the brand colour, with a short transition so the pointer
  is seen arriving rather than teleporting.
- **Paused after**, half a second, so the result can be taken in. Skipped
  entirely when the `pace` gate is off, so `verify` loses nothing.

The pointer is decoration and never fails a run — but if it stops working the
run says so once, because decoration that quietly disappears looks fine.

Worth knowing if you touch it: `page.evaluate` given a **string** evaluates it as
an expression and *ignores any argument*. The first version of the spotlight
passed the target's rectangle as an argument, so it built the drawing function
and never called it, and the `catch` meant nothing said so. The box is
interpolated into the expression instead.

## The window

Headed runs open at the display's full width, positioned at the top-left, and
print what they actually got:

```
viewport 1512x809, display 1512x982
```

That line exists because the first attempt at "full screen" was a lie. Under
Playwright on macOS, **both `--start-fullscreen` and `--kiosk` are ignored** —
the window comes up at Chromium's default size while the launch arguments claim
otherwise, and nothing on screen contradicts them. Both were tried and measured;
neither moved the viewport by a pixel, so neither is left in the code pretending.
Explicit `--window-position` and `--window-size` do work.

The remaining ~170px is the tab strip and omnibox — browser chrome, not window
size. Removing it would take a CDP call; the window is big enough to present
from as it is.

## The browser is left open

Pass or fail. The end state — the approved record, the changed balance — is the
point of a demonstration, and it is what you most want to see after a failure.
The process therefore **waits for Ctrl-C** rather than exiting.

`--close` opts out, and `--headless` implies it. For anything unattended use
`--profile verify --headless`, or the job will hang forever.

## Things that bite, and why the code looks the way it does

Every one of these cost a failing run to find, and several were failures against
a system that was working correctly — so they are worth knowing before changing a
step.

| Trap | What it looks like | What the code does |
| :--- | :--- | :--- |
| **`page[size]`, not `size`** | A plain `size=200` is ignored and you get 20 rows. The slot allocator silently believed the student had twenty records and would hand out an occupied window | Every query uses `page%5Bsize%5D` |
| **Choosing a project prefills the date** | The prefill is an effect, so it lands *after* the change event and overwrites a date filled too early — with the project's own start date, which is in the future, and submission is refused | Wait for the prefill, then overwrite it |
| **Toasts auto-dismiss** | Waiting on one makes a step depend on winning a race it does not control. Three steps failed this way against actions that had already succeeded | Wait for the dialog or wizard to close instead |
| **Lists render before their data** | A dialog is briefly open and empty, a home page shows `--h`, a detail view has no filename yet. Asserting on sight reports a fault that is not there | Wait for the row, the figure, or the dialog's own content |
| **Search is asynchronous** | Acting straight after pressing Enter acts on the *unfiltered* list. A student once registered for a previous run's project while every assertion passed, because they were all reading the same wrong card | Wait for the list's own count to read `1 个项目` |
| **One result makes every ancestor match** | With a single card on screen, `div:has-text(title)` matches the card *and* everything above it | Filter on a second string only the card carries, then take the innermost |
| **Controlled inputs drop early fills** | Without `--slow-mo` the login form submits empty, the server says the credentials are wrong, and the run stalls 25s later waiting for a redirect | Wait for a fetched element, fill, read back, retry |
| **Logins are throttled** | Re-authenticating on every role switch is about a dozen logins a run; they start being refused at a random phase | One context per role, signed in once, kept for the run |
| **Coins are minimum accounting units** | Two recognised hours credit **200**, not 2 | `UNITS_PER_COIN = 100` |
| **Credit follows the record, not the project** | A 90-minute session credits 150 even when the project says two hours | The expectation is derived from the record's own duration |
| **Record search matches names only** | Not project titles, not record numbers, so it cannot find what a run created | Raise the page size to 100 instead |
| **Status words differ from toast words** | The chip says 已提交 while the toast says 待审核; a returned record reads 退回, not 已退回 | `RECORD_STATUS` in `steps/serviceRecord.ts` |

## Adding a flow

One file in `src/flows/`, one line in `src/flows/index.ts`. The runner owns gate
filtering, narration, timing, screenshots and the exit code; a flow owns only its
phases. If a new flow needs a step that does not exist, put it in `src/steps/` so
the next flow gets it too — a flow file should read as prose about the workflow,
not as Playwright calls.

## Narration and voice

Every line is announced to the console and appended to `narration/<runId>.txt`.
That file is the **script**: prose only, no timestamps or ids. It is written on
every run, with or without a working speaker, because reviewing the wording
should not depend on audio.

With the `tts` gate on — the `demo` profile's default — each line is also spoken
through the local Qwen3 server (`../tts`).

```bash
python qwen_serve.py start     # in ../tts, before the demo
pnpm e2e                       # narrated
pnpm e2e --no-gate tts         # silent
pnpm e2e --dry-narrate         # read the last script aloud, touch nothing
pnpm e2e --dry-narrate --script narration/260920-150455-XXXX.txt
```

Three things worth knowing:

- **Speech blocks.** A line takes about four seconds, and the run waits for it.
  That is deliberate: a fire-and-forget call runs ahead of the browser and
  narrates the next step while the audience is still looking at the last one.
  It roughly doubles a run's length, which for a demonstration is the point.
- **Start the server first.** A cold start is about six seconds *per line*, so
  the answer to a stopped server is to start it, not to wait for it. Preflight
  reports whether it found one running.
- **Losing the voice is never fatal.** If the server is missing or stops
  responding, the run says so once and carries on silently. Stopping in front of
  an audience is worse than finishing without sound.

`--dry-narrate` reads a real script back rather than pretending to run the flow,
because the text is produced as the phases execute — there is no script until a
run has made one.
