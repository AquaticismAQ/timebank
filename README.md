# TimeBank · 时间银行

> A volunteer service management system for middle school students, converting service hours into auditable "time coins" that form each student's personal record of public contribution.

> 一套面向中学学生的志愿服务管理体系，将服务时长转换为可记录、可审核的「时间币」，形成学生个人的公益成长档案。

---

## What is TimeBank? · 什么是时间银行？

**English** — TimeBank is an institutional framework that records, audits, and incentivizes student volunteer activity. Service hours are logged as "time coins," which accumulate into a verifiable public-interest portfolio used for school recognition, honor certification, and student development records. The system is designed around three principles: **authentic evidence** (multi-layered verification so every hour is real), **privacy by design** (on-device anonymization, hash-only storage, no raw personal data uploaded), and **legal compliance** (no token issuance, no financial transactions — blockchain is used only for tamper-proof evidence storage).

**中文** — 时间银行是一套记录、审核并激励学生志愿服务的制度框架。服务时长以「时间币」计入个人账户，累积为可核验的公益档案，用于学校荣誉认证与学生发展记录。体系围绕三大原则设计：**真实存证**（多层验证确保每一小时真实发生）、**隐私优先**（端侧脱敏、仅存哈希、原图不落盘）、**合法合规**（不发币、不交易，区块链仅用于防篡改存证）。

---

## Documentation · 文档

The Phase 1 institutional design documentation is available in **Chinese
and English**. The same-named files in `Design/english/` mirror
`Design/chinese/`; the Chinese documents are the authoritative source of
truth and the English versions must remain synchronized with them.

第一阶段制度设计文档现已提供**中文与英文版本**。`Design/english/` 中的
同名文件是 `Design/chinese/` 的英文镜像；中文文档是权威来源，英文版本须
与其保持同步。

### Design Docs · 设计文档

| Document | Description |
| --- | --- |
| [01-TimeBankDesign.md](Design/chinese/01-TimeBankDesign.md) | 顶层方案设计：制度目标、核心概念、服务要求、核心功能、角色分工与激励体系 |
| [02-ManualAudit.md](Design/chinese/02-ManualAudit.md) | 人工审核方案：纸质表单流程、数据字段设计、审核标准与风险控制 |
| [03-AutoAudit.md](Design/chinese/03-AutoAudit.md) | 自动审核方案（POV 见证者审核）：端侧脱敏、弱验证、AI 风控、反 Sybil 路径对比 |
| [04-Blockchain.md](Design/chinese/04-Blockchain.md) | 区块链存证方案：定位、合规要求、公链选型、上链数据设计与费用评估 |
| [05-OnlineSystem.md](Design/chinese/05-OnlineSystem.md) | 线上系统设计：功能模块、架构、数据安全与简略实施计划 |
| [06-Legal.md](Design/chinese/06-Legal.md) | 法律合规方案：中国法规下区块链应用约束、时间币限制边界与合法化路径 |
| [07-AI.md](Design/chinese/07-AI.md) | AI 技术应用方案：端侧人脸脱敏、多模态内容审核、图像真实性检测、统计学异常风控与见证者信用评分 |
| [08-mlps-level1-security-design-guide.md](Design/chinese/08-mlps-level1-security-design-guide.md) | 等保一级安全设计方案：安全架构、存储/传输加密、凭证文件安全、身份鉴别与合规自查清单 |
| [09-StudentIdentity.md](Design/chinese/09-StudentIdentity.md) | 学生身份标识设计：登录名与学号的形态、由来、边界与名册 CSV 准备 |
| [10-StudentActivationFlow.md](Design/chinese/10-StudentActivationFlow.md) | 学生账号激活流程：名册导入到首次登录的完整路径与激活凭证单 |
| [11-RolesAndPermissions.md](Design/chinese/11-RolesAndPermissions.md) | 角色与权限设计：角色模板、权限目录与数据范围模型 |
| [12-DataImport.md](Design/chinese/12-DataImport.md) | 数据导入设计：批量导入类型、流程、校验与权限约束 |
| [13-DataExport.md](Design/chinese/13-DataExport.md) | 数据导出设计：导出类型目录、筛选范围模型、列规范与权限约束 |
| [14-AnnualRollover.md](Design/chinese/14-AnnualRollover.md) | 学年升级与毕业流程设计：每学年一次的升年级、毕业与学段衔接 |

### English Docs · 英文文档

| Document | Description |
| --- | --- |
| [01-TimeBankDesign.md](Design/english/01-TimeBankDesign.md) | Top-level institutional design: goals, core concepts, service requirements, functions, roles, and incentives |
| [02-ManualAudit.md](Design/english/02-ManualAudit.md) | Manual review: paper-form workflow, data fields, review standards, and risk controls |
| [03-AutoAudit.md](Design/english/03-AutoAudit.md) | Automated review and POV witness attestation: on-device de-identification, weak verification, AI risk controls, and anti-Sybil options |
| [04-Blockchain.md](Design/english/04-Blockchain.md) | Blockchain evidence storage: positioning, compliance, consortium-chain selection, evidence-package design, and costs |
| [05-OnlineSystem.md](Design/english/05-OnlineSystem.md) | Online system: modules, architecture, data security, and implementation plan |
| [06-Legal.md](Design/english/06-Legal.md) | Legal compliance under Chinese law: blockchain constraints and the permitted boundaries for time coins |
| [07-AI.md](Design/english/07-AI.md) | AI applications: on-device face de-identification, multimodal review, image-authenticity checks, statistical fraud controls, and witness scoring |
| [08-mlps-level1-security-design-guide.md](Design/english/08-mlps-level1-security-design-guide.md) | MLPS Level 1 security design: security architecture, at-rest/in-transit encryption, evidence-file security, authentication, and the compliance self-check list |
| [09-StudentIdentity.md](Design/english/09-StudentIdentity.md) | Student identity design: the username and student number identifiers, where each comes from, and roster CSV preparation |
| [10-StudentActivationFlow.md](Design/english/10-StudentActivationFlow.md) | Student account activation: the full path from roster import to first login, and the credential slip |
| [11-RolesAndPermissions.md](Design/english/11-RolesAndPermissions.md) | Roles and permissions: role templates, the permission catalogue, and the data-scope model |
| [12-DataImport.md](Design/english/12-DataImport.md) | Data import: bulk import types, workflow, validation, and permission constraints |
| [13-DataExport.md](Design/english/13-DataExport.md) | Data export: the export catalogue, filter and scope model, column conventions, and permission constraints |
| [14-AnnualRollover.md](Design/english/14-AnnualRollover.md) | Annual rollover and graduation: the once-a-year promotion process and the two-stage model |

---

## Key Design Highlights · 核心设计亮点

**English**
- **Proof of Volunteering (POV)**: A multi-source evidence framework combining device metadata (GPS, timestamps), witness attestation (WeChat real-name, 2-second scan), AI scene recognition, and social endorsement (peer co-verification, guardian sign-off).
- **Privacy by Default**: Client-side face blurring (WASM/BlazeFace) before any upload; original images destroyed in memory; only hashes stored on-chain.
- **Anti-Sybil & Statistical Fraud Control**: Metcalfe dispersion filters, geo-fencing, seasonal/environmental consistency checks, witness credit scoring — borrowed from financial anti-money-laundering models.
- **Incentive Design**: Spontaneous volunteering earns low time-coin quotas with high honorary incentives (badges, rankings), making fraud unrewarding.
- **Legal Compliance**: No token issuance, no financial transactions; consortium blockchain (recommended: WenChang Chain / BSN) for evidence only; all data stored domestically.

**中文**
- **POV 见证者证明**：多源加权可信度体系，整合设备元数据（GPS/时间戳）、见证者弱验证（微信实名 2 秒闭环）、多模态 AI 场景识别与社会佐证（组员互证 + 监护人背书）。
- **隐私优先设计**：端侧（WASM/BlazeFace）人脸打码后才上传，原图内存销毁，链上仅存哈希。
- **反作弊统计学风控**：梅特卡夫离散度过滤、地理时空围栏、季节环境一致性检验、见证者信用分——借鉴金融反洗钱/反刷单模型。
- **激励机制**：自发公益采用低时间币上限 + 高荣誉激励（勋章/排名），从源头使作弊无利可图。
- **法律合规**：不发币、不交易；推荐文昌链（BSN 开放联盟链）仅作存证；数据与节点全部在境内。

---

## Project Status · 项目状态

As of **2026-08-26**, Phase 2 is ongoing and approximately **90% complete**.
The React frontend and Spring Boot backend are deployed together on the
shared test server and have been verified through real API and browser
flows. Remaining work is primarily acceptance/cutover completion and
infrastructure-dependent operations; the current deployment is a test
environment, not a production launch. See
[`Docs/implementation/00-Status.md`](Docs/implementation/00-Status.md) for
the authoritative item-by-item status.

截至 **2026-08-26**，第二阶段仍在推进，整体约完成 **90%**。React 前端与
Spring Boot 后端已共同部署到共享测试服务器，并通过真实 API 与浏览器流程
验证。剩余工作主要是验收/切换收尾及依赖新增基础设施的运维事项；当前部署
属于测试环境，并非正式生产上线。逐项权威状态见
[`Docs/implementation/00-Status.md`](Docs/implementation/00-Status.md)。

| Phase | Status |
| --- | --- |
| Phase 1: Institutional Design · 第一阶段：制度设计 | ✅ Complete; Chinese originals and English mirrors available |
| Manual Review Design · 人工审核设计 | ✅ Specification complete and implemented in the online system |
| Automated Review (POV) · 自动审核 | ✅ Specification complete; Phase 3 implementation reserved |
| Blockchain Evidence Storage · 区块链存证 | ✅ Specification complete; optional Phase 3 capability |
| Legal Compliance · 法律合规 | ✅ Specification complete |
| English Design Documentation · 英文设计文档 | ✅ All fourteen mirrors complete |
| Phase 2: Online System · 第二阶段：线上系统 | 🔄 Approximately 90% complete; frontend and backend deployed and verified on the shared test server |
| Phase 3: POV Mini Program · 第三阶段：POV 小程序 | 📋 Reserved behind feature flags; not implemented |
| Phase 4: Extended Incentives · 第四阶段：拓展激励 | 📋 Reserved behind feature flags; not implemented |

---

## Building & Running Locally · 本地构建与运行

**English** — The Phase 2 online system is a React/Vite frontend (`frontend/`) plus a Spring Boot/Java 21/MySQL backend (`backend/`), documented in full in `frontend/README.md`, `backend/scripts/`, and `CLAUDE.md`/`AGENTS.md`. Quick start:

```bash
# Frontend (needs a running backend to talk to — see frontend/README.md)
cd frontend
pnpm install
cp .env.example .env.local   # set VITE_DEV_PROXY_TARGET to a real backend URL
pnpm dev

# Backend
cd backend
mvn compile   # local compile check (Java 21)
mvn test      # fast tests, no external services
```

There is no mock mode — the frontend always talks to a real backend. To run the whole system on your own machine (MySQL and the backend in Docker, the website via `pnpm dev`), follow **[Your First Code Change](CONTRIBUTING.md#your-first-code-change--第一次改代码)** in `CONTRIBUTING.md`; `Docs/operations/DockerDevelopment.md` has the details. Docker is for local development only — deployment is a plain JAR under systemd.

**中文** — 二期线上系统由 React/Vite 前端（`frontend/`）与 Spring Boot / Java 21 / MySQL 后端（`backend/`）组成，详细说明见 `frontend/README.md`、`backend/scripts/` 与 `CLAUDE.md`/`AGENTS.md`。快速开始见上方英文代码块。本项目不提供 Mock 模式，前端始终对接真实后端。想在自己电脑上运行整个系统（MySQL 和后端跑在 Docker 里，网站用 `pnpm dev` 打开），请按 `CONTRIBUTING.md` 中的 **[第一次改代码](CONTRIBUTING.md#your-first-code-change--第一次改代码)** 操作，细节见 `Docs/operations/DockerDevelopment.md`。Docker 仅用于本地开发，部署仍是 systemd 下的 JAR。

---

## Deployment · 部署

**English** — The frontend and backend are currently deployed together on a
single shared test server and have been verified there through real API and
browser flows. There is no separate staging environment yet (see
`Docs/operations/Runbooks.md`). Both deploy scripts are designed to be safe
to commit: neither hardcodes the real hostname or any secret, both source an
optional, untracked `~/.timebank.env` for convenience (see
`Docs/operations/DeploymentEnv.md`), and real credentials live only in the
untracked `test-server-credits.md`.

```bash
frontend/scripts/deploy.sh [user@host]   # builds locally, rsyncs dist/ to nginx, reloads nginx

# Backend: sync source, then run on the server itself (needs the target JVM)
rsync -az --delete --exclude 'target/' --exclude '.git/' backend/ <user@host>:/opt/timebank-backend/
ssh <user@host>
cd /opt/timebank-backend && ./scripts/verify.sh && ./scripts/deploy.sh
```

`Docs/operations/DeploymentEnv.md` covers the `~/.timebank.env` convenience file that both scripts pick up automatically if present, so collaborators don't have to re-export the deploy target and database credentials by hand every session.

**中文** — 前端与后端现已共同部署到同一台共享测试服务器，并已通过真实 API
与浏览器流程验证；目前尚无独立的预发布环境（见
`Docs/operations/Runbooks.md`）。两个部署脚本均可安全提交：不硬编码真实
主机名或任何密钥，均会在存在时自动加载未纳入版本控制的
`~/.timebank.env`（见 `Docs/operations/DeploymentEnv.md`），真实凭证仅
保存在未纳入版本控制的 `test-server-credits.md` 中。命令示例见上方英文
代码块。`Docs/operations/DeploymentEnv.md` 说明了 `~/.timebank.env`
这一便利文件的结构，两个脚本在其存在时会自动加载，协作者无需每次手动
重新导出部署目标与数据库凭证。

---

## License · 许可证

This project's documentation is shared for educational and reference purposes. For licensing details, please contact the project maintainers.

本项目文档以教育参考为目的共享。许可证详情请联系项目维护者。
