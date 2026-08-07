# 时间银行 · RESTful API 合约（API Contract）

> 本文档定义前端驱动实施所需的顶层 API 合约。它描述资源边界、端点、状态、权限、请求体和通用协议，**对 API 行为具有权威性**；响应载荷的具体字段形状由前端类型模块拥有，且不由任何 OpenAPI 文件裁决——真相来源与优先级见 §10.1。第二阶段接口优先实现；第三、第四阶段接口作为稳定扩展命名空间预留。

---

## 1. 合约目标

- 支持 React Web 前端与后端并行开发。
- 第二阶段完整覆盖学生入口、管理员/教师入口和人工审核闭环。
- 保持资源化、版本化和可测试，避免页面专用 RPC 接口。
- 所有业务规则、权限和账户一致性由后端执行。
- 为第三阶段 POV 和第四阶段兑换、借贷、增值能力保留兼容扩展空间。

---

## 2. 通用协议

### 2.1 基础约定

| 项目 | 约定 |
| --- | --- |
| Base Path | `/api/v1` |
| 协议 | HTTPS |
| 数据格式 | `application/json; charset=utf-8` |
| 时间格式 | ISO 8601，带时区，例如 `2026-09-01T08:30:00+08:00` |
| 日期格式 | `YYYY-MM-DD` |
| ID | 服务端生成的不可猜测字符串；前端不得解析 ID |
| 时长 | 只使用整数**分钟**。API 不下发小时数，也不下发小数时长 |
| 时间币金额 | 整数**分币**（1 时间币 = 100 单位）。`7550` 表示 75.5 时间币。客户端负责展示格式化；API 不下发小数时间币 |
| 字段命名 | JSON 使用 `camelCase` |
| 语言 | 每个请求携带 `Accept-Language`，见 §2.10 |
| API 版本 | URL 主版本；兼容字段只追加、不复用旧语义 |

### 2.2 认证

Web 推荐使用 Secure、HttpOnly、SameSite Cookie 管理会话；如采用访问令牌，也必须配合短期有效期、安全刷新和撤销机制。

认证端点：

| Method | Path | 说明 |
| --- | --- | --- |
| `POST` | `/auth/login` | 账号密码登录 |
| `POST` | `/auth/logout` | 注销当前会话 |
| `POST` | `/auth/refresh` | 刷新会话 |
| `POST` | `/auth/forgot-password` | 发起密码重置 |
| `POST` | `/auth/reset-password` | 使用一次性令牌设置新密码 |
| `GET` | `/me` | 当前用户、角色、权限、数据范围与功能开关 |
| `PATCH` | `/me/profile` | 修改允许自助维护的资料 |
| `POST` | `/me/change-password` | 修改密码 |

登录响应至少返回：

```json
{
  "data": {
    "user": {
      "id": "usr_xxx",
      "displayName": "Example User",
      "roles": ["student"],
      "permissions": ["projects.read", "serviceRecords.create"],
      "studentId": "std_xxx"
    },
    "workspaces": ["student"],
    "preferredLanguage": "zh-CN",
    "featureFlags": {
      "pov": false,
      "redemption": false,
      "borrowing": false,
      "appreciation": false
    }
  }
}
```

`studentId`（2026-08-06 新增，见 `Docs/clarification/01-i3-partner-school-terminology.md`）对教职工账号（教师/管理员/系统管理员）为 `null`——它使前端无需额外请求即可在共同组织者搜索中排除"我自己"。

### 2.3 CSRF 与来源保护

- Cookie 会话的变更请求必须验证 CSRF Token。
- API 校验 `Origin`/`Referer` 和允许来源。
- 登录、密码重置、文件上传和导出接口需要限流。

### 2.4 权限

- API 以权限码判定访问，例如 `projects.manage`、`reviews.decide`。
- 教师的数据范围可限制到年级、班级或指定项目。
- 前端从 `/me` 获取权限用于界面展示，但后端对每次请求重新校验。
- 返回 `403` 时不得泄露无权限资源是否存在。

### 2.5 成功响应

单资源：

```json
{
  "data": {
    "id": "rec_xxx"
  },
  "meta": {
    "requestId": "req_xxx"
  }
}
```

列表：

```json
{
  "data": [],
  "page": {
    "number": 1,
    "size": 20,
    "totalItems": 0,
    "totalPages": 0
  },
  "meta": {
    "requestId": "req_xxx"
  }
}
```

### 2.6 错误响应

```json
{
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "The request contains invalid fields.",
    "fieldErrors": [
      {
        "field": "serviceDate",
        "code": "DATE_OUT_OF_RANGE",
        "message": "The service date is outside the allowed range."
      }
    ],
    "requestId": "req_xxx"
  }
}
```

通用错误码：

| HTTP | Code | 含义 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | 请求结构错误 |
| 401 | `AUTHENTICATION_REQUIRED` | 未登录或会话失效 |
| 403 | `FORBIDDEN` | 无权限 |
| 404 | `RESOURCE_NOT_FOUND` | 资源不存在或不可见 |
| 409 | `STATE_CONFLICT` | 状态不允许、重复操作或版本冲突 |
| 413 | `FILE_TOO_LARGE` | 文件超过限制 |
| 422 | `VALIDATION_FAILED` | 字段或业务校验失败 |
| 429 | `RATE_LIMITED` | 请求过于频繁 |
| 500 | `INTERNAL_ERROR` | 未预期服务错误 |
| 503 | `SERVICE_UNAVAILABLE` | 维护或依赖暂不可用 |

### 2.7 列表、筛选与排序

通用查询参数：

- `page[number]`：从 1 开始。
- `page[size]`：默认 20，最大值由服务端配置。
- `sort`：例如 `-submittedAt,studentName`，前缀 `-` 表示降序。
- `filter[field]`：精确或枚举过滤。
- `search`：资源允许时进行关键词搜索。

服务端必须返回稳定排序；同一请求翻页时不得产生无定义顺序。

### 2.8 并发与幂等

- 可编辑资源在响应体中返回当前 `version`。
- 更新与审核决定通过**请求体字段**带回资源当前的 `version`——`PATCH` 用 `version`，服务记录审核用 `recordVersion`，提案审核用 `proposalVersion`——绝不使用 `If-Match` 头；冲突返回 `409 STATE_CONFLICT`，响应体携带当前 `version` 供客户端据此重试。
- 提交、审核决定、账户调整/冲正、导入确认必须携带 `Idempotency-Key`。记录/项目/提案的创建不需要——具体端点清单见 §13.4。
- 相同幂等键和请求体返回同一业务结果；相同键不同请求体返回冲突。

### 2.9 删除规则

- 已提交、已审核、已入账的业务记录不可物理删除。
- 草稿可删除；基础数据优先停用或归档。
- 账户余额只能通过流水变化，不提供直接修改余额接口。

### 2.10 语言与本地化

Web 前端提供中文与英文两种界面（默认 `zh-CN`）。本地化的切分依据是**字段承载的文本类型**，而不是显示它的页面。

| 文本类型 | 示例 | 传输表示 |
| --- | --- | --- |
| **代码与枚举** | `status`、`decision`、`type`、`sourceType`、`reasonCode`、`recognitionLevel`、`priority` | **永不本地化**。下发稳定代码，标签由客户端负责；对不认识的值必须兜底展示原始代码（§10.2） |
| **字典/基础数据** | 服务类别、组织类型、年级、班级、荣誉等级、规则名称 | 服务端本地化。响应同时携带稳定标识（`categoryId` / `code`）与所解析语言的 `name`。客户端的逻辑与样式一律以标识为准，不得依赖 `name` |
| **用户撰写内容** | 项目标题与描述、提案字段、审核意见、组织名称、复核陈述 | 按录入原样返回，录入什么语言就是什么语言。不做机器翻译，不按语言重复存储 |
| **服务端生成文案** | 通知 `title`/`body`、错误 `message` | 按请求语言渲染。通知另外携带 `templateCode` 与参数，使切换语言后历史通知能重新正确渲染，也允许客户端自行本地化 |

**语言解析顺序：** `Accept-Language` 请求头 → 当前用户的 `preferredLanguage` → 学校默认语言。无法匹配时降级而不是报错；字典项缺少某语言译文时降级到学校默认语言——本地化 `name` 永不为 `null` 或空串。

**`preferredLanguage`** 为受支持集合内的 BCP 47 标签（`zh-CN`、`en`）。由 `/me` 返回，通过 `PATCH /me/profile` 修改，并作为异步产物（导出、邮件、定时通知）的语言依据——这些场景没有请求头可读。客户端可本地缓存以便即时切换，但跨设备以服务端值为准。

**语言不改变数据语义。** 数字、日期、时长和时间币金额始终按 §2.1 的规范形式传输，与语言无关，差异只在展示层。

### 2.11 响应组装

以下三条规则保证页面能够仅凭已获得的响应完成渲染，符合 §8 关于禁止逐行追加请求的约定。

**1. 被引用实体随行下发展示名称。** 凡是界面会在 ID 旁展示可读标签的地方，同一响应对象内必须同时给出该标签：

| 字段 | 随行字段 |
| --- | --- |
| `projectId` | `projectTitle` |
| `studentId` | `studentName` |
| `categoryId` / `serviceCategoryId` | `categoryName` / `serviceCategoryName` |
| `organizationId` | `organizationName` |
| `ownerId`、`reviewerId`、`operatorId` | `ownerName`、`reviewerName`、`operatorName` |
| `relatedRecordId`、`linkedProjectId` | `relatedRecordTitle`、`linkedProjectTitle` |

这些标签是**只读的、时点性的展示快照**：不接受出现在请求体中，不得当作标识使用；若被引用实体后来改名，它与当前名称不一致是正常的。

**2. 随访问者变化的字段以 `my` 前缀标识。** `myApplicationStatus`、`myRole` 等按当前登录主体计算，不得跨用户缓存；不适用时字段缺省而不是返回 `null`。

**3. 服务端计算的聚合值属于资源本身。** `enrolledCount`、`projectCount`、`revisionCount`、`daysWaiting` 以及各类进度合计均由服务端推导，客户端只负责展示，不重算也不覆盖。

---

## 3. 核心资源与状态

### 3.1 项目 `Project`

核心字段：

- `id`, `title`, `summary`, `description`
- `categoryId`, `organizationId`, `ownerId`
- `originProposalId`, `studentOrganizerIds`
- `startAt`, `endAt`, `location`
- `capacity`, `recognizedMinutes`
- `eligibleGrades`, `evidenceRequirements`
- `registrationOpenAt`, `registrationCloseAt`
- `status`, `version`, `createdAt`, `updatedAt`

状态：

```text
draft → published → registrationOpen → inProgress → completed → archived
              ↘ closed
```

状态由时间和管理动作共同决定，前端不自行推导最终状态。

### 3.2 活动提案 `EventProposal`

活动提案由学生创建，用于申请组织面向其他学生的志愿活动。提案获批前不是正式 `Project`，不会出现在项目中心，也不能接受报名。

核心字段：

- `id`, `proposerStudentId`, `coOrganizerStudentIds`, `coOrganizerNames`
- `title`, `objective`, `description`, `expectedOutcome`
- `categoryId`, `plannedStartAt`, `plannedEndAt`, `location`, `format`
- `targetParticipants`, `capacity`, `volunteerTasks`
- `proposedSupervisorId`, `organizationId`, `externalContact`
- `safetyPlan`, `resourceNeeds`, `recruitmentPlan`
- `proposedRecognizedMinutes`, `evidencePlan`
- `status`, `currentRevision`, `assignedReviewerId`
- `approvedProjectId`, `version`, `createdAt`, `submittedAt`, `updatedAt`

`coOrganizerNames`（2026-08-06 随下方写入字段一并新增，见 `Docs/clarification/01-i3-partner-school-terminology.md`）由服务端解析、只读，与 `coOrganizerStudentIds` 按位置一一对应——与 `proposedSupervisorId`/`organizationId` 已有的 `proposedSupervisorName`/`organizationName` 是同一种模式。

**创建/更新提案**（`POST /event-proposals`、`PATCH /event-proposals/{id}`）接受 `organizationId` **或** `newOrganizationName`，二者不可同时提供：

- `organizationId` 引用一个已存在的合作组织（来自 `GET /organizations`）——后端会校验其存在。
- `newOrganizationName` 是学生填写的、系统中尚不存在的合作组织名称。后端会在同一事务内创建该组织（类型默认为 `community`）并将生成的 id 关联到提案——前端不得为此路径直接调用 `POST /organizations`（该端点仍受 `organizations.manage` 权限控制，发起活动的学生从不持有该权限）。
- 二者同时提供将返回 `400`。二者均不提供则提案不关联合作组织，这仍是合法状态（合作组织本就是可选项，与此前一致）。

`coOrganizerStudentIds` 与 `proposedSupervisorId` 均在服务端校验为真实、有效的账号：每个共同组织者 id 必须是真实存在的学生（且不能是发起人本人），`proposedSupervisorId` 必须解析为持有 `teacher` 角色的用户——与 §4.3"批准时指定教师为项目负责人"一致。此前二者均未经校验即被接受；前端现在通过下方的目录搜索来解析，而非自由文本。

状态：

```text
draft → submitted → underReview → approved
                   ↘ changesRequested → submitted
                   ↘ rejected
draft/submitted → withdrawn
```

审批决定：

- `requestChanges`
- `recommendApproval`
- `approve`
- `reject`

教师可否直接批准由权限和审批规则决定；需要学生发展中心终审时，教师使用 `recommendApproval`。批准操作在同一事务中生成正式项目草稿并回填 `approvedProjectId`，因此 `approved` 必须同时具有有效的关联项目。正式项目仍须由具有发布权限的教师或管理员发布。

### 3.3 报名 `Application`

状态：

```text
pending → confirmed → completed
       ↘ waitlisted → confirmed
       ↘ rejected
confirmed → cancelled
```

### 3.4 服务记录 `ServiceRecord`

`sourceType` 标识服务的来源方式，创建时确定：`project`（在已报名项目下完成）、`external`（外部组织组织）、`self`（学生自发）。它决定提交时的必填字段组合，不改变状态机本身。

状态：

```text
draft → submitted → underReview → approved
                   ↘ returned → submitted
                   ↘ rejected → appealPending
approved → corrected
```

核心字段：

- `id`, `studentId`, `projectId`, `sourceType`
- `serviceDate`, `startAt`, `endAt`, `durationMinutes`
- `location`, `organizationId`, `organizationName`
- `serviceCategoryId`, `description`
- `supervisor`, `evidenceFiles`, `includesTravelTime`
- `status`, `currentRevision`, `reviewSummary`
- `creditedUnits`, `accountTransactionId`
- `version`, `createdAt`, `submittedAt`, `updatedAt`

`includesTravelTime` 是披露标志（申报时长是否包含在途时间），创建与更新时可写，不是派生字段。

### 3.5 审核 `Review`

审核决定：

- `approve`
- `returnForChanges`
- `reject`
- `escalate`

审核本身为不可覆盖的事件；修改决定时创建新的复核事件，不覆盖历史记录。

### 3.6 复核/补录 `Case`

`type`：

- `appeal`
- `supplement`
- `manualCorrection`

状态：

```text
draft → submitted → inReview → resolved
                         ↘ needsInformation → submitted
                         ↘ rejected
```

### 3.7 账户与流水

`Account` 是余额视图；`AccountTransaction` 是不可变流水。

流水类型：

- `serviceCredit`
- `correction`
- `freeze`
- `unfreeze`
- `redemption`（第四阶段）
- `borrow`（第四阶段）
- `repayment`（第四阶段）
- `bonus`（第四阶段）

### 3.8 异步任务 `Job`

适用于导入、导出、批量处理和报表生成：

```text
queued → running → succeeded
                 ↘ failed
                 ↘ cancelled
```

前端通过任务查询接口轮询，不保持长连接作为必要依赖。

---

## 4. 第二阶段 API

### 4.1 当前用户与站内通知

| Method | Path | 权限 | 说明 |
| --- | --- | --- | --- |
| `GET` | `/me` | 已登录 | 当前用户、权限、工作区、功能开关 |
| `GET` | `/notifications` | 已登录 | 当前用户通知列表 |
| `GET` | `/notifications/unread-count` | 已登录 | 未读数量 |
| `PATCH` | `/notifications/{id}` | 本人 | 标记单条已读 |
| `POST` | `/notifications/read-all` | 本人 | 全部标记已读 |
| `GET` | `/announcements` | 已登录 | 有效公告 |

### 4.2 项目

| Method | Path | 权限 | 说明 |
| --- | --- | --- | --- |
| `GET` | `/projects` | `projects.read` | 查询可见项目 |
| `GET` | `/projects/{id}` | `projects.read` | 项目详情 |
| `POST` | `/projects` | `projects.create` | 创建草稿 |
| `PATCH` | `/projects/{id}` | `projects.update` | 更新项目 |
| `POST` | `/projects/{id}/publish` | `projects.publish` | 发布 |
| `POST` | `/projects/{id}/archive` | `projects.archive` | 归档已完成的项目 |
| `POST` | `/projects/{id}/cancel` | `projects.cancel` | 取消并直接归档一个进行中（或已完成）的项目，需填写原因 |
| `GET` | `/projects/{id}/timeline` | `projects.audit.read` | 项目变更时间线 |

项目状态变化使用动作端点，避免用通用 `PATCH status` 绕过业务校验。

**状态机（2026-08-08 修订，见 `Docs/clarification/02-PorjectOrganization.md` §2）：** `draft`（草稿）→ `published`（已发布）→ `registrationOpen`（报名开放）→ `closed`（报名关闭）→ `inProgress`（进行中）→ `completed`（已结束）→ `archived`（已归档），其中 `registrationOpen`→`closed`→`inProgress`→`completed` 均由系统按时间自动流转（在读取/操作访问时惰性同步），`closed` 仅表示"报名窗口已结束"，从来不是人工操作，也不代表取消。`POST /projects/{id}/archive` 只能在 `completed` 状态下成功，且必须所有 `CONFIRMED`（已确认）报名学生该项目的服务记录都已进入终态审核结果（`approved`/`rejected`/`corrected`）才能通过；否则返回 409 状态冲突错误，并说明还有多少名学生未完成审核。`POST /projects/{id}/cancel`（`reason` 必填，`@NotBlank`，作为审计记录的 `reasonCode` 存储）是独立的管理员覆盖操作——可从 `draft`/`published`/`registrationOpen`/`closed`/`inProgress`/`completed` 中的任一状态直接归档，完全绕过已审核记录的门槛检查，用于丢弃一个不需要的草稿、彻底放弃某个进行中的项目，或强制关闭一个停滞的项目。此前存在的手动 `POST /projects/{id}/close`（`projects.close`）提前关闭动作现已废弃——既然 `closed` 已完全自动化，`cancel` 承接了它原本的使用场景。`CreateProjectRequest`/`UpdateProjectRequest` 也新增了可选的 `ownerId` 字段：仅管理员可用（按角色校验，而非仅凭 `projects.create`/`.update` 权限，因为教师也持有这两项权限），非管理员提交的 `ownerId` 会被静默忽略并强制设为本人，而不是报错拒绝。

**无人报名自动归档（2026-08-08 新增）：** `registrationOpen`→`closed` 的按时间自动流转在执行前会先检查该项目已确认的报名人数——若确认报名人数为零，项目会直接归档（新增 `registrationOpen`→`archived`，同样由系统触发），而不再进入 `closed`/`inProgress`/`completed`，因为这些状态对一个零报名的项目已没有实际意义。系统会写入一条专门的审计记录（`action: "project.autoArchiveNoRegistrants"`，`reasonCode: "NO_REGISTRANTS_AT_REGISTRATION_CLOSE"`，`actorId`/`permissionUsed` 均为空——因为没有人工操作者）说明原因，与其他状态变更的审计记录区分开来。只要该项目有至少一名已确认的报名学生，就不受影响，仍按原有流程进入 `closed`。

管理后台的"报名名单"入口（`GET /applications?filter[projectId]=`，见 §4.4）只有在项目发布之后才有意义——前端现已对 `draft`（草稿）状态的项目隐藏该操作菜单项，因为草稿阶段报名从未开放过。

### 4.3 学生活动提案

| Method | Path | 权限 | 说明 |
| --- | --- | --- | --- |
| `GET` | `/event-proposals` | 本人/`eventProposals.read` | 学生查询本人提案；教师/管理员查询权限范围审批队列 |
| `POST` | `/event-proposals` | `eventProposals.create` | 学生创建提案草稿 |
| `GET` | `/event-proposals/{id}` | 本人/`eventProposals.read` | 提案详情 |
| `PATCH` | `/event-proposals/{id}` | 提案人且可编辑 | 编辑草稿或需修改的提案 |
| `DELETE` | `/event-proposals/{id}` | 提案人且为草稿 | 删除草稿 |
| `POST` | `/event-proposals/{id}/submit` | 提案人 | 提交审批 |
| `POST` | `/event-proposals/{id}/withdraw` | 提案人/管理权限 | 撤回或申请撤回 |
| `GET` | `/event-proposals/{id}/revisions` | 本人/`eventProposals.read` | 修订历史 |
| `GET` | `/event-proposals/{id}/timeline` | 本人/`eventProposals.read` | 审批时间线 |
| `POST` | `/event-proposals/{id}/reviews` | `eventProposals.review` | 要求修改、推荐批准、批准或驳回 |
| `POST` | `/event-proposals/{id}/assign` | `eventProposals.assign` | 分配或转交评审人 |
| `GET` | `/event-proposal-queue/summary` | `eventProposals.review` | 待办数量与审批 SLA |

审批请求：

```json
{
  "decision": "requestChanges",
  "reasonCode": "SAFETY_PLAN_INCOMPLETE",
  "comment": "Please add an adult supervision and emergency contact plan.",
  "proposalVersion": 2
}
```

批准提案时由后端完成：

1. 写入不可覆盖的审批事件。
2. 将提案状态更新为 `approved`。
3. 根据批准版本生成正式 `Project` 草稿。
4. 将学生记录为 `studentOrganizer`，将指定教师记录为项目责任人。
5. 回填 `approvedProjectId` 并创建站内通知。

前端不得先创建项目再单独更新提案，以免出现无来源项目或重复项目。

### 4.4 报名

| Method | Path | 权限 | 说明 |
| --- | --- | --- | --- |
| `GET` | `/applications` | `applications.read` | 查询本人或权限范围报名 |
| `GET` | `/applications/{id}` | `applications.read` | 报名详情 |
| `POST` | `/projects/{projectId}/applications` | `applications.create` | 学生报名 |
| `POST` | `/applications/{id}/cancel` | 本人/管理权限 | 取消报名 |
| `POST` | `/applications/{id}/confirm` | `applications.manage` | 管理员确认 |
| `POST` | `/applications/{id}/reject` | `applications.manage` | 拒绝报名 |
| `POST` | `/applications/bulk-actions` | `applications.manage` | 批量确认、拒绝或标记完成 |

### 4.5 服务记录

| Method | Path | 权限 | 说明 |
| --- | --- | --- | --- |
| `GET` | `/service-records` | `serviceRecords.read` | 查询本人或权限范围记录 |
| `POST` | `/service-records` | `serviceRecords.create` | 创建草稿 |
| `GET` | `/service-records/{id}` | `serviceRecords.read` | 记录详情 |
| `PATCH` | `/service-records/{id}` | 本人且为可编辑状态 | 更新草稿/退回记录 |
| `DELETE` | `/service-records/{id}` | 本人且为草稿 | 删除草稿 |
| `POST` | `/service-records/{id}/submit` | 本人 | 提交审核 |
| `POST` | `/service-records/{id}/withdraw` | 本人且未开始审核 | 撤回 |
| `GET` | `/service-records/{id}/timeline` | `serviceRecords.read` | 记录时间线 |
| `GET` | `/service-records/{id}/revisions` | `serviceRecords.read` | 修订历史 |
| `GET` | `/service-records/{id}/reviews` | `serviceRecords.read` | 审核历史 |

服务端在提交时完成：

- 必填与格式校验。
- 服务时间和时长规则校验。
- 项目资格与报名关系校验。
- 重复记录检测。
- 证明材料完整性校验。

### 4.6 文件

推荐受控直传流程：

| Method | Path | 权限 | 说明 |
| --- | --- | --- | --- |
| `POST` | `/files/upload-intents` | 已登录 | 获取短时上传凭证 |
| `POST` | `/files/{id}/complete` | 上传者 | 确认上传完成并触发校验 |
| `GET` | `/files/{id}` | 有资源访问权 | 获取元数据和短时下载地址 |
| `DELETE` | `/files/{id}` | 上传者且未绑定已提交记录 | 删除临时文件 |

`upload-intents` 请求包含文件名、MIME、大小和用途；服务端返回允许的上传地址、请求头、过期时间与 `fileId`。文件完成病毒扫描和类型校验前状态为 `processing`，不可提交审核。

### 4.7 审核中心

| Method | Path | 权限 | 说明 |
| --- | --- | --- | --- |
| `GET` | `/review-queue` | `reviews.read` | 查询审核队列 |
| `GET` | `/review-queue/summary` | `reviews.read` | 待办与 SLA 汇总 |
| `POST` | `/service-records/{id}/reviews` | `reviews.decide` | 提交审核决定 |
| `POST` | `/service-records/reviews/bulk` | `reviews.bulkDecide` | 批量审核 |
| `POST` | `/service-records/{id}/assign` | `reviews.assign` | 分配或转交审核人 |

审核请求：

```json
{
  "decision": "returnForChanges",
  "reasonCode": "MISSING_EVIDENCE",
  "comment": "Please add confirmation from the organization.",
  "recordVersion": 3
}
```

审核通过必须在单一后端事务中：

1. 写入审核事件。
2. 更新服务记录状态。
3. 生成时间币流水。
4. 更新账户余额视图。
5. 更新达标进度。
6. 创建站内通知。

前端不依次调用多个接口模拟该事务。

### 4.8 复核与补录

| Method | Path | 权限 | 说明 |
| --- | --- | --- | --- |
| `GET` | `/cases` | `cases.read` | 查询本人或权限范围案件 |
| `POST` | `/cases` | `cases.create` | 创建复核/补录草稿 |
| `GET` | `/cases/{id}` | `cases.read` | 详情 |
| `PATCH` | `/cases/{id}` | 本人且草稿/补充状态 | 编辑 |
| `POST` | `/cases/{id}/submit` | 本人 | 提交 |
| `POST` | `/cases/{id}/decisions` | `cases.decide` | 管理员处理 |
| `GET` | `/cases/{id}/timeline` | `cases.read` | 时间线 |

### 4.9 学生、班级、年级与学年

| Method | Path | 权限 | 说明 |
| --- | --- | --- | --- |
| `GET` | `/students` | `students.read` | 学生列表 |
| `POST` | `/students` | `students.manage` | 创建学生 |
| `GET` | `/students/{id}` | 本人/`students.read` | 学生详情 |
| `PATCH` | `/students/{id}` | `students.manage` | 更新权威资料 |
| `POST` | `/students/{id}/activate` | `students.manage` | 启用 |
| `POST` | `/students/{id}/deactivate` | `students.manage` | 停用 |
| `GET` | `/students/{id}/progress` | 本人/`progress.read` | 达标进度 |
| `GET` | `/students/{id}/service-records` | 本人/`students.read` | 服务历史 |
| `GET` | `/students/directory` | `students.read`/`directory.read` | 按姓名搜索，返回精简的 `{id, displayName, gradeName, className}`，用于挑选共同组织者（2026-08-06 新增，见 `Docs/clarification/01-i3-partner-school-terminology.md`） |
| `GET` | `/academic-years` | 已登录 | 学年 |
| `GET` | `/grades` | 已登录 | 年级 |
| `GET` | `/classes` | 已登录 | 班级 |

`directory.read` 刻意比 `students.read` 更窄：这是 `role_student` 持有的权限（学生原本没有任何方式查到其他学生或教师的 id），其响应从不携带 `/students` 列表响应中的 `studentNumber`/`status`/`graduationYear` 或联系方式字段。`search` 为必填参数，结果数量有上限（20 条）——这是一个联想搜索，不是目录浏览。

### 4.10 组织与服务类别

| Method | Path | 权限 | 说明 |
| --- | --- | --- | --- |
| `GET` | `/organizations` | `organizations.read` | 组织列表——支持 `filter[type]`、`filter[status]`、`search`（仅按名称）（2026-08-08 新增，见 `02-PorjectOrganization.md` §4；此前这些参数虽然接受但会被静默忽略） |
| `POST` | `/organizations` | `organizations.manage` | 创建 |
| `GET` | `/organizations/{id}` | `organizations.read` | 详情 |
| `PATCH` | `/organizations/{id}` | `organizations.manage` | 更新 |
| `POST` | `/organizations/{id}/archive` | `organizations.manage` | 归档——唯一的移除方式；按设计不提供永久删除（见 `02-PorjectOrganization.md` §4） |
| `GET` | `/service-categories` | 已登录 | 服务类别 |
| `POST` | `/service-categories` | `rules.manage` | 创建类别 |
| `PATCH` | `/service-categories/{id}` | `rules.manage` | 更新类别 |

`organizations.manage` 现由 `role_admin` 和 `role_teacher` 共同持有（2026-08-08 变更——此前仅管理员持有）。同一变更中，`Organization` 新增了 `description`（自由文本简介）字段，与既有的结构化字段 `type`/`contactName`/`contactEmail`/`contactPhone`/`address` 并列；`CreateOrganizationRequest`/`UpdateOrganizationRequest`/`OrganizationDto` 均已携带该字段。

### 4.11 账户与流水

| Method | Path | 权限 | 说明 |
| --- | --- | --- | --- |
| `GET` | `/accounts` | `accounts.read` | 权限范围账户列表 |
| `GET` | `/accounts/{id}` | 本人/`accounts.read` | 账户余额视图 |
| `GET` | `/accounts/{id}/transactions` | 本人/`accounts.read` | 流水 |
| `GET` | `/account-transactions/{id}` | 本人/`accounts.read` | 流水详情 |
| `POST` | `/accounts/{id}/adjustments` | `accounts.adjust` | 人工调整并生成流水 |
| `POST` | `/account-transactions/{id}/reverse` | `accounts.reverse` | 冲正指定流水 |
| `POST` | `/accounts/{id}/freeze` | `accounts.freeze` | 冻结 |
| `POST` | `/accounts/{id}/unfreeze` | `accounts.freeze` | 解冻 |

所有变更请求必须包含标准化原因码与说明。冲正生成反向流水，不删除原流水。

### 4.12 规则

| Method | Path | 权限 | 说明 |
| --- | --- | --- | --- |
| `GET` | `/rules` | `rules.read` | 查询当前或历史规则 |
| `POST` | `/rules` | `rules.manage` | 创建规则草稿 |
| `GET` | `/rules/{id}` | `rules.read` | 规则详情 |
| `PATCH` | `/rules/{id}` | `rules.manage` | 更新草稿 |
| `POST` | `/rules/{id}/validate` | `rules.manage` | 校验并返回影响摘要 |
| `POST` | `/rules/{id}/publish` | `rules.publish` | 发布并设置生效时间 |
| `POST` | `/rules/{id}/archive` | `rules.manage` | 归档 |

已生效规则不可原地修改；变更必须创建新版本。

### 4.13 仪表盘、统计与报表

| Method | Path | 权限 | 说明 |
| --- | --- | --- | --- |
| `GET` | `/dashboards/student` | 学生 | 学生首页聚合 |
| `GET` | `/dashboards/teacher` | 教师 | 教师待办与范围统计 |
| `GET` | `/dashboards/admin` | 管理员 | 全校工作台 |
| `GET` | `/reports/participation` | `reports.read` | 参与率 |
| `GET` | `/reports/completion` | `reports.read` | 达标率 |
| `GET` | `/reports/service-hours` | `reports.read` | 服务时长 |
| `GET` | `/reports/review-efficiency` | `reports.read` | 审核效率 |
| `GET` | `/reports/top-projects` | `reports.read` | 所选周期内参与度最高的项目 |
| `POST` | `/exports` | `exports.create` | 创建导出任务 |
| `GET` | `/exports/{jobId}` | 创建者/管理员 | 查询导出任务 |
| `GET` | `/exports/{jobId}/download` | 创建者/管理员 | 获取短时下载地址 |

仪表盘端点只做首页聚合；业务详情仍通过资源端点获取，不能把所有页面数据集中到单一“大接口”。

### 4.14 导入

| Method | Path | 权限 | 说明 |
| --- | --- | --- | --- |
| `GET` | `/imports/templates/{type}` | `imports.create` | 下载模板 |
| `POST` | `/imports` | `imports.create` | 创建导入任务 |
| `GET` | `/imports/{id}` | `imports.read` | 导入详情 |
| `POST` | `/imports/{id}/validate` | `imports.create` | 预校验 |
| `GET` | `/imports/{id}/errors` | `imports.read` | 错误列表/文件 |
| `POST` | `/imports/{id}/commit` | `imports.commit` | 确认导入 |
| `POST` | `/imports/{id}/cancel` | `imports.create` | 取消 |

导入必须先校验后确认。重复导入通过来源文件 Hash、业务唯一键和幂等键共同防护。

### 4.15 用户、角色与审计

| Method | Path | 权限 | 说明 |
| --- | --- | --- | --- |
| `GET` | `/users` | `users.read` | 用户列表 |
| `POST` | `/users` | `users.manage` | 创建用户 |
| `PATCH` | `/users/{id}` | `users.manage` | 更新用户 |
| `POST` | `/users/{id}/unlock` | `users.manage` | 解锁 |
| `POST` | `/users/{id}/reset-password` | `users.manage` | 管理员触发重置 |
| `GET` | `/roles` | `roles.read` | 角色列表 |
| `POST` | `/roles` | `roles.manage` | 创建角色 |
| `PATCH` | `/roles/{id}` | `roles.manage` | 更新角色权限 |
| `GET` | `/permissions` | `roles.read` | 权限码目录 |
| `GET` | `/users/directory` | `users.read`/`directory.read` | 按姓名与角色搜索，返回精简的 `{id, displayName}`，用于挑选拟邀请指导教师（2026-08-06 新增，见 `Docs/clarification/01-i3-partner-school-terminology.md`）——必须提供 `role` 查询参数（如 `teacher`），不暴露 username/email/studentId/roleCodes/status 等其他用户字段 |
| `GET` | `/audit-logs` | `auditLogs.read` | 操作审计 |
| `GET` | `/login-logs` | `securityLogs.read` | 登录日志 |
| `GET` | `/system/settings` | `settings.read` | 系统参数 |
| `PATCH` | `/system/settings` | `settings.manage` | 更新允许修改的参数 |

### 4.16 学校信息

| Method | Path | 权限 | 说明 |
| --- | --- | --- | --- |
| `GET` | `/school/info` | 已登录 | 学校基本信息（名称、学生发展中心、联系方式等） |

响应示例：

```json
{
  "data": {
    "name": "示例中学",
    "studentDevCenter": "学生发展中心",
    "contact": "dev-center@example.edu.cn",
    "academicYear": "2025-2026"
  }
}
```

该端点返回低频变化的学校级配置信息，前端可在应用初始化时获取并缓存。与 `/system/settings` 的区别在于：`/school/info` 面向所有已登录用户，返回学校展示层信息；`/system/settings` 面向管理员，返回可配置的系统参数。

---

## 5. 权限矩阵

| 资源/动作 | 学生 | 教师 | 学生发展中心管理员 | 系统管理员 |
| --- | --- | --- | --- | --- |
| 浏览项目 | 可见范围 | 可见范围 | 全校 | 全校 |
| 创建/发布项目 | 否 | 授权范围 | 是 | 按配置 |
| 提交活动提案 | 本人 | 否 | 可代办需专门权限 | 否 |
| 审批活动提案 | 否 | 数据范围内评估/推荐，批准按权限 | 全校审批 | 默认只读 |
| 报名 | 本人 | 否 | 代办需专门权限 | 否 |
| 提交服务记录 | 本人 | 代录需专门权限 | 代录需专门权限 | 否 |
| 审核记录 | 否 | 数据范围内 | 全校 | 默认只读 |
| 复核决定 | 否 | 按授权 | 是 | 默认只读 |
| 查看账户 | 本人 | 数据范围内只读 | 全校 | 按权限 |
| 调整/冲正账户 | 否 | 否 | 专门权限 | 专门权限 |
| 规则管理 | 否 | 只读 | 是 | 系统参数为主 |
| 导入/导出 | 本人数据不单独导出 | 范围内 | 全校 | 系统级 |
| 用户/角色管理 | 否 | 否 | 部分用户 | 是 |
| 审计日志 | 本人时间线 | 范围内业务日志 | 全校业务日志 | 全部 |

权限矩阵用于默认设计，最终以学校职责分工配置为准。

---

## 6. 第三阶段预留 API：POV

以下端点在第二阶段可返回 `404` 或明确的 `FEATURE_DISABLED`；启用时仍使用 `/api/v1`：

| Method | Path | 说明 |
| --- | --- | --- |
| `POST` | `/pov-sessions` | 创建现场 POV 会话 |
| `GET` | `/pov-sessions/{id}` | POV 状态 |
| `POST` | `/pov-sessions/{id}/check-ins` | 提交 GPS/时间戳 |
| `POST` | `/pov-sessions/{id}/media` | 关联脱敏材料 |
| `POST` | `/pov-sessions/{id}/witness-invitations` | 创建见证二维码/短时凭证 |
| `POST` | `/witness-confirmations` | 见证者确认 |
| `POST` | `/pov-sessions/{id}/peer-confirmations` | 组员互证 |
| `POST` | `/pov-sessions/{id}/guardian-endorsements` | 监护人背书 |
| `POST` | `/pov-sessions/{id}/submit` | 提交 POV 审核 |
| `GET` | `/pov-review-queue` | Web 管理端 POV 队列 |
| `GET` | `/service-records/{id}/risk-assessment` | AI/规则风险结果 |
| `POST` | `/verification-calls` | 创建随机抽查任务 |
| `POST` | `/verification-calls/{id}/result` | 记录核验结果 |
| `GET` | `/attestations/{id}` | 存证状态 |

POV 最终生成或关联标准 `ServiceRecord`，审核通过后仍通过标准账户流水入账。

---

## 7. 第四阶段预留 API

### 7.1 兑换

| Method | Path | 说明 |
| --- | --- | --- |
| `GET` | `/redemption-items` | 学生可见兑换目录 |
| `GET` | `/redemption-items/{id}` | 兑换详情 |
| `POST` | `/redemption-orders` | 创建兑换申请 |
| `GET` | `/redemption-orders` | 本人或管理范围订单 |
| `POST` | `/redemption-orders/{id}/cancel` | 取消 |
| `POST` | `/redemption-orders/{id}/decisions` | 审批 |
| `POST` | `/redemption-orders/{id}/fulfill` | 标记履约 |

### 7.2 借贷

| Method | Path | 说明 |
| --- | --- | --- |
| `GET` | `/borrowing/eligibility` | 查询本人资格与额度 |
| `POST` | `/borrowing-applications` | 创建申请 |
| `GET` | `/borrowing-applications` | 查询申请 |
| `POST` | `/borrowing-applications/{id}/decisions` | 审批 |
| `POST` | `/borrowing-applications/{id}/repayments` | 记录归还 |

### 7.3 增值与荣誉

| Method | Path | 说明 |
| --- | --- | --- |
| `GET` | `/recognition-levels` | 荣誉等级 |
| `GET` | `/students/{id}/recognitions` | 学生荣誉 |
| `GET` | `/bonus-rules` | 增值规则 |
| `POST` | `/bonus-rules` | 创建规则 |
| `POST` | `/bonus-rules/{id}/publish` | 发布规则 |
| `GET` | `/bonus-awards` | 奖励流水 |

上述能力使用 Feature Flag 控制。未启用时前端不展示操作入口，后端拒绝变更请求。

---

## 8. 缓存与性能约定

- 字典、年级、类别等低频变化资源可使用 `ETag`/`If-None-Match`。
- 账户、审核状态和权限数据不得长期缓存。
- 列表必须分页；导出大量数据使用异步任务。
- 仪表盘允许短时聚合缓存，并返回 `generatedAt`。
- API 应返回完成页面任务所需的数据，避免明显的逐行 N+1 请求。

---

## 9. 审计与隐私

- 每个变更请求关联 `requestId`、操作者、时间、来源和业务对象。
- 登录、权限修改、审核决定、账户变化、导入导出和规则发布必须写审计日志。
- API 不向列表返回不必要的手机号、邮箱或监护人信息。
- 敏感字段按权限脱敏，下载地址短时有效。
- 前端不得把访问令牌、敏感材料或完整错误对象写入浏览器日志。
- 审计日志不可通过普通管理 API 修改或删除。

---

## 10. 真相来源与变更管理

### 10.1 真相来源与优先级

原先"后端实现前先手工产出 OpenAPI 文件"的要求**予以撤销**。该要求写于尚无客户端之时；如今完整的 React 前端已经存在，并且已经解决了预写规范本想解决的问题——但只覆盖了部分接口面（§13.2）。因此合约采用分层结构，每份产物只对自己真正验证过的部分具有权威性。

| 产物 | 权威范围 | 非权威范围 |
| --- | --- | --- |
| **本文档** | 行为：资源、状态机、权限与数据范围、事务边界、错误码、幂等与并发、**请求体**（§13.4） | 响应字段的完整清单 |
| **`frontend/src/app/api/types.ts`** —— 由 `frontend/src/app/mock/data.ts` 原样抽出的载荷类型 | **响应载荷形状**：前端消费的每个端点的字段名、可选性与单位 | 任何行为、状态语义或权限规则 |
| 后端运行时由 springdoc 生成的 **`openapi.json`** | 无。仅作为工具链与评审差异展示用的衍生产物 | 全部 |

**优先级。** 行为冲突以本文档为准；响应字段冲突以类型模块为准；两者确实矛盾时以本文档为准，并在**同一个 PR 内**修正类型模块。`openapi.json` 永不裁决：它只被重新生成、不被手工编辑，也不作为合并门槛。

**为什么不采用规范先行。** 合约的读取面已由一个真实渲染全部字段的界面验证过，这比规范评审更强。**为什么不让代码生成的规范充当裁决者。** 那等于把裁决权交给后端"恰好输出了什么"，迫使一个已经可用的前端反过来追随后端。

**出现第二个客户端时重新评估。** 一旦第三阶段小程序开始消费同一 API，两个消费者之间可能产生分歧，前端类型模块就不再是中立裁决者；届时改为由 `openapi.json` 生成共享类型才是正确做法。本决定不阻碍该演进。

### 10.2 变更规则

- 新增可选字段属于兼容变更。
- 删除字段、修改类型、改变枚举语义或收紧原有行为属于破坏性变更。
- 破坏性变更进入新的 API 主版本。
- 枚举新增值时，客户端必须有未知值兜底展示（展示原始代码）。
- **一次合约变更以一个 PR 落地**，同时改动本文档、其英文版、类型模块、API Client 和后端实现。只改其中之一，正是"漂移"的定义。

### 10.3 完成定义

一个端点只有同时满足以下条件才视为完成：

- 行为符合本文档，响应符合类型模块。
- 权限与数据范围测试通过，包含越权不可见返回 `404` 的场景。
- 正常、校验失败、无权限、并发冲突和重复请求行为均已定义并测试。
- 审计与幂等要求按风险等级落实。
- **消费该端点的前端页面在关闭 Mock Client 后，能对接真实后端正确渲染。** 这是取代原 OpenAPI 校验门槛的验收关口——渲染成功比 Schema 比对更严格，因为它同时能发现缺失的展示名称、错误的单位和不可用的分页。

---

## 11. 第二阶段 API 验收场景

1. 学生提交活动提案，教师要求修改，学生重新提交，管理员批准并生成正式项目草稿。
2. 管理员或责任教师发布由提案生成的项目，其他学生查询并报名，教师处理名单。
3. 学生创建草稿、上传材料并提交服务记录。
4. 教师退回记录，学生补充后重新提交，教师审核通过。
5. 审核通过在单一事务中生成账户流水并更新达标进度。
6. 学生发起复核，学生发展中心处理并产生完整时间线。
7. 管理员导入历史台账，查看校验错误并确认有效数据。
8. 教师只能访问授权年级/班级，越权访问返回 `403` 或不可见的 `404`。
9. 重复提交、重复审批、重复审核和重复账户调整不会生成重复项目或重复流水。
10. 并发编辑返回明确冲突，不静默覆盖其他用户修改。
11. Web 在没有微信及其他 IM 接入的环境中通过全部端到端验收。
12. 每个页面在 `zh-CN` 与 `en` 下均能正确渲染：没有本地化后的枚举或状态值出现在传输层，也没有字典项在非默认语言下返回空名称。

---

## 12. 前端实现约定

### 12.1 API Client 层

前端通过统一的 API Client 层（`src/app/api/client.ts`）访问所有后端数据。API Client 按资源域分组（如 `api.projects`、`api.serviceRecords`、`api.accounts`），每个方法对应一个 REST 端点，返回 `Promise<T>`。

当前阶段 API Client 以 Mock 数据为底座，通过 `delay()` 模拟网络延迟。后端就绪后，将方法实现替换为 `fetch` 调用即可，组件层无需改动。

### 12.2 数据获取模式

组件使用 `useState` + `useEffect` 模式获取数据，不直接导入 Mock 常量：

```tsx
// ✅ 正确：通过 API Client 获取
const [projects, setProjects] = useState<Project[]>([]);
useEffect(() => {
  api.projects.list().then(setProjects).catch(() => {});
}, []);

// ❌ 错误：直接导入 Mock 数据
import { MOCK_PROJECTS } from '../../mock/data';
```

需要加载状态时，在数据为 `null` 或空数组时返回加载占位：

```tsx
const [progress, setProgress] = useState<StudentProgress | null>(null);
useEffect(() => {
  api.students.progress(user?.studentId || 'std_001').then(setProgress).catch(() => {});
}, [user?.studentId]);

if (!progress) return <div className="p-4 lg:p-6">加载中...</div>;
```

### 12.3 类型来源

TypeScript 类型（如 `Project`、`ServiceRecord`、`Account`）目前从 `../../mock/data` 导入，该文件同时充当类型定义源。按 §10.1，这些类型即**权威的响应形状**，因此原样抽出到 `frontend/src/app/api/types.ts`，`mock/data.ts` 只保留假数据。此后组件与 API Client 从 API 层导入类型而不是从 Mock 层导入，将来删除假数据不会带走任何类型信息。

### 12.4 仪表盘聚合端点

学生首页使用 `GET /dashboards/student` 一次性获取进度、账户、近期记录和推荐项目。管理后台使用 `GET /dashboards/admin` 获取统计、待审核队列和活跃项目。聚合端点只用于首页，详情页仍使用资源端点。

### 12.5 组织与学校信息

组织列表通过 `api.organizations.list()` 获取，学校信息通过 `api.school.getInfo()` 获取。这些低频变化数据可在组件挂载时获取并缓存于组件状态。

---

## 13. 前端消费映射

本章的目的，是让后端对着前端**实际消费的内容**来建设，而不是对着 §3–§4 的理想解读来建设。它记录映射关系、Mock 中**不得**照抄的取巧做法，以及每一处已知差异及其处理方式。

### 13.1 API Client 方法 → 端点

| Client 方法 | 端点 | 是否已接入页面 |
| --- | --- | --- |
| `auth.login` | `POST /auth/login` | 是 |
| `auth.me` | `GET /me` | 否 |
| `auth.logout` | `POST /auth/logout` | 否 |
| `school.getInfo` | `GET /school/info` | 是 |
| `projects.list` | `GET /projects` | 是 |
| `projects.get` | `GET /projects/{id}` | 否 |
| `projects.create` / `update` | `POST /projects` / `PATCH /projects/{id}` | 否 |
| `projects.publish` / `archive` / `cancel` | `POST /projects/{id}/publish` / `/archive` / `/cancel` | 否 |
| `eventProposals.list` | `GET /event-proposals` | 是 |
| `eventProposals.get` | `GET /event-proposals/{id}` | 否 |
| `eventProposals.create` / `update` | `POST /event-proposals` / `PATCH /event-proposals/{id}` | 否 |
| `eventProposals.submit` / `withdraw` | `POST /event-proposals/{id}/submit` / `/withdraw` | 否 |
| `eventProposals.review` | `POST /event-proposals/{id}/reviews` | 否 |
| `applications.list` | `GET /applications` | 否 |
| `applications.create` | `POST /projects/{projectId}/applications` | 否 |
| `applications.cancel` / `confirm` | `POST /applications/{id}/cancel` / `/confirm` | 否 |
| `serviceRecords.list` | `GET /service-records` | 是 |
| `serviceRecords.get` | `GET /service-records/{id}` | 否 |
| `serviceRecords.create` / `update` / `delete` | `POST` / `PATCH` / `DELETE /service-records[/{id}]` | 否 |
| `serviceRecords.submit` | `POST /service-records/{id}/submit` | 否 |
| `reviewQueue.list` | `GET /review-queue` | 是 |
| `reviewQueue.summary` | `GET /review-queue/summary` | 否 |
| `reviewQueue.decide` | `POST /service-records/{id}/reviews` | 否 |
| `accounts.get` | `GET /accounts?filter[studentId]=…` | 是 |
| `accounts.transactions` | `GET /accounts/{id}/transactions` | 是 |
| `students.list` | `GET /students` | 是 |
| `students.get` | `GET /students/{id}` | 否 |
| `students.progress` | `GET /students/{id}/progress` | 是 |
| `organizations.list` | `GET /organizations` | 是 |
| `notifications.list` | `GET /notifications` | 是 |
| `notifications.unreadCount` | `GET /notifications/unread-count` | 是 |
| `notifications.markRead` / `markAllRead` | `PATCH /notifications/{id}` / `POST /notifications/read-all` | 是 |
| `dashboards.student` / `admin` | `GET /dashboards/student` / `/admin` | 是 |
| `reports.data` | 组合 `GET /reports/participation`、`/completion`、`/service-hours`、`/top-projects` | 是 |
| `users.list` | `GET /users` | 否 |

`api.accounts.get(studentId)` 传入的是学生 ID，而账户端点以账户 ID 为键。该差异在 Client 内解决——先按 `studentId` 过滤，再用返回的 `accountId`——而不是新增以学生为键的账户端点。

### 13.2 前端已验证与未验证的部分

**已验证：读取面。** 每个页面都通过 `api.*` 取数，没有任何组件直接引用假数据常量，因此上表中"是"的那些响应形状，已由真实渲染全部字段的页面验证过。这些形状具有权威性（§10.1）。

**未验证：整个写入面。** 没有任何组件调用创建、修改、提交、审核、发布、确认或取消方法；这些操作目前只弹出提示。**从未构造或发送过任何请求体。** 文件上传在前端完全不存在——证明材料是预置对象并直接携带 `url`。

由此产生的现实结论：§13.4 的请求体与 §4.6 的上传流程**没有任何**前端验证，联调缺口将集中在此。它们必须在前端接入写操作之前定稿，而该接入工作是审核闭环验收的前置条件，不是事后补做的收尾。

### 13.3 Mock 中不得照抄的取巧做法

Mock Client 有意采取了若干捷径，不得被当作合约行为：

| Mock 取巧做法 | 真实要求 |
| --- | --- |
| `reviewQueue.decide` 在客户端改状态并从队列里移除条目 | 后端单一事务：审核事件、状态、账户流水、进度、通知（§4.7） |
| 列表直接返回数组 | `{data, page, meta}` 信封（§2.5）；Client 解包 `data`，在需要分页的界面暴露 `page` |
| 提案批准只翻转状态 | 批准同一事务内生成正式项目草稿并回填 `approvedProjectId`（§4.3） |
| `MOCK_USERS` 带 `password` 字段 | 任何响应中都不存在 |
| 学生列表假数据带 `guardianName` / `guardianContact` | 列表响应不返回监护人与联系方式（§9） |
| `EvidenceFile.url` 是永久直链 | 由 `GET /files/{id}` 校验权限后签发短时地址，并记录访问审计（§4.6） |
| 类别名称里放的是 i18n key（`mock.categories.cat_campus`） | 字典名称由服务端本地化，客户端以 `categoryId` 为键（§2.10） |
| 在浏览器内对全量数组做筛选与搜索 | 服务端 `filter[...]` / `search` / `sort` 加分页（§2.7） |

### 13.4 请求体

由于没有任何写操作被客户端验证过，请求形状在此明确规定，而不是靠推断：

**通用规则：** 请求体是 §3 所列资源核心字段中**可写的那部分**。以下三类字段一律以 `422 VALIDATION_FAILED` 拒绝，而不是静默忽略，以免客户端误以为自己设置成功：

- **状态字段。** 状态流转一律走动作端点（`/submit`、`/publish`、`/reviews`），不走 `PATCH`。
- **随行展示名称**（§2.11 规则 1）——`projectTitle`、`studentName`、`categoryName` 等只是输出。
- **服务端计算值**——`enrolledCount`、由 `startAt`/`endAt` 推导的 `durationMinutes`、`creditedUnits`、`balanceAfter`、各类时间戳，以及全部 `my*` 字段。`version`（及其在各资源上的别名 `recordVersion`/`proposalVersion`）不是一律拒绝：创建时拒绝（服务端赋值为 `1`），更新与审核决定时则**必须**作为请求体字段提供（§2.8）——绝不使用 `If-Match` 头。

**各端点的附加要求：**

| 端点 | 请求体要求 |
| --- | --- |
| `POST /service-records` | `sourceType` 及其必需伴随字段（§3.4）：`project` 需 `projectId`；`external` 需 `organizationId`/`organizationName` 与负责人联系方式。另需 `serviceDate`、`startAt`、`endAt`、`serviceCategoryId`、`description`、证明文件 ID、`includesTravelTime` |
| `POST /service-records/{id}/submit` | 空请求体；必须携带 `Idempotency-Key`。全部提交校验（§4.5）在此执行，而不是在创建时 |
| `POST /service-records/{id}/reviews` | `decision`、`reasonCode`、`comment`、`recordVersion`；必须携带 `Idempotency-Key` |
| `POST /event-proposals/{id}/reviews` | `decision`、`reasonCode`、`comment`、`proposalVersion`；必须携带 `Idempotency-Key` |
| `POST /projects/{projectId}/applications` | 空请求体或仅 `note`——学生身份取自登录主体，绝不作为请求字段 |
| `POST /projects`、`PATCH /projects/{id}` | §3.1 核心字段中可写的部分（绝不含 `status`，其只走动作端点）。`PATCH` 另需 `version` |
| `POST /event-proposals`、`PATCH /event-proposals/{id}` | §3.2 核心字段中可写的部分（绝不含 `status`）。`PATCH` 另需 `version` |
| `POST /accounts/{id}/adjustments` | 以分币为单位的带符号整数 `amount`、`reasonCode`、`memo`；必须携带 `Idempotency-Key` |
| `POST /account-transactions/{id}/reverse` | `reasonCode`、`memo`——不含 `amount`，冲正金额与原流水一致。必须携带 `Idempotency-Key` |
| `POST /files/upload-intents` | `filename`、`mimeType`、`sizeBytes`、`purpose`（§4.6） |
| `PATCH /me/profile` | 仅自助维护字段，含 `preferredLanguage`（§2.10） |

### 13.5 差异清单与处理方式

前端现状与本合约之间的已知差异。处理原则：**行为、状态、单位和隐私以合约为准；响应形状以前端为准。**

| # | 事项 | 前端现状 | 处理方式 | 改动落在 |
| --- | --- | --- | --- | --- |
| 1 | 提案状态 | `needsRevision`，且没有 `underReview` | 合约为准：`changesRequested`，并存在 `underReview`（§3.2） | 前端 |
| 2 | 记录状态 | 没有 `appealPending` | 合约为准；接入复核时补齐（§3.4） | 前端 |
| 3 | 时间币金额 | `balance: 6` 表示 6 时间币 | 合约为准：传输层为整数分币，展示时格式化（§2.1） | 前端格式化 |
| 4 | 时长 | `completedHours` 等与分钟字段重复 | 合约为准：传输层只有分钟，小时在界面换算（§2.1） | 前端 |
| 5 | 类别名称 | 存放 i18n key | 合约为准：由服务端下发本地化 `name`，以 `categoryId` 为键（§2.10） | 前端与假数据 |
| 6 | 学生列表中的监护人字段 | 存在 | 合约为准：列表不返回（§9） | 前端假数据与后端 |
| 7 | 证明材料地址 | 永久直链 | 合约为准：短时且校验权限（§4.6） | 前端上传接入 |
| 8 | 列表信封与分页 | 裸数组、客户端筛选 | 合约为准（§2.5、§2.7） | 前端 Client 层 |
| 9 | ID 旁的展示名称（`projectTitle`、`studentName` 等） | 全面存在 | **前端为准**——已固化为 §2.11 | 合约（已完成） |
| 10 | `enrolledCount`、`myApplicationStatus`、`daysWaiting`、`hasAnomaly`、`priority` | 存在 | **前端为准**——按 §2.11 由服务端计算。第二阶段的异常标记基于规则，不来自 AI | 合约（已完成） |
| 11 | 聚合载荷（`AdminStats`、`ReportData`、`StudentProgress`、`ReviewQueueItem`） | 仅在前端类型中定义 | **前端为准**——这些形状以类型模块为权威（§10.1） | 类型模块 |
| 12 | 提案 `timeline` 内嵌在资源中 | 内嵌 | 两者并存：详情响应可内嵌时间线，列表响应不得内嵌，完整历史仍由 `/timeline` 提供（§4.3） | 合约（已完成） |
| 13 | 报表 | 单个 `reports.data()` 聚合块 | 由 Client 组合四个报表端点；新增 `/reports/top-projects`（§4.13） | 合约（已完成）与前端 |
| 14 | 语言偏好 | 仅存 `localStorage` | 服务端持久化 `preferredLanguage`，本地值作为缓存（§2.10） | 前端与后端 |
