<!--
Title must follow Conventional Commits (enforced by CI): type(scope): summary
Types: feat | fix | docs | chore | refactor | test | style | perf | ci | build | revert
See CONTRIBUTING.md for examples.
-->

## Summary

<!-- What does this PR do, and why? One or two sentences is fine. -->

## Related issue(s)

<!-- Closes #123 / Relates to #123 / N/A -->

## Type of change

- [ ] `feat` — new feature
- [ ] `fix` — bug fix
- [ ] `docs` — documentation only
- [ ] `refactor` — code change, no behavior change
- [ ] `chore` / `ci` / `build` — tooling, dependencies, CI
- [ ] `test` — test-only change

## Test plan

<!-- How did you verify this? Commands run, screenshots, or manual steps.
Frontend/backend changes: note whether this was checked against the real
shared test server per CLAUDE.md's live-verification workflow, or only
locally/via `mvn test`. -->

## Checklist

- [ ] `frontend`: `pnpm build` passes locally (no separate lint/test script exists — see `CLAUDE.md`)
- [ ] `backend`: `mvn test` passes locally
- [ ] If this touches `Design/chinese/`, `Docs/frontend/Design.md`, or `Docs/integration/APIContract.md`: the `_CN`/non-`_CN` counterpart is kept in sync
- [ ] No financial vocabulary introduced (no "token"/"currency"/redemption semantics outside the reserved Phase 4 namespace — see `CLAUDE.md`)
- [ ] Status-machine changes (`Project`/`EventProposal`/`Application`/`ServiceRecord`/`Case`) stay backend-owned; frontend only displays state
- [ ] If this reproduces/fixes a live-verified bug, an issue doc was added under `Docs/issues/` and `integration-test/STATUS.md` was updated (see `CLAUDE.md`'s bug-tracking workflow)
