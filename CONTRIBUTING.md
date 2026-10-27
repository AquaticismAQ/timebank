# Contributing to TimeBank · 贡献指南

Thank you for your interest in contributing to the TimeBank project. This guide covers how to contribute effectively.

感谢你对时间银行项目的贡献意愿。本文档说明如何有效参与贡献。

---

## Table of Contents · 目录

- [Ways to Contribute](#ways-to-contribute)
- [Document Structure](#document-structure)
- [Editing Guidelines](#editing-guidelines)
- [Your First Code Change](#your-first-code-change--第一次改代码)
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
├── Design/
│   ├── chinese/           # Institutional design — the source of truth (01-TimeBankDesign.md, 02-ManualAudit.md, …)
│   └── english/           # Same-named English mirrors
├── Docs/                  # Online-system docs: frontend design, API contract, build plan, issue write-ups
├── frontend/              # The website (React)
├── backend/               # The server (Java / Spring Boot)
└── integration-test/      # Script that fills a database with made-up test data
```

The design documents cross-reference each other and link back to `01-TimeBankDesign.md`. When editing, maintain these cross-references. When a `Design/chinese/` file changes, update its `Design/english/` twin in the same PR.

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

## Your First Code Change · 第一次改代码

New to the project? This walks you from an empty computer to an open Pull Request. You **don't** need access to the school's server — everything runs on your own machine: the database and backend in Docker, the website in your browser.

第一次参与？下面带你从零开始，一直到提交 Pull Request。你**不需要**学校服务器的权限——所有东西都在你自己的电脑上运行：数据库和后端跑在 Docker 里，网站在浏览器里打开。

Looking for something to work on? Issues labelled [`good first issue`](https://github.com/AquaticismAQ/timebank/labels/good%20first%20issue) are picked to be small and friendly.
想找任务？带 `good first issue` 标签的 Issue 是特意挑出来的、适合入门的小任务。

### 1. Install the tools · 安装工具

| Tool · 工具 | What it's for · 用途 |
| :--- | :--- |
| [Git](https://git-scm.com/) | Download the code and save your changes · 获取代码、保存修改 |
| [Docker Desktop](https://www.docker.com/products/docker-desktop/) | Runs the database and backend for you · 帮你运行数据库和后端 |
| [Node.js](https://nodejs.org/) 18 or newer, then `npm install -g pnpm` | Runs the website and the test-data script · 运行网站和测试数据脚本 |
| A `mysql` client (macOS: `brew install mysql-client`) | Used by the test-data script · 测试数据脚本需要 |
| Java 21 + Maven *(optional · 可选)* | Only to run backend tests yourself — CI runs them on every PR anyway · 仅用于自己跑后端测试，CI 也会自动跑 |

Give Docker about 4 GB of memory. 请给 Docker 分配约 4 GB 内存。

### 2. Get the code · 获取代码

Click **Fork** at the top of the GitHub page, then:
先在 GitHub 页面右上角点 **Fork**，然后：

```bash
git clone https://github.com/<your-github-name>/timebank.git
cd timebank
git checkout -b fix/short-description     # a new branch for your change · 为这次修改新建分支
```

### 3. Start the backend · 启动后端

```bash
docker compose up -d --build
docker compose ps        # wait until backend shows (healthy) · 等到 backend 显示 (healthy)
```

The first time takes a few minutes while it downloads and compiles. 第一次需要几分钟下载和编译。

### 4. Fill it with test data · 填入测试数据

An empty database means empty screens. This script creates made-up students, teachers, projects and records:
空数据库意味着页面全是空的。下面的脚本会生成虚构的学生、老师、项目和记录：

```bash
cd integration-test
npm install
TIMEBANK_SEED_DB_HOST=127.0.0.1 \
TIMEBANK_SEED_DB_PORT=3307 \
TIMEBANK_DB_USERNAME=timebank_app \
TIMEBANK_DB_PASSWORD=timebank-not-for-production \
TIMEBANK_BASE_URL=http://localhost:28080 \
npm run seed
cd ..
```

**At the end it prints every username and password — copy a few somewhere now.** They are shown only once.
**脚本最后会打印所有用户名和密码，请马上复制几个保存下来**，它们只显示这一次。

### 5. Open the website · 打开网站

```bash
cd frontend
pnpm install
printf 'VITE_DEV_PROXY_TARGET=http://localhost:28080\n' > .env.local
pnpm dev
```

Open **http://localhost:5173**. Log in as `admin001` for the teacher/admin pages, or with one of the 8-digit student numbers from step 4 for the student pages.
打开 **http://localhost:5173**。用 `admin001` 登录可进入教师/管理端，用第 4 步打印出的 8 位数字学号登录可进入学生端。

### 6. Make your change · 动手修改

| You want to change… · 想改的内容 | Look in · 去哪里找 |
| :--- | :--- |
| A student page · 学生端页面 | `frontend/src/app/components/student/` |
| A teacher/admin page · 教师/管理端页面 | `frontend/src/app/components/admin/` |
| Text on the screen · 界面文字 | `frontend/src/i18n/zh.json` and `en.json` — change both · 两个都要改 |
| Server logic · 后端逻辑 | `backend/src/main/java/cn/timebank/` |
| How the system is meant to work · 制度设计 | `Design/chinese/` (with its `Design/english/` twin · 及对应英文版) |

Frontend edits show up in the browser as soon as you save. After a backend edit, run `docker compose up -d --build` again.
前端修改保存后浏览器会自动刷新；修改后端后需要重新运行 `docker compose up -d --build`。

Two rules matter most: use **made-up data only** — never real names, schools or passwords, not even in screenshots — and remember **time coins are a school record, not money** (see [Content Principles](#content-principles)).
最重要的两条规则：只用**虚构数据**——不出现真实姓名、学校或密码，截图也不行；以及**时间币是校内记录，不是钱**（见[内容原则](#content-principles)）。

### 7. Check your work · 检查

```bash
cd frontend
npx -y -p typescript tsc --noEmit -p tsconfig.json && pnpm build   # frontend · 前端
cd ../backend
mvn test                                                            # backend, optional · 后端（可选）
```

If something fails and you can't see why, open the PR anyway and say so — that's fine.
如果检查失败又看不出原因，照样提交 PR 并说明情况即可。

### 8. Open a Pull Request · 提交 PR

```bash
git add .
git commit -m "fix: short description of what you changed"
git push -u origin fix/short-description
```

Then open a Pull Request on GitHub and fill in the short template. The title format is explained in [Submitting Changes](#submitting-changes).
然后在 GitHub 上发起 Pull Request，填写简短的模板。标题格式见[提交修改](#submitting-changes)。

### When you're done · 收尾

```bash
docker compose down        # stop, keep your data · 停止并保留数据
docker compose down -v     # stop and wipe everything, to start fresh · 停止并清空，从头再来
```

**Stuck? · 遇到问题？** See the troubleshooting section of [`Docs/operations/DockerDevelopment.md`](Docs/operations/DockerDevelopment.md#10-troubleshooting), or open an issue with the **Question** template. 可查看该文档的故障排查部分，或用 **提问（Question）** 模板新建 Issue。

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
