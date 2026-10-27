# Snapshots

Screens from the three key workflows, captured from a real run against the
shared test server — one folder per workflow, numbered in the order a person
would see them:

| Folder | Workflow |
| :--- | :--- |
| `project-workflow/` | A teacher creates a project, a student registers, files a service record, the teacher approves it and the time coins are credited |
| `proposal-workflow/` | A student proposes an activity, the teacher asks for changes, the student revises, the teacher approves and publishes the generated project |
| `case-workflow/` | A student files a case, the teacher asks for more information, the student answers, the teacher resolves it |

Each folder holds the pages the flow opens, every form and dialog at the moment
it is submitted, and the screen each step ends on. Login pages are left out.
Everything shown is fictional fixture data; `[AUTO]` titles mark records the
automated run created.

Regenerate with the e2e harness (`e2e/README.md` → Snapshots):

```bash
cd e2e
./demo-project.sh --no-gate tts --headless --snapshots ../snapshots
./demo-proposal.sh --no-gate tts --headless --snapshots ../snapshots
./demo-case.sh --no-gate tts --headless --snapshots ../snapshots
```

Review every picture before committing new ones — they show whatever the
server holds, and only fictional data belongs in this repository
(`Docs/README.md` §2.8).
