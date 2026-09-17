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
| `POST` | `/auth/activate` | 兑换首次激活码并设置账号的首个密码 |
| `GET` | `/me` | 当前用户、角色、权限、数据范围与功能开关 |
| `PATCH` | `/me/profile` | 修改允许自助维护的资料 |
| `POST` | `/me/change-password` | 修改密码 |

**不存在 `/auth/forgot-password` 与 `/auth/reset-password`，系统亦不提供任何形式的自助密码找回**（见 `Design/*/08-mlps-level1-security-design-guide.md` §8.1／§8.2，issue 089）。这两个端点曾经存在，从未被任何客户端调用，现已连同其存储一并移除。忘记密码须线下处置：用户联系管理员，由管理员当面核实身份后经 `POST /users/{id}/reset-password`（见 §4.15）签发激活凭条。**请勿**在前端新增"忘记密码"流程——登录页"忘记密码？请联系管理员"的文案即是预期行为的全部。

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

`studentId`（2026-08-06 新增，见 `Docs/optimization/01-i3-partner-school-terminology.md`）对教职工账号（教师/管理员/系统管理员）为 `null`——它使前端无需额外请求即可在共同组织者搜索中排除"我自己"。

`roles`/`workspaces` 在此处仍为数组格式（不同于 `/users` 的 `roleCode`，见 §4.15），但始终只含一个元素——每个用户始终且仅持有一个角色（见 07-RoleFinetune.md §2.5），因此也只有一个工作区；不存在多工作区账号，也不再有工作区选择页面。

#### `POST /auth/activate` —— 首次激活

学生通过批量名单导入建账，账号**没有密码**，因此根本无法登录（见 `Docs/optimization/10-StudentIdentityAndOnboarding.md` §2.4）。学生纸条上印有一次性激活码；兑换该码会在同一个事务内设置学生自己选择的密码并激活账号。服务端始终不生成任何密码。

**出于必要而公开、免认证**——与 `/auth/login` 一起，是仅有的两个此类端点。与其他变更请求一样需要 CSRF Token。

请求：

```json
{
  "code": "HKTZ4-9QP2M",
  "newPassword": "the-student-chooses-this"
}
```

- `code` —— 共 10 位：9 位 Crockford base32 数据字符（`0123456789ABCDEFGHJKMNPQRSTVWXYZ`，不含 `I`/`L`/`O`/`U`）加 1 位校验字符；校验字符采用 Crockford 标准算法 `value mod 37`，取值于 37 位校验字母表（数据字母表加 `*~$=U`）。服务端会先归一化：忽略大小写，去除分隔符与空白，并将 `I`/`L` 折叠为 `1`、`O` 折叠为 `0`。纸条上的分组写法（`HKTZ4-9QP2M`）可直接提交。客户端应在本地校验该校验字符，使明显的输入错误无需往返即可发现。
- `newPassword` —— 8–128 个字符。

**不发送用户名，也不返回用户名。** 激活码本身就是查询键，因此该端点从不被告知、也从不确认某个码属于哪位学生。成功时返回 `{ "data": null }`，且**不建立会话**：学生随后用刚设置的密码走常规登录流程。

所有失败均为 `422 VALIDATION_FAILED`，通过 `code` 字段上的 `fieldErrors[0].code` 区分：

| `fieldErrors[0].code` | 含义 |
| --- | --- |
| `ACTIVATION_CODE_MALFORMED` | 长度不对、字符不在字母表内，或校验字符不匹配——即输入有误 |
| `ACTIVATION_CODE_INVALID` | 不存在该激活码。刻意笼统：猜测得到的就是这个响应 |
| `ACTIVATION_CODE_EXPIRED` | 该码存在，但有效期（默认 7 天，可配置）已过——学生需要重新补发纸条 |
| `ACTIVATION_CODE_ALREADY_USED` | 一次性使用，且已被兑换 |

后三者的区分是刻意的，其代价也是：`EXPIRED` 与 `ALREADY_USED` 确实会确认所提交的码存在。之所以接受，是因为处于这两种状态的码本就无法再兑换，攻击者由此得不到任何可利用的东西；而若把它们合并，持有过期纸条的学生就会一直重复输入一个正确的码。无论哪种情况都不会透露该码**属于哪个账号**，且四者共用同一 HTTP 状态码，仅凭状态码无法区分。

也可能返回 `429 RATE_LIMITED`：该端点同时设有按 IP 的尝试限制与**全局失败额度**——全系统每小时仅允许几百次失败猜测——这正是按 IP 限流无法覆盖的分布式场景的应对手段（见方案 §2.5）。额度耗尽期间该端点对所有人拒绝服务，包括正常学生；成功兑换永远不消耗该额度。

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
| **字典/基础数据** | 服务类别、组织类型、荣誉等级、规则名称 | 服务端本地化。响应同时携带稳定标识（`categoryId` / `code`）与所解析语言的 `name`。客户端的逻辑与样式一律以标识为准，不得依赖 `name` |
| **用户撰写内容** | 项目标题与描述、提案字段、审核意见、组织名称、复核陈述 | 按录入原样返回，录入什么语言就是什么语言。不做机器翻译，不按语言重复存储 |
| **服务端生成文案** | 通知 `title`/`body`、错误 `message` | 按请求语言渲染。通知另外携带 `templateCode` 与参数，使切换语言后历史通知能重新正确渲染，也允许客户端自行本地化 |

**年级与班级是上述字典行的刻意例外**（issue 010，见
`Docs/issues/010-grade-class-raw-storage-and-abbreviated-format.md`）：两者其实都不是
被翻译的内容。年级数字在每种语言下含义相同（只是格式不同——"G10" 对比"十年级"），而班级
名称（如 "Luna"）是专有名词，从不翻译。`GET /grades` 仍下发服务端本地化的 `name`（未变
——Project 的可报名年级展示、Reports/CSV 导出等确有跨模块依赖），但每个**与学生关联**的
年级/班级字段（`StudentListItemDto`/`StudentDetailDto`/`StudentDirectoryEntryDto`/
`ReviewQueueItemDto`）携带的是原始 `gradeLevel`（int，年级数字）与原始的、与语言无关的
`className`，而不再是预先本地化的 `gradeName` 字符串。`GET /classes` 的 `name` 同理为原
始值——`class` 没有其他消费方。本地化展示字符串由客户端自行拼装
（`formatGradeClass` / `formatClassName`，`frontend/src/app/lib/format.ts`）。

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
- `hostOrganizationId`, `hostOrganizationName`, `deliveryScope`
- `originProposalId`, `studentOrganizerIds`
- `startAt`, `endAt`, `location`
- `capacity`, `recognizedMinutes`
- `eligibleGrades`, `evidenceRequirements`
- `registrationOpenAt`, `registrationCloseAt`
- `status`, `version`, `createdAt`, `updatedAt`

**`hostOrganizationId`/`hostOrganizationName`/`deliveryScope`**（新增，见 `Docs/optimization/03-ServiceRecordFormOptimization.md` §4.2/§10）：每个项目恰有一个本校组织——全系统唯一一条单例（`isSchoolOrganization: true` 的组织记录，`GET /organizations` 永不返回它，读/改请见 §4.16 的专用端点），由服务端解析，客户端不可写。`organizationId`/`organizationName` 保持原义：项目的**合作**组织（如有）——绝不会是本校组织本身（`POST`/`PATCH /projects` 会拒绝与本校组织相同的 `organizationId`，返回 `400`，因为本校组织已经自动关联，无需再指定）。`deliveryScope` 取值 `onCampus`/`offCampus`/`hybrid`，`POST /projects` 时**必填**（绝不从组织类型推断），`PATCH` 时可选；早于此字段的历史项目一律回填为 `hybrid`（"未知，待人工复核"，不是对校内/校外的猜测）。

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
- `gradeId`, `gradeLevel`, `className`
- `title`, `objective`, `description`, `expectedOutcome`
- `categoryId`, `plannedStartAt`, `plannedEndAt`, `location`, `format`
- `targetParticipants`, `capacity`, `volunteerTasks`
- `proposedSupervisorId`, `organizationId`, `externalContact`
- `safetyPlan`, `resourceNeeds`, `recruitmentPlan`
- `proposedRecognizedMinutes`, `evidencePlan`
- `status`, `currentRevision`, `assignedReviewerId`
- `approvedProjectId`, `version`, `createdAt`, `submittedAt`, `updatedAt`

`coOrganizerNames`（2026-08-06 随下方写入字段一并新增，见 `Docs/optimization/01-i3-partner-school-terminology.md`）由服务端解析、只读，与 `coOrganizerStudentIds` 按位置一一对应——与 `proposedSupervisorId`/`organizationId` 已有的 `proposedSupervisorName`/`organizationName` 是同一种模式。

`gradeId`/`gradeLevel`/`className`（随 `Docs/operations/VersionManagement.md` 所在会话新增，使管理员/教师端列表页可按年级筛选与展示）是提案在创建时从发起人快照下来的年级/班级——从不实时重新查询发起人的*当前*年级，与 `ServiceRecord` 已有的 `gradeId`/`className` 冗余方式一致。`GET /event-proposals` 的 `filter[gradeId]` 按此快照值精确匹配（与 §4.2 中 `Project` 的多年级 `eligibleGradeIds` 不同，一个提案只对应一个年级）。

**创建/更新提案**（`POST /event-proposals`、`PATCH /event-proposals/{id}`）接受 `organizationId` **或** `newOrganizationName`，二者不可同时提供：

- `organizationId` 引用一个已存在的合作组织（来自 `GET /organizations`）——后端会校验其存在。
- `newOrganizationName` 是学生填写的、系统中尚不存在的合作组织名称。后端会在同一事务内创建该组织（类型默认为 `community`）并将生成的 id 关联到提案——前端不得为此路径直接调用 `POST /organizations`（该端点仍受 `organizations.manage` 权限控制，发起活动的学生从不持有该权限）。
- 二者同时提供将返回 `400`。二者均不提供则提案不关联合作组织，这仍是合法状态（合作组织本就是可选项，与此前一致）。

`coOrganizerStudentIds` 与 `proposedSupervisorId` 均在服务端校验为真实、有效的账号：每个共同组织者 id 必须是真实存在的学生（且不能是发起人本人），`proposedSupervisorId` 必须解析为持有 `teacher` 角色的用户——与 §4.3"批准时指定教师为项目负责人"一致。此前二者均未经校验即被接受；前端现在通过下方的目录搜索来解析，而非自由文本。

状态：

```text
draft → submitted → approved
                 ↘ changesRequested → submitted
                 ↘ rejected
draft/submitted → withdrawn
```

（`underReview` 已于 2026-09-16 移除，见 issue 080。下文的 `recommendApproval`
被移除后，它就无法再到达：唯一的入口是审批事务内部一次从未提交的中间跳转，因此
任何提案都不可能被看到或筛选为该状态。审核人从 `submitted` 一步作出决定。移除前
已先核对线上数据：没有任何提案、也没有任何审计记录曾处于该状态。服务记录与个案
流程各自的 `underReview`/`inReview` 是真实的驻留状态，保持不变。）

审批决定：

- `requestChanges`
- `approve`
- `reject`

（`recommendApproval`——一个"保持在 `underReview`、仅记录内部背书"的无实际状态变化的决定，用于两级审批——已于 2026-08-20 移除：批准本身已经是两步流程（提案批准，再由另一步单独发布项目），再加一层"先建议、再由他人批准"的中间层，使审批流程比这个两步拆分本身需要的更复杂。移除前从未有实际数据记录过这一决定。）

任何持有 `eventProposals.review` 权限的已登录用户（目前由 `teacher`/`admin`/`sysadmin` 持有）均可对提案 `approve`/`reject`/`requestChanges`——某个账号是否应持有 `eventProposals.review` 是角色配置问题（见 `Docs/optimization/07-RoleFinetune.md` §2.9），本合约不做进一步限制。批准操作在同一事务中生成正式项目草稿并回填 `approvedProjectId`，因此 `approved` 必须同时具有有效的关联项目。正式项目仍须由具有发布权限的教师或管理员发布——批准本身从不自动发布。

### 3.3 报名 `Application`

状态：

```text
pending → confirmed → completed
       ↘ waitlisted → confirmed
       ↘ rejected
confirmed → cancelled
```

### 3.4 服务记录 `ServiceRecord`

`sourceType` 标识服务的来源方式，创建时确定，更新时不可再变更。**新建记录只接受 `project`（学生对该项目持有 `confirmed` 报名）或 `self`（学生自发）**（见 `Docs/optimization/03-ServiceRecordFormOptimization.md` §3/§7.3，取代此前 `project`/`external`/`self` 三分）。`external` 仅作为**历史数据**保留——既有记录保留该来源、可读可审核，处于 `draft`/`returned` 的旧记录仍可用原有自由文本字段编辑并重新提交一次（来源本身不变），但 `POST /service-records` 会直接拒绝 `sourceType: "external"`。`sourceType` 决定创建和提交时哪些字段必填/由服务端派生，不改变状态机本身。

状态：

```text
draft → submitted → underReview → approved
                   ↘ returned → submitted
                   ↘ rejected → appealPending
approved → corrected
```

核心字段：

- `id`, `studentId`, `projectId`, `sourceType`, `recordNumber`
- `serviceDate`, `startAt`, `endAt`, `durationMinutes`
- `location`, `organizationId`, `organizationName`
- `hostOrganizationName`, `partnerOrganizationNames`
- `serviceCategoryId`, `description`
- `supervisorKind`, `supervisorSubjectId`, `supervisorName`, `supervisorContact`
- `evidenceFiles`, `includesTravelTime`, `travelTimeReason`
- `status`, `currentRevision`, `reviewSummary`
- `creditedUnits`, `accountTransactionId`
- `version`, `createdAt`, `submittedAt`, `updatedAt`

`includesTravelTime` 是披露标志（申报时长是否包含在途时间），创建与更新时可写，不是派生字段；`includesTravelTime` 为 true 时 `travelTimeReason` 必填（提交时校验，创建草稿时不强制，允许保存不完整草稿），且从不计入 `durationMinutes`。

**`sourceType: "project"`**（§10 D1/Q1）：客户端只需提供 `projectId`——`organizationId`/`organizationName`/`location`/`serviceCategoryId`/`supervisorKind`/`supervisorSubjectId`/`supervisorName`/`supervisorContact` 即使客户端传了也一律忽略，全部由服务端根据项目解析并在创建时不可变快照（之后修改项目本身不会追溯改变已创建的记录）。报名的 `confirmed` 状态在创建和提交时**都会**校验——草稿创建后取消报名会阻止提交。`hostOrganizationName`/`partnerOrganizationNames` 是快照时项目自身本校/合作组织的镜像。`supervisorKind` 恒为 `projectOwner`，解析自项目负责人账号（姓名+邮箱）；若该账号没有邮箱，提交会返回 `supervisorContact`/`CONTACT_MISSING` 字段错误，而不是静默省略联系方式。校内项目与校外合作项目一视同仁——项目负责人（恒为校内教师/管理员）始终是记录的主管，而不是合作组织自己的联系人。

**`sourceType: "self"`**：`organizationId`/`organizationName` 仍为自由文本（不校验对应真实组织），与旧 `external` 来源一致。`supervisorKind` 为 `internalUser`/`student`/`externalManual` 之一 + `supervisorSubjectId`（真实用户或学生 id，服务端解析出姓名+邮箱/联系方式——若该账号无联系方式则返回 `CONTACT_MISSING` 字段错误），或者对 `externalManual` 直接使用客户端提供的 `supervisorName`/`supervisorContact`，二者均为必填（没有账号可回退）。完全不提供 `supervisorKind` 在提交时会报 `REQUIRED` 字段错误。

**`recordNumber`**（§5.3/§10 D3）：创建时由服务端生成，唯一、不可变，格式为 `TB-{学年代码}-{年级}-{学号}-{序号}`，其中 `序号` 是按（学年、学生）维度原子递增的计数器——绝非"查最大值加一"。早于此字段的历史记录为 `null`。

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
draft → submitted → resolved
                 ↘ needsInformation → submitted
                 ↘ rejected
submitted → draft
```

（`inReview` 已于 2026-09-16 移除，见 issue 082——与 issue 080 在提案流程中
发现的问题完全相同：唯一入口是审批事务内部一次从未提交的中间跳转，而且个案
流程连"接手"接口都没有。审核人从 `submitted` 一步作出决定。移除前已先核对线上
数据：没有任何申诉、也没有任何审计记录曾处于该状态。`assignedReviewerId`
保留——它只是闲置而非误导，且是将来实现"接手"功能时自然会用到的字段。）

`submitted → draft` 是学生撤回自己的申诉（issue 081），仅本人可执行，并且
刻意不从 `inReview` 出发——"仅在审核人接手之前可撤回"由状态机本身保证，
而非另设前置校验，与服务记录的做法一致。

`CaseDto` 还携带 `gradeId`/`gradeLevel`/`className`——案件在创建时从提交学生快照下来的年级/班级（从不实时重新查询），与 `EventProposal`（§3.2）、`ServiceRecord` 已有的方式一致。`GET /cases` 的 `filter[gradeId]`（§4.8）按此快照值匹配。

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

`deliveryScope`（`onCampus`/`offCampus`/`hybrid`）在 `POST /projects` 时必填，`PATCH` 时可选——见 §3.1。

项目状态变化使用动作端点，避免用通用 `PATCH status` 绕过业务校验。

**状态机（2026-08-08 修订，见 `Docs/optimization/02-PorjectOrganization.md` §2）：** `draft`（草稿）→ `published`（已发布）→ `registrationOpen`（报名开放）→ `closed`（报名关闭）→ `inProgress`（进行中）→ `completed`（已结束）→ `archived`（已归档），其中 `registrationOpen`→`closed`→`inProgress`→`completed` 均由系统按时间自动流转（在读取/操作访问时惰性同步），`closed` 仅表示"报名窗口已结束"，从来不是人工操作，也不代表取消。`POST /projects/{id}/archive` 只能在 `completed` 状态下成功，且必须所有 `CONFIRMED`（已确认）报名学生该项目的服务记录都已进入终态审核结果（`approved`/`rejected`/`corrected`）才能通过；否则返回 409 状态冲突错误，并说明还有多少名学生未完成审核。`POST /projects/{id}/cancel`（`reason` 必填，`@NotBlank`，作为审计记录的 `reasonCode` 存储）是独立的管理员覆盖操作——可从 `draft`/`published`/`registrationOpen`/`closed`/`inProgress`/`completed` 中的任一状态直接归档，完全绕过已审核记录的门槛检查，用于丢弃一个不需要的草稿、彻底放弃某个进行中的项目，或强制关闭一个停滞的项目。此前存在的手动 `POST /projects/{id}/close`（`projects.close`）提前关闭动作现已废弃——既然 `closed` 已完全自动化，`cancel` 承接了它原本的使用场景。`CreateProjectRequest`/`UpdateProjectRequest` 也新增了可选的 `ownerId` 字段：仅管理员可用（按角色校验，而非仅凭 `projects.create`/`.update` 权限，因为教师也持有这两项权限），非管理员提交的 `ownerId` 会被静默忽略并强制设为本人，而不是报错拒绝。

**无人报名自动归档（2026-08-08 新增）：** `registrationOpen`→`closed` 的按时间自动流转在执行前会先检查该项目已确认的报名人数——若确认报名人数为零，项目会直接归档（新增 `registrationOpen`→`archived`，同样由系统触发），而不再进入 `closed`/`inProgress`/`completed`，因为这些状态对一个零报名的项目已没有实际意义。系统会写入一条专门的审计记录（`action: "project.autoArchiveNoRegistrants"`，`reasonCode: "NO_REGISTRANTS_AT_REGISTRATION_CLOSE"`，`actorId`/`permissionUsed` 均为空——因为没有人工操作者）说明原因，与其他状态变更的审计记录区分开来。只要该项目有至少一名已确认的报名学生，就不受影响，仍按原有流程进入 `closed`。

管理后台的"报名名单"入口（`GET /applications?filter[projectId]=`，见 §4.4）只有在项目发布之后才有意义——前端现已对 `draft`（草稿）状态的项目隐藏该操作菜单项，因为草稿阶段报名从未开放过。

`GET /projects` 的 `filter[gradeId]` 只要该年级出现在项目的 `eligibleGradeIds`（见 §3.1）**任意位置**即匹配——是"包含"而非"等于"，因为一个项目可以同时适用于多个年级（与 `EventProposal`/`Case` 单一年级的 `filter[gradeId]` 不同，见 §3.2/§3.6）。该过滤基于对 `eligible_grade_ids`（一个 MySQL JSON 列，而非规范化的关联表）的原生 `JSON_CONTAINS` 查询实现，而非 JPA Criteria 谓词——因为 Hibernate 中 `@Convert` 映射的集合列无法在 Specification 里表达"数组包含"语义。

### 4.3 学生活动提案

| Method | Path | 权限 | 说明 |
| --- | --- | --- | --- |
| `GET` | `/event-proposals` | 已登录 | 学生只能看到本人提案（`Self` 范围）；`admin`/`sysadmin` 能看到完整队列（`All` 范围）；其余每个非学生角色（`teacher`，以及未来任何按年级限定的自定义角色）只能看到其被分配年级的提案（`Grades` 范围）——查看权限按角色区分，不再由 `eventProposals.read` 把关（2026-08-20，见 `Docs/optimization/07-RoleFinetune.md` §2.9/§2.10） |
| `POST` | `/event-proposals` | `eventProposals.create` | 学生创建提案草稿 |
| `GET` | `/event-proposals/{id}` | 已登录 | 提案详情——查看权限规则同上 |
| `PATCH` | `/event-proposals/{id}` | 提案人且可编辑 | 编辑草稿或需修改的提案 |
| `DELETE` | `/event-proposals/{id}` | 提案人且为草稿 | 删除草稿 |
| `POST` | `/event-proposals/{id}/submit` | 提案人 | 提交审批 |
| `POST` | `/event-proposals/{id}/withdraw` | 提案人/管理权限 | 撤回或申请撤回 |
| `GET` | `/event-proposals/{id}/revisions` | 已登录 | 修订历史——查看权限规则同上 |
| `GET` | `/event-proposals/{id}/timeline` | 已登录 | 审批时间线——查看权限规则同上 |
| `POST` | `/event-proposals/{id}/reviews` | `eventProposals.review` | 要求修改、批准或驳回 |
| `GET` | `/event-proposals/{id}/reviews` | 已登录 | 提案的审批决定历史——查看权限规则同上 |
| `POST` | `/event-proposals/{id}/assign` | `eventProposals.assign` | 分配或转交评审人 |
| `GET` | `/event-proposal-queue/summary` | `eventProposals.review` | 待办数量与审批 SLA |

**查看与决定的区分（2026-08-20）：** 以上每个读取端点都改为仅要求 `isAuthenticated()`，不再要求 `eventProposals.read`——任何已登录用户都能查看其数据范围所覆盖的提案并打开提案详情（前端只是不渲染决定按钮而已），由 `DataScopeResolver` 将学生限定在本人提案、将 `admin`/`sysadmin` 扩展到完整队列、将其余每个非学生角色限定在其被分配的年级（`Docs/optimization/07-RoleFinetune.md` §2.10 的 `hasSchoolWideVisibility()` 规则，修订了 §2.1 最初"任何非学生角色都能看到一切"的决定）。只有*决定*（`POST .../reviews`）与*分配*（`POST .../assign`、`GET /event-proposal-queue/summary`）仍按权限把关，分别是 `eventProposals.review`/`.assign`——真正决定一个账号能否看到决定按钮的是这个区分，而不是 `eventProposals.read`。`eventProposals.read` 仍保留在权限目录中（`07-RoleFinetune.md` §6，为保持接口文档完整性），但已不再把关本节的任何端点。

**批准通知（2026-08-20，见 `Docs/optimization/07-RoleFinetune.md` §2.10.2）：** `reject`/`requestChanges` 仍只通知提案人，不变。`approve` 会**同时**通知提案人（`eventProposal.approved`，不变）**和**每一位覆盖该提案年级的、持有 `projects.publish` 权限的在职用户（`eventProposal.approvedNeedsPublish`，关联资源为 `project`/新生成项目的 id）——因为批准只会生成一个*草稿*项目（见上文，从不自动发布），需要有具体的人知道有一个项目待发布。"覆盖该提案年级"复用与该账号自身提案队列可见性完全相同的年级范围分配；`admin`/`sysadmin` 始终视为覆盖所有年级。

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
| `GET` | `/service-records` | `serviceRecords.read` | 查询本人或权限范围记录；支持 `filter[status]`/`filter[sourceType]`/`filter[serviceCategoryId]`/`filter[studentId]`/`search`（`filter[studentId]` 2026-08-12 新增，服务于管理端"查看该学生记录"页面——见 §4.9 表格后的说明） |
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
| `POST` | `/cases/{id}/withdraw` | 本人且尚未开始审核 | 撤回（issue 081） |
| `DELETE` | `/cases/{id}` | 本人且仅草稿 | 删除草稿（issue 081） |
| `POST` | `/cases/{id}/decisions` | `cases.decide` | 管理员处理 |
| `GET` | `/cases/{id}/timeline` | `cases.read` | 时间线 |
| `GET` | `/cases/{id}/revisions` | `cases.read` | 修订历史（2026-08-12 补录此表——功能早已实现，只是此前漏收录） |
| `GET` | `/cases/{id}/decisions` | `cases.read` | 处理历史 |

`type` 取值为 `appeal`（复核）\| `supplement`（补录）\| `manualCorrection`（人工调整）。`subjectType`/`subjectId` 在接口层是可选、自由文本的，但目前只有 `subjectType: "serviceRecord"` 才会解析出可读的 `subjectLabel`（`CaseServiceImpl.subjectLabel()`）——其它取值，或完全不带 subject（不关联具体记录的一般性申请），同样合法，只是没有 `subjectLabel`。`manualCorrection` 是唯一一种"通过"决定会直接调整学生时间币账户的类型，通过 `requestedAmountCentiCoins`（见 §3.6）——`cases.create` 权限本身对 `role_student` 和教职工角色都有授予，但学生端 UI 只提供 `appeal`/`supplement` 两种入口；`manualCorrection` 按约定仅由教职工发起，不是权限层面的限制（详见 `06-StudentCaseFiling.md` §2.1）。

`GET /cases` 同样支持 `filter[gradeId]`，按案件自身快照下来的年级精确匹配（见 §3.6）。

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
| ~~`GET` `/students/{id}/service-records`~~ | — | 从未实现——这一行原本描述了预期形态，但服务端实际没有这个路由。解决方式与 `api.accounts.get(studentId)`（§13.1 后的说明）相同：前端改为调用 `GET /service-records?filter[studentId]=`，复用其它管理端服务记录页面已经在用的列表接口，而不是再维护一个带自己一套筛选/排序/分页逻辑的子资源接口。 |
| `GET` | `/students/directory` | `students.read`/`directory.read` | 按姓名搜索，返回精简的 `{id, displayName, gradeLevel, className}`，用于挑选共同组织者（2026-08-06 新增，见 `Docs/optimization/01-i3-partner-school-terminology.md`；`gradeName` 已在 issue 010 中改为原始的 `gradeLevel`） |
| `GET` | `/academic-years` | 已登录 | 学年 |
| `GET` | `/grades` | 已登录 | 年级 |
| `GET` | `/classes` | 已登录 | 班级 |

**学生记录不再包含监护人与学籍邮箱字段。** `guardianName`、`guardianContact` 与学生自身的 `email` 已在 `V26` 中按数据最小化原则删除（`Docs/optimization/08-SecurityPrivacyHardening.md` §3.1）：没有任何界面展示它们，因此只是徒增泄露面而无功能收益。它们不再出现在 `POST`/`PATCH /students` 请求、`GET /students/{id}` 响应，以及 `students` 导入模板中——模板现为七列（`studentNumber,displayName,gradeId,classId,enrollmentAcademicYearId,graduationYear,joinedAt`）。`user.email` 不受影响：那是登录账号上的另一个字段，也是登录页接受的标识符。

一个需要知晓的连带影响：`supervisorKind: "student"` 的同伴主管，其联系方式现在通过该学生**关联的登录账号**解析，而非学籍邮箱；因此没有账号的纯名册学生仍会以 `CONTACT_MISSING` 失败，与此前行为一致。

`directory.read` 刻意比 `students.read` 更窄：这是 `role_student` 持有的权限（学生原本没有任何方式查到其他学生或教师的 id），其响应从不携带 `/students` 列表响应中的 `studentNumber`/`status`/`graduationYear` 或联系方式字段。`search` 为必填参数，结果数量有上限（20 条）——这是一个联想搜索，不是目录浏览。

### 4.10 组织与服务类别

| Method | Path | 权限 | 说明 |
| --- | --- | --- | --- |
| `GET` | `/organizations` | `organizations.read` | 组织列表——支持 `filter[type]`、`filter[status]`、`search`（仅按名称）（2026-08-08 新增，见 `02-PorjectOrganization.md` §4；此前这些参数虽然接受但会被静默忽略）。永不包含本校组织（`isSchoolOrganization: true`）——该条是学校自身的档案记录，不是合作方；请改用 §4.16 的 `GET`/`PATCH /school/info` 读取或编辑 |
| `POST` | `/organizations` | `organizations.manage` | 创建 |
| `GET` | `/organizations/{id}` | `organizations.read` | 详情 |
| `PATCH` | `/organizations/{id}` | `organizations.manage` | 更新 |
| `POST` | `/organizations/{id}/archive` | `organizations.manage` | 归档——唯一的移除方式；按设计不提供永久删除（见 `02-PorjectOrganization.md` §4） |
| `GET` | `/service-categories` | 已登录 | 服务类别。默认仅返回启用项；`?includeInactive=true` 同时返回已停用项，供管理页使用 |
| `POST` | `/service-categories` | `rules.manage` | 创建类别 |
| `PATCH` | `/service-categories/{id}` | `rules.manage` | 更新类别（中英文名称、`active`） |
| `DELETE` | `/service-categories/{id}` | `rules.manage` | 删除类别；若仍被记录/项目/提案引用则返回 `409` |

`organizations.manage` 现由 `role_admin` 和 `role_teacher` 共同持有（2026-08-08 变更——此前仅管理员持有）。同一变更中，`Organization` 新增了 `description`（自由文本简介）字段，与既有的结构化字段 `type`/`contactName`/`contactEmail`/`contactPhone`/`address` 并列；`CreateOrganizationRequest`/`UpdateOrganizationRequest`/`OrganizationDto` 均已携带该字段。

`OrganizationDto` 还携带只读的 `isSchoolOrganization` 布尔字段（见 `Docs/optimization/03-ServiceRecordFormOptimization.md` §4.1）——全系统恰有一条记录为 `true`，仅通过一次性种子创建，永远不经由 `POST /organizations`。`PATCH /organizations/{id}` 与 `POST /organizations/{id}/archive` 对该行均返回 `409`——其名称/地址/联系方式只能通过 `PATCH /school/info`（§4.16，仅 `sysadmin`）修改，不能走普通的组织管理流程，也永远不可被归档。每个项目的本校组织（见 §3.1）都解析自这一条记录。

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

**五个报表端点的学年范围。** 每个端点都接受可选的 `?academicYearId=`（取 `GET /academic-years` 返回的 `id`，不是 `code`）。不传或传空表示当前标记为 current 的学年——绝不表示“全部学年”。若 `academicYearId` 不匹配任何学年，返回 `RESOURCE_NOT_FOUND`（404），而不是悄悄回退到当前学年。每个报表都在响应体中以 `academicYearId` 回显实际生效的学年，客户端据此确认拿到的范围就是请求的范围。

“按学年限定”对五个端点含义并不相同，因为数据模型并未给学生或服务记录打上学年标记：

| 端点 | 限定依据 |
| --- | --- |
| `/reports/participation` | 服务**日期**落在该学年内的记录。统计人群：当前在籍、且在该学年结束前已入学的学生。 |
| `/reports/completion` | 同参与率，另加该学年**当时生效**的年度必修分钟数——不是今天的规则。 |
| `/reports/service-hours` | 服务**日期**落在该学年内的已通过记录，按月分桶。 |
| `/reports/review-efficiency` | 在该学年内**作出**的审核决定（以审核人决定的时间计，不以服务发生时间计）。 |
| `/reports/top-projects` | **排期**落在该学年内的项目（`startAt`），时长取服务日期落在该学年内的已通过记录。 |

有两条限制属于固有约束，不必当作缺陷上报：已离校的学生不会出现在过去学年的统计人群中，因为学生状态是“当下”的事实，系统不保留历史花名册；`POST /exports` 的导出任务本身不带周期，因此报表导出始终是当前学年的。

仪表盘端点不接受 `academicYearId`：它们描述学校的当下状态，其中带周期的指标已在字段名中写明自己的周期（`approvedThisMonth`、`totalServiceHoursThisYear`）。

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
| `POST` | `/users/{id}/reset-password` | `users.manage` + `admin`/`sysadmin` 角色 | 管理员触发重置——清除该账号的密码，并以 PDF 返回一张可打印的激活凭条（见 `Docs/optimization/10-StudentIdentityAndOnboarding.md` §3.7）；**不返回任何密码，因为根本不会生成密码** |
| `POST` | `/users/activation-slips` | `users.manage` + `admin`/`sysadmin` 角色 | 生成整个班级的可打印激活凭条，合为一份 PDF（见 `Docs/optimization/10-StudentIdentityAndOnboarding.md` §3.4） |
| `GET` | `/roles` | `roles.read` | 角色列表 |
| `POST` | `/roles` | `roles.manage` | 创建角色 |
| `PATCH` | `/roles/{id}` | `roles.manage` | 更新角色名称/会话超时/权限 |
| `DELETE` | `/roles/{id}` | `roles.manage` | 删除角色（见 07-RoleFinetune.md §2.2/§2.4） |
| `GET` | `/permissions` | `roles.read` | 权限码目录 |
| `GET` | `/users/directory` | `users.read`/`directory.read` | 按姓名与角色搜索，返回精简的 `{id, displayName}`，用于挑选拟邀请指导教师（2026-08-06 新增，见 `Docs/optimization/01-i3-partner-school-terminology.md`）——必须提供 `role` 查询参数（如 `teacher`），不暴露 username/email/studentId/roleCode/status 等其他用户字段 |
| `GET` | `/audit-logs` | `auditLogs.read` | 操作审计 |
| `GET` | `/login-logs` | `securityLogs.read` | 登录日志 |
| `GET` | `/system/settings` | `settings.read` | 系统参数 |
| `PATCH` | `/system/settings` | `settings.manage` | 更新允许修改的参数 |

**角色是可配置的，而非固定词表**（见 07-RoleFinetune.md）——`student`、`admin`、
`sysadmin` 为受保护角色（不可删除；`sysadmin` 同时不在管理端界面中展示，但其本身
仍正常存在并生效），`teacher` 及任何学校自定义角色均为普通、可删除的行。若角色为
受保护角色，或仍被任一用户持有，`DELETE /roles/{id}` 返回 `409`。`POST /roles` /
`PATCH /roles/{id}` 若 `permissionCodes` 中包含 `eventProposals.create`、
`applications.create` 或 `serviceRecords.create`，返回 `422`——这三项权限仅保留给
`student` 角色，因为可管理角色始终是教职工类型，而非自助提交者。

**每个用户始终且仅持有一个角色**（见 07-RoleFinetune.md §2.5）——`POST /users` /
`PATCH /users/{id}` / `GET /users` 列表响应携带单一的 `roleCode: string` 字段，
而非集合。（相对地，`GET /me` 的 `roles`/`workspaces` 数组保持不变——实际上如今始终
只有一个元素，但线上格式仍是数组，见 §2.2 登录响应示例。）

`POST /users`、`PATCH /users/{id}` 以及 `GET /users` 列表响应均携带
`scopeGradeIds: string[]`（2026-08-10 新增，issue 002）——`teacher` 角色账号所绑定的年级
id（对应 `teacher_scope_assignment` 记录，覆盖 §11 验收场景 8 已启用数据范围的每个资源类型：
`serviceRecords`、`eventProposals`、`students`、`accounts`、`cases`，每个年级每种资源类型各一条）。
仅当 `roleCode` 为 `teacher` 时才有意义：创建时对其他角色一律忽略，且若 `PATCH` 将 `roleCode`
改为非 `teacher`，该绑定会自动清空。`admin`/`sysadmin` 角色不需要此字段——其全量数据访问是权限层面
的绕过（`hasFullDataScope()`，授予所有非 `student` 角色），而非数据范围记录，因此非 `teacher`
账号的 `scopeGradeIds` 始终为空，界面上也不展示年级选择器。

`POST /users`、`PATCH /users/{id}` 还携带 `studentId: string | null` 与
`staffId: string | null`（2026-08-10 新增，issue 003），并在 `GET /users` 及
`PATCH` 响应中一并返回：

- `studentId`——仅当 `roleCode` 为 `student` 时使用，该角色下为必填。必须是一个真实存在、
  尚未被其他账号关联的 `Student` 记录 id（见 §4.9）——创建学生角色账号**不支持**直接填写新的
  年级/班级/学号；前端通过 `GET /students/directory` 搜索并提交其 id，与提案共同组织者/指导教师
  所用的选择器模式一致。关联一个已被其他账号占用的学生，或未知 id，将分别返回 `409`/`400`。
- `staffId`——每个非 `student` 角色均为必填（原为"仅 teacher 或 admin"，见
  07-RoleFinetune.md——现已扩展为任何教职工类角色，含自定义角色，均需要该字段）。为自由文本、
  由学校分配的标识符（不同于 `studentId`，没有可关联的"教职工名册"模块）——必须在所有账号间唯一，
  重复将返回 `409`。
- 二者遵循与其他 `PATCH` 字段相同的部分更新约定：省略/`null` 表示"不修改"，提供值表示"整体替换"。
- 这些必填规则在请求校验阶段强制执行，而非 bean 级 `@NotBlank`，因为每条规则都依赖于 `roleCode`。

**`POST /users/activation-slips`——激活凭条文档**
（2026-09-17 新增，见 `Docs/optimization/10-StudentIdentityAndOnboarding.md` §3.4）。
这是学生拿到首个凭据的唯一途径：为一个班级中所有仍处于待激活状态的成员各生成一个
一次性激活码，并把可打印的凭条合为**一份 PDF** 返回。

```json
{ "classId": "cls_...", "slipsPerPage": 1 }
```

- **响应体就是 PDF 本身**，不套用 §2.5 的 `data`/`meta` 信封——
  `200 application/pdf`，带 `Content-Disposition: attachment` 与
  `Cache-Control: no-store, private, max-age=0`。这与 `GET /files/{id}/content`
  已有的例外一致。若失败发生在文档生成**之前**，仍返回常规错误信封，客户端通过
  content type 区分两者。文档为流式返回且**从不落盘**——刻意不走 `/files` 管线，
  该管线用于对*证据*做净化与水印处理，会让凭据在短时效 URL 背后获得第二次生命。
- **使用 `POST` 而非 `GET`，尽管它下载的是文件。** 该调用会签发新的激活码，并作废
  这些学生此前持有的激活码；若用 `GET`，预取、代理重试或浏览器后退都会静默作废
  已经发到学生手里的凭条。
- **`slipsPerPage` 是只含 `1` 与 `2` 的封闭枚举，默认为 `1`**（§2.8，D-8）——
  而非带范围校验的整数。其他取值会在反序列化阶段失败，在签发任何激活码之前返回
  `400 INVALID_REQUEST`。
- **仅限管理员**（§2.10，D-9/D-10）：需要 `users.manage` **且**持有 `admin` 或
  `sysadmin` 角色码。这是一次普通的角色检查，而非数据范围解析器——教师完全没有凭条
  签发路径，连"仅限本班"的受限路径也没有，因此不存在可能出错的"本班"分支。其他任何人
  一律 `403`。之所以比单纯的 `users.manage` 更窄，理由与 `PATCH /school/info` 比
  `settings.manage` 更窄相同（见 §4.16）。
- **已激活的学生会被跳过**（§2.7）：只为状态为 `PENDING_ACTIVATION` 或
  `ACTIVATION_EXPIRED` 的账号签发，因此丢了五张凭条后的重新生成不会重置另外
  二十五名本来一切正常的学生。若全班都已激活，返回 `409` 而非一份空 PDF；未知
  `classId` 返回 `404`；名册中尚无账号的学生会被跳过，而不会导致整批失败。
- **每张凭条都载有**学生姓名、用户名、二维码、按打印分组显示的激活码
  （`HKTZ4-9QP2M`），以及一行出处信息——*由谁签发、何时签发、有效期截止*——
  且**每张凭条各印一次，而不是每页一次**，因为整页纸是要被剪开的。
- **二维码编码的是同一个激活码**，包装为 `<站点根地址>/activate?code=...`
  （§2.5 所说的"一个秘密，两种投递形式"），与 §2.2 所述 `/activate` 路由一致。
  站点的**根地址**同时以文本形式印在二维码旁（§2.9）：它是一个反钓鱼锚点，给孩子
  一个可以与地址栏核对的东西，仅此一点就足以支撑印出它，与是否有人手动输入无关。
  深链接绝不以文本形式印出。
- **激活码明文只出现在 PDF 里，别无他处**——不进审计记录、不进日志、不进错误信息。
  审计记录（`schoolClass` 上的 `user.issueCredentialSlips`）记载由谁为哪个班级签发、
  签发了多少张，以及有多少学生被跳过。
- 服务端需设置 `TIMEBANK_SITE_ROOT_URL`（`timebank.auth.activation.site-root-url`）；
  未设置时启动即失败，因为取值错误会把学校并不拥有的域名印到发给学生的纸上。

**`POST /users/{id}/reset-password`——管理员重置签发的是激活凭条，而不是密码**
（2026-09-17 变更，见 `Docs/optimization/10-StudentIdentityAndOnboarding.md` §3.7）。
该端点过去会在服务端生成一个临时密码，并以 `{ username, temporaryPassword }`
返回明文。现在不再如此，也不再有任何 JSON 响应。

- **无请求体，响应体就是 PDF**，与上面的 `POST /users/activation-slips` 完全一致：
  相同的 `Content-Disposition`、相同的 `Cache-Control: no-store`，以及相同的
  "失败仍返回常规错误信封，客户端通过 content type 区分"规则。它是同一格式、
  同一语言解析规则下的单张凭条文档；文件名不指向任何个人
  （`activation-slip-<yyyymmdd>.pdf`）。
- **账号会发生什么**：已存储的密码被**清除**，账号回到 `PENDING_ACTIVATION`，
  同时签发一个新的一次性激活码——三者在同一个事务内完成，因此渲染失败会整体回滚，
  而不会让账号落入"既没有密码、又没有印出凭条"的状态。在该用户兑换激活码并自行
  设置密码之前，其完全无法登录；若凭条一直未被使用，过期扫描会把账号置为
  `ACTIVATION_EXPIRED`，再次重置即可重新签发。
- **该账号的所有在线会话一律作废**，与此前一致。清除密码本身并不会结束会话——
  会话中携带的是已经通过认证的主体。
- **仅限管理员**（§2.10，D-9/D-10）：从原先的仅需 `users.manage` 收窄，与凭条批量
  签发保持一致——这个调用会铸造凭据。
- **以下情况返回 `409`**：管理员重置自己的账号（应改用
  `POST /me/change-password`，它同样会作废所有会话；或请另一位管理员代为重置），
  以及账号处于 `INACTIVE`（应先恢复启用；否则重置会静默撤销停用决定）。未知用户 id
  返回 `404`。`LOCKED` 账号可以正常重置，并在重置后解除锁定——锁定本就是申请重置
  最常见的原因。
- 该账号此前持有的任何激活码都会被本次重置作废，因此之后有且仅有一个可用激活码
  （§2.7）。
- **激活码明文只出现在 PDF 里，别无他处**——不在响应头里、不进审计记录
  （`user` 上的 `user.adminResetCredential`，其后是 `user.issueActivationCode`）、
  不进日志。

### 4.16 学校信息

| Method | Path | 权限 | 说明 |
| --- | --- | --- | --- |
| `GET` | `/school/info` | 已登录 | 学校基本信息（名称、全称、学生发展中心、联系方式等） |
| `PATCH` | `/school/info` | 仅 `sysadmin` 角色 | 编辑学校信息——按角色代码判断，而非按权限：`admin` 与 `sysadmin` 在角色合并后持有完全相同的权限集合（见 §4.15 角色表），此端点必须比 `admin` 更窄 |

响应示例：

```json
{
  "data": {
    "name": "示例中学",
    "fullName": "示例中学（法定全称）",
    "studentDevCenter": "学生发展中心",
    "contact": "dev-center@example-township-school.edu",
    "academicYear": "2025-2026"
  }
}
```

`PATCH` 接受部分字段（`name`/`fullName`/`studentDevCenter`/`contact`，均可选——只更新提供的字段）；不带 `version`/乐观锁字段，因为这是一条极少被修改、只有一类编辑者的单例记录。非 `sysadmin`（包括 `admin`）调用会返回 `403`。

该端点返回低频变化的学校级配置信息，前端可在应用初始化时获取并缓存。与 `/system/settings` 的区别在于：`/school/info` 面向所有已登录用户，返回学校展示层信息；`/system/settings` 面向管理员，返回可配置的系统参数。

### 4.17 系统版本

| Method | Path | 权限 | 说明 |
| --- | --- | --- | --- |
| `GET` | `/system/version` | 已登录 | 已部署的产品版本号 |

响应示例：

```json
{
  "data": {
    "version": "0.1.1"
  }
}
```

`version` 是一个语义化版本号字符串（`MAJOR.MINOR.PATCH`，可带 `-SNAPSHOT` 后缀），来源于后端构建本身（Maven 的 `spring-boot-maven-plugin` `build-info` 目标，在构建时将 `pom.xml` 的 `<version>` 写入 jar 包），从不在应用代码中手动维护。完整的版本/发布管理方案见 `Docs/operations/VersionManagement.md`，其中也说明了前端自己的 `package.json` 版本号如何与后端保持同步——尽管该端点（而非前端打包时的版本号）才是界面上任何版本展示的运行时真相来源。展示时前缀 `v`（如 `v0.1.1`）——接口本身返回不带前缀的原始版本号。

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
| 生成激活凭条 | 否 | 否 | 是 | 是 |
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
| `school.updateInfo` | `PATCH /school/info` | 是 |
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
| `reports.data(academicYearId?)` | 组合 `GET /reports/participation`、`/completion`、`/service-hours`、`/top-projects`，并把同一个可选的 `academicYearId` 传给每一个 | 是 |
| `users.list` | `GET /users` | 否 |

`api.accounts.get(studentId)` 传入的是学生 ID，而账户端点以账户 ID 为键。该差异在 Client 内解决——先按 `studentId` 过滤，再用返回的 `accountId`——而不是新增以学生为键的账户端点。

`serviceRecords.list` 在管理端"查看该学生记录"场景（学生管理页的"记录"按钮）下传入 `filter: { studentId }`，调用的是其它服务记录页面已经在用的同一个方法/接口——与上面 `accounts.get` 的解决方式相同，不是新增第二个 Client 方法。

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
| 学生列表假数据带 `guardianName` / `guardianContact` | 已失效：字段已在 `V26` 中整体删除，不再存在（§4.9） |
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
| 1 | 提案状态 | `needsRevision`，且没有 `underReview` | `changesRequested` 以合约为准。前端*没有* `underReview` 反而歪打正着——该状态已于 2026-09-16 从合约中整体移除（§3.2，issue 080） | 前端 |
| 2 | 记录状态 | 没有 `appealPending` | 合约为准；接入复核时补齐（§3.4） | 前端 |
| 3 | 时间币金额 | `balance: 6` 表示 6 时间币 | 合约为准：传输层为整数分币，展示时格式化（§2.1） | 前端格式化 |
| 4 | 时长 | `completedHours` 等与分钟字段重复 | 合约为准：传输层只有分钟，小时在界面换算（§2.1） | 前端 |
| 5 | 类别名称 | 存放 i18n key | 合约为准：由服务端下发本地化 `name`，以 `categoryId` 为键（§2.10） | 前端与假数据 |
| 6 | 学生列表中的监护人字段 | 存在 | 已失效：`V26` 整体删除该列，而非仅隐藏（§4.9） | 已解决 |
| 7 | 证明材料地址 | 永久直链 | 合约为准：短时且校验权限（§4.6） | 前端上传接入 |
| 8 | 列表信封与分页 | 裸数组、客户端筛选 | 合约为准（§2.5、§2.7） | 前端 Client 层 |
| 9 | ID 旁的展示名称（`projectTitle`、`studentName` 等） | 全面存在 | **前端为准**——已固化为 §2.11 | 合约（已完成） |
| 10 | `enrolledCount`、`myApplicationStatus`、`daysWaiting`、`hasAnomaly`、`priority` | 存在 | **前端为准**——按 §2.11 由服务端计算。第二阶段的异常标记基于规则，不来自 AI | 合约（已完成） |
| 11 | 聚合载荷（`AdminStats`、`ReportData`、`StudentProgress`、`ReviewQueueItem`） | 仅在前端类型中定义 | **前端为准**——这些形状以类型模块为权威（§10.1） | 类型模块 |
| 12 | 提案 `timeline` 内嵌在资源中 | 内嵌 | 两者并存：详情响应可内嵌时间线，列表响应不得内嵌，完整历史仍由 `/timeline` 提供（§4.3） | 合约（已完成） |
| 13 | 报表 | 单个 `reports.data()` 聚合块 | 由 Client 组合四个报表端点；新增 `/reports/top-projects`（§4.13） | 合约（已完成）与前端 |
| 14 | 语言偏好 | 仅存 `localStorage` | 服务端持久化 `preferredLanguage`，本地值作为缓存（§2.10） | 前端与后端 |
