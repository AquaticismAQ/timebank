# TimeBank · 时间银行

> A volunteer service management system for middle school students, converting service hours into auditable "time coins" that form each student's personal record of public contribution.

> 一套面向中学学生的志愿服务管理体系，将服务时长转换为可记录、可审核的「时间币」，形成学生个人的公益成长档案。

---

## What is TimeBank? · 什么是时间银行？

**English** — TimeBank is an institutional framework that records, audits, and incentivizes student volunteer activity. Service hours are logged as "time coins," which accumulate into a verifiable public-interest portfolio used for school recognition, honor certification, and student development records. The system is designed around three principles: **authentic evidence** (multi-layered verification so every hour is real), **privacy by design** (on-device anonymization, hash-only storage, no raw personal data uploaded), and **legal compliance** (no token issuance, no financial transactions — blockchain is used only for tamper-proof evidence storage).

**中文** — 时间银行是一套记录、审核并激励学生志愿服务的制度框架。服务时长以「时间币」计入个人账户，累积为可核验的公益档案，用于学校荣誉认证与学生发展记录。体系围绕三大原则设计：**真实存证**（多层验证确保每一小时真实发生）、**隐私优先**（端侧脱敏、仅存哈希、原图不落盘）、**合法合规**（不发币、不交易，区块链仅用于防篡改存证）。

---

## Documentation · 文档

Documentation is currently available in **Chinese only**. English translations will be added in a future update.

文档目前仅提供**中文版本**，英文版本将在后续版本中更新。

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

### English Docs · 英文文档

*Coming soon · 即将推出*

---

## Key Design Highlights · 核心设计亮点

**English**
- **Proof of Volunteering (POV)**: A multi-source evidence framework combining device metadata (GPS, timestamps), witness attestation (WeChat real-name, 2-second scan), AI scene recognition, and social endorsement (peer co-verification, guardian sign-off).
- **Privacy by Default**: Client-side face blurring (WASM/BlazeFace) before any upload; original images destroyed in memory; only hashes stored on-chain.
- **Anti-Sybil & Statistical Fraud Control**: Metcalfe dispersion filters, geo-fencing, seasonal/environmental consistency checks, witness credit scoring — borrowed from financial anti-money-laundering models.
- **Token Economics**: Spontaneous volunteering earns low time-coin quotas with high honorary incentives (badges, rankings), making fraud economically pointless.
- **Legal Compliance**: No token issuance, no financial transactions; consortium blockchain (recommended: WenChang Chain / BSN) for evidence only; all data stored domestically.

**中文**
- **POV 见证者证明**：多源加权可信度体系，整合设备元数据（GPS/时间戳）、见证者弱验证（微信实名 2 秒闭环）、多模态 AI 场景识别与社会佐证（组员互证 + 监护人背书）。
- **隐私优先设计**：端侧（WASM/BlazeFace）人脸打码后才上传，原图内存销毁，链上仅存哈希。
- **反作弊统计学风控**：梅特卡夫离散度过滤、地理时空围栏、季节环境一致性检验、见证者信用分——借鉴金融反洗钱/反刷单模型。
- **代币经济学**：自发公益低时间币上限 + 高荣誉激励（勋章/排名），从源头使作弊无利可图。
- **法律合规**：不发币、不交易；推荐文昌链（BSN 开放联盟链）仅作存证；数据与节点全部在境内。

---

## Project Status · 项目状态

| Phase | Status |
| --- | --- |
| Institutional Design · 制度设计 | ✅ Draft complete |
| Manual Audit · 人工审核 | ✅ Spec complete |
| Auto Audit (POV) · 自动审核 | ✅ Spec complete |
| Blockchain Evidence · 区块链存证 | ✅ Spec complete |
| Online System · 线上系统 | 🔄 Spec complete, dev pending |
| Legal Compliance · 法律合规 | ✅ Spec complete |
| English Documentation · 英文文档 | 📋 Planned |
| System Development · 系统开发 | 📋 Planned |

---

## License · 许可证

This project's documentation is shared for educational and reference purposes. For licensing details, please contact the project maintainers.

本项目文档以教育参考为目的共享。许可证详情请联系项目维护者。
