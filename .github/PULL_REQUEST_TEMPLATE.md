<!--
Title must follow Conventional Commits (enforced by CI): type(scope): summary
Types: feat | fix | docs | chore | refactor | test | style | perf | ci | build | revert
See CONTRIBUTING.md for examples.
-->

## Summary

- **Base branch:** `main`
- **What changed and why:** (2–5 bullets; the diff shows *what*, you explain *why*)
- **Scope boundary:** (what this PR explicitly does NOT change)
- **Blast radius:** (what else could be affected — e.g. shared components in `components/ui/`, both `_CN`/non-`_CN` doc pairs, the account-balance ledger, another portal that reuses this API)
- **Linked issue(s):** Use `Closes #`, `Fixes #`, or `Resolves #` only for issues this PR fully resolves; use `Related #` / `Depends on #` for non-closing relationships.
- **Labels:** snapshot the labels actually applied from `.github/labels.yml` once set, e.g. `type: fix`, `area: backend`, `priority: medium`.

## Type of change

- [ ] `feat` — new feature
- [ ] `fix` — bug fix
- [ ] `docs` — documentation only
- [ ] `refactor` — code change, no behavior change
- [ ] `chore` / `ci` / `build` — tooling, dependencies, CI
- [ ] `test` — test-only change

## Testing (required)

### How you can test (when useful)

Include this subsection when reviewer-run manual verification adds real signal — user-visible behavior, a non-obvious path, or a named CI coverage gap (e.g. anything only checkable against the real shared test server, since CI never runs `mvn verify`'s `*IT` tests or a live frontend build). For docs-only or trivial changes, set the first field to `N/A` with a one-line reason and drop the rest.

Frame it before/after: the same steps should show the old behavior on `main` and the new behavior on this branch.

- **Reviewer testing requested?** (`Yes` / `N/A`; if `N/A`, one line why)
- **Interface(s) exercised:** Student Portal / Admin+Teacher Portal / Backend API / Docs
- **Setup / preconditions:** which seeded account (`Docs/TestAccounts.md`), server state, or data needed first
- **Steps to run:** the exact click-through or `curl`/command sequence
- **Expected on this branch (after):**
- **Prior behavior on `main` (before):**

### How I tested

Use the evidence that matches the changed surface — CI is enough for most changes; add manual/live verification for a concrete gap CI can't cover.

```sh
# frontend
cd frontend && npx -y -p typescript tsc --noEmit -p tsconfig.json && pnpm build
# backend
cd backend && JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn test
```

- **CI checks relied on and why they cover this change:**
- **Known CI coverage gap, if any:** (e.g. "backend `*IT` tests aren't run in CI — needs a manual `mvn verify` against the shared test server")
- **Commands run and tail output:**
- **Beyond CI, what did you manually verify?** Note explicitly if this was checked against the real shared test server per `CLAUDE.md`'s live-verification workflow, versus only locally.
- **If this reproduces/fixes a live-verified bug:** issue doc added under `Docs/issues/` and `integration-test/STATUS.md` updated (see `CLAUDE.md`'s bug-tracking workflow)
- **If any command was intentionally skipped, why:**

## Security & Privacy Impact (required)

Yes/No for each. Answer any `Yes` with a 1–2 sentence risk-and-mitigation note.

- New permissions, roles, or data-scope access? (`Yes/No`)
- New external network calls? (`Yes/No`)
- Secrets / credentials / session handling changed? (`Yes/No`)
- Real names, real school/org identity, real credentials, or other PII added to diff, tests, fixtures, seed data, or docs? (`Yes/No` — this repo went through a full history sanitization on 2026-08-18; real values belong only in the untracked `test-server-credits.md`, never in a tracked file)
- If any `Yes`, describe the risk and mitigation:

## Compatibility (required)

- Backward compatible? (`Yes/No`)
- `Docs/integration/APIContract.md` changed? (`Yes/No` — if `Yes`, the doc update is part of this PR, not a follow-up)
- Status-machine/domain-model changes to `Project`/`EventProposal`/`Application`/`ServiceRecord`/`Case`? (`Yes/No` — these stay backend-owned; frontend only displays state)
- Financial vocabulary introduced? (`Yes/No` — must be `No`; no "token"/"currency"/redemption semantics outside the reserved Phase 4 namespace, see `CLAUDE.md`)
- If backward compatibility is `No`, or the API contract changed: exact steps for existing clients/deployments to adapt:

## Rollback

Low-risk PRs: `git revert <sha>` is the plan unless otherwise noted.

Medium/high-risk PRs (schema changes, auth/session changes, anything touching the account ledger) should fill:

- **Fast rollback command/path:**
- **Feature flags or config toggles:** (or `None`)
- **Observable failure symptoms:** (what to check in logs/audit log, which behavior regresses)

---

**Labels** live in the GitHub label UI (synced from `.github/labels.yml`), not the PR body — set `type:`/`area:`/`priority:`/`phase:` there.

**Do not add bot/AI attribution footers** (e.g. `Co-authored-by: Claude ...`, "Created with Claude Code") to the PR body or commit-message tail.

**Privacy:** never commit real names, real school/org identity, secrets, or other PII in diff, tests, fixtures, or docs — see `Docs/TestAccounts.md`/`test-server-credits.md`'s gitignored-real-values convention.
