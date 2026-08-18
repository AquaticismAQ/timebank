# Label policy

Labels are defined in `.github/labels.yml` and kept in sync with the repo by
`.github/workflows/labels.yml` (`EndBug/label-sync`) on every push to `main`
that touches that file. **Edit `labels.yml`, not the GitHub UI** — labels
created or renamed only in the UI will be removed on the next sync, since
`labels.yml` is treated as the full desired state.

This is separate from `Docs/issues/0NN-*.md` (see `CLAUDE.md`'s
bug-tracking workflow): those are the internal, dated write-ups for defects
actually reproduced by a live-verification session against the real
deployed app. GitHub Issues + labels below are for general triage —
community bug reports, feature requests, and doc issues — which may or may
not turn into a `Docs/issues/` entry once someone verifies them live.

## Categories

- **`type: *`** — exactly one per issue/PR. What kind of thing this is.
  Names deliberately match the Conventional Commits types enforced on PR
  titles (`fix`, `feat`, `docs`, `chore`, `refactor`, `test`), not GitHub's
  traditional `bug`/`enhancement` wording — so a PR's `type: *` label and
  its title prefix should always agree. `type: question` and
  `type: security` are the two exceptions with no commit-type equivalent.
- **`status: *`** — exactly one, and it should reflect current lifecycle
  state (defaults to `status: needs-triage` on creation via the issue
  templates; move it forward as it's worked).
- **`priority: *`** — optional, maintainer-assigned during triage, not
  something reporters should set themselves.
- **`area: *`** — optional, one or more. Which part of the repo this
  touches.
- **`phase: *`** — optional. Only apply if the issue is specifically about
  a phase-gated feature (Phase 3/4 are reserved and not implemented — see
  `CLAUDE.md` — so most `phase: 3-pov` / `phase: 4-redemption` issues
  should be feature requests, not bug reports).
- **`good first issue` / `help wanted`** — standard GitHub community
  labels, applied at maintainer discretion.

## Adding a label

Add an entry to `.github/labels.yml` (name, color, description) in a PR.
Once merged to `main`, the sync workflow creates it on the repo. Don't
create ad-hoc labels through the GitHub UI for anything meant to persist.

## Renaming a label

Don't just change `name:` in place — the sync workflow runs with
`delete-other-labels: true`, so a bare rename deletes the old label
(stripping it from any issue/PR still wearing it) and creates the new one
empty. Add an `aliases:` list with the old name(s) instead, e.g.:

```yaml
- name: "type: fix"
  aliases: ["type: bug"]
```

`EndBug/label-sync` then renames the existing label in place, carrying its
associations forward.
