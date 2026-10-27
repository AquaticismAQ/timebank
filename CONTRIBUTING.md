# Contributing to TimeBank · 贡献指南

Thank you for your interest in contributing to the TimeBank project. This guide covers how to contribute effectively.

感谢你对时间银行项目的贡献意愿。本文档说明如何有效参与贡献。

---

## Table of Contents · 目录

- [Ways to Contribute](#ways-to-contribute)
- [Document Structure](#document-structure)
- [Editing Guidelines](#editing-guidelines)
- [Submitting Changes](#submitting-changes)
- [Language & Style](#language--style)
- [Content Principles](#content-principles)

---

## Ways to Contribute

- **Documentation**: Improve existing Chinese docs, translate to English, fix errors or outdated information.
- **Design Review**: Critique or refine the institutional design, audit mechanisms, or legal analysis.
- **Technical Proposals**: Suggest implementations for the online system, blockchain integration, or AI audit components.
- **Case Studies**: Contribute real-world examples or scenarios that test the design.
- **Issue Reporting**: Open an issue for factual errors, logical gaps, or unclear sections.

**贡献方式**

- **文档**：改进现有中文文档、翻译英文版、修正错误或过时内容。
- **设计审阅**：对制度设计、审核机制或法律分析提出改进意见。
- **技术方案**：为线上系统、区块链集成或 AI 审核组件提出实现建议。
- **案例研究**：补充能检验设计边界的真实场景或案例。
- **问题反馈**：对事实错误、逻辑漏洞或表述不清的部分提交 Issue。

---

## Document Structure

```
repo/
├── README.md              # Project overview (bilingual)
├── CONTRIBUTING.md        # This file
└── Docs/
    ├── chinese/           # Chinese documentation (primary)
    │   ├── TimeBankDesign.md   # Top-level institutional design
    │   ├── ManualAudit.md      # Manual audit process & data design
    │   ├── AutoAudit.md        # Automated audit (POV) & AI risk control
    │   ├── Blockchain.md       # Blockchain evidence storage
    │   ├── OnlineSystem.md     # Online system design
    │   └── Legal.md            # Legal compliance under Chinese law
    └── english/           # English documentation (planned)
```

All sub-documents cross-reference each other and link back to `TimeBankDesign.md`. When editing, maintain these cross-references.

---

## Editing Guidelines

### For Documentation

1. **Read the full doc first** before proposing changes — the documents are interdependent and cross-reference each other.
2. **Preserve cross-references**: If you add, remove, or rename a section, update all `see XDoc.md` / `详见 XDoc.md` references across all affected documents.
3. **Do not weaken risk controls**: Do not remove or soften anti-fraud, anti-Sybil, or legal compliance provisions without clear justification.
4. **Keep section numbering stable**: Avoid renumbering existing sections unless restructuring is truly necessary, as cross-references use section numbers.
5. **One change per PR**: Keep pull requests focused — documentation restructures, content additions, and corrections should be separate PRs.

### For Technical Proposals

- Proposals should reference the relevant sub-document (e.g., `OnlineSystem.md` for system architecture, `AutoAudit.md` for audit logic).
- Include a pros/cons analysis and a recommendation, consistent with the comparison format used throughout the docs.
- Flag any legal implications and cross-reference `Legal.md`.

---

## Submitting Changes

1. **Fork** the repository and create a branch from `main`:
   ```bash
   git checkout -b docs/your-change-description
   # or
   git checkout -b feature/your-feature-name
   ```

2. **Make your changes** following the guidelines above.

3. **Commit** with a clear, descriptive message:
   ```bash
   # Documentation changes
   git commit -m "docs: clarify POV witness verification flow in AutoAudit"

   # New content
   git commit -m "docs: add English translation for TimeBankDesign"

   # Bug/error fix
   git commit -m "fix: correct legal citation for PIPL article reference"
   ```

4. **Push** your branch and open a **Pull Request** against `main`.

5. Fill out the short PR template that appears when you open the PR: what
   you changed and why, how you checked it, and a four-item checklist. If
   you're unsure about anything, open the PR anyway and ask in it.

   打开 PR 时会自动出现一个简短模板：写明改了什么、为什么、如何验证，并完成四项自查。
   有不确定的地方，照样提交 PR 并在其中提问即可。

---

## Language & Style

### Chinese Documents

- Use formal but accessible Mandarin (书面语, not colloquial).
- Use Markdown tables for comparisons; use mermaid diagrams for flows.
- Bold key terms on first use: **时间币**、**POV 见证者证明**。
- Avoid financial/token vocabulary: do not use 代币、发币、挖矿、提现、钱包 in any new content.
- Reference relevant laws precisely: cite law name, issuing authority, and year.

### English Documents

- Mirror the structure of the Chinese counterpart exactly.
- Use plain, professional English; avoid jargon without explanation.
- Keep technical terms consistent: "time coin" (not "token"), "evidence storage" (not "blockchain transaction"), "witness attestation" (not "witness signature").

---

## Content Principles

These principles underpin the entire project design and must be upheld in all contributions:

1. **Non-financial**: Time coins are a school-internal accounting unit, never a currency, token, or financial instrument. No contributions should introduce financial transfer, cash-out, or tradeable characteristics.
2. **Privacy by default**: Any new data field or process involving personal information must follow the minimum-necessary principle and use hashing or anonymization for sensitive data.
3. **Anti-fraud integrity**: Risk control and anti-Sybil mechanisms must not be weakened. New features that could introduce fraud vectors must include corresponding mitigations.
4. **Legal compliance**: All design decisions must remain consistent with `Legal.md`. Proposals affecting blockchain use, data handling, or coin economics must be reviewed against Chinese regulatory constraints.
5. **Preserve spontaneous volunteering**: Design changes must not inadvertently restrict or bureaucratize spontaneous, informal public-interest acts (e.g., picking up litter, helping neighbors) — a core value of the project.

---

## Questions?

Open an issue and pick the **Question** template, or reach out to the project maintainers directly. No question is too small.

有疑问？新建 Issue 时选择 **提问（Question）** 模板，或直接联系项目维护者。问题再小也欢迎。
