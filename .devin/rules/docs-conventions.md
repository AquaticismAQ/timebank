---
description: Documentation conventions when editing Design/ or Docs/ files
trigger: glob
globs: '{Design,Docs}/**/*.md'
---

# Documentation Conventions

## Chinese-English Pairs

- Chinese docs are primary; English docs mirror structure exactly
- Files with `_CN.md` suffix are the Chinese counterparts
- **Both must stay in sync** when either is edited
- Applies to: `Docs/frontend/Design.md` ↔ `Design_CN.md`, `Docs/integration/APIContract.md` ↔ `APIContract_CN.md`
- Implementation docs (`Docs/implementation/`) do NOT have Chinese counterparts

## Status Document (`00-Status.md`)

- **Living document** — update on phase entry, exit, and any gate/risk change
- Work items use IDs like `B3.4`, `I2.2` — **never renumber**, append new items
- Status marks: `—` not started, `▶` in progress, `⏸` blocked, `✅` done, `⛔` descoped
- A phase is `✅` only when ALL exit-criteria checkboxes are ticked

## Cross-References

- Preserve `见 XDoc.md` / `see XDoc.md` cross-references
- Don't renumber existing sections — other docs cite section numbers
- Reference format: `§3.4`, `Design.md D9`, `APIContract.md §2.6`

## Content Rules

- Never weaken anti-fraud/anti-Sybil/legal-compliance provisions without justification
- Avoid financial vocabulary: 代币/发币/挖矿/提现/钱包
- Time coins are non-financial school-internal accounting units
