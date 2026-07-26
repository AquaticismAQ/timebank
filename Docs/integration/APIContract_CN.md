# 时间银行 · RESTful API 合约（API Contract）

> 本文档定义前端驱动实施所需的顶层 API 合约。它描述资源边界、端点、状态、权限和通用协议，不替代后端数据库设计或 OpenAPI 逐字段规范。第二阶段接口优先实现；第三、第四阶段接口作为稳定扩展命名空间预留。

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
| 金额/时长 | 不使用浮点数；时长使用分钟，时间币使用最小记账单位整数 |
| 字段命名 | JSON 使用 `camelCase` |
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
      "permissions": ["projects.read", "serviceRecords.create"]
    },
    "workspaces": ["student"],
    "featureFlags": {
      "pov": false,
      "redemption": false,
      "borrowing": false,
      "appreciation": false
    }
  }
}
```

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

- 可编辑资源返回 `version` 或 `ETag`。
- 更新时携带 `If-Match` 或 `version`；冲突返回 `409 STATE_CONFLICT`。
- 创建记录、审核决定、账户调整和导入确认支持 `Idempotency-Key`。
- 相同幂等键和请求体返回同一业务结果；相同键不同请求体返回冲突。

### 2.9 删除规则

- 已提交、已审核、已入账的业务记录不可物理删除。
- 草稿可删除；基础数据优先停用或归档。
- 账户余额只能通过流水变化，不提供直接修改余额接口。

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

- `id`, `proposerStudentId`, `coOrganizerStudentIds`
- `title`, `objective`, `description`, `expectedOutcome`
- `categoryId`, `plannedStartAt`, `plannedEndAt`, `location`, `format`
- `targetParticipants`, `capacity`, `volunteerTasks`
- `proposedSupervisorId`, `organizationId`, `externalContact`
- `safetyPlan`, `resourceNeeds`, `recruitmentPlan`
- `proposedRecognizedMinutes`, `evidencePlan`
- `status`, `currentRevision`, `assignedReviewerId`
- `approvedProjectId`, `version`, `createdAt`, `submittedAt`, `updatedAt`

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
- `supervisor`, `evidenceFiles`
- `status`, `currentRevision`, `reviewSummary`
- `creditedUnits`, `accountTransactionId`
- `version`, `createdAt`, `submittedAt`, `updatedAt`

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
| `POST` | `/projects/{id}/close` | `projects.close` | 提前关闭 |
| `POST` | `/projects/{id}/archive` | `projects.archive` | 归档 |
| `GET` | `/projects/{id}/timeline` | `projects.audit.read` | 项目变更时间线 |

项目状态变化使用动作端点，避免用通用 `PATCH status` 绕过业务校验。

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
| `GET` | `/academic-years` | 已登录 | 学年 |
| `GET` | `/grades` | 已登录 | 年级 |
| `GET` | `/classes` | 已登录 | 班级 |

### 4.10 组织与服务类别

| Method | Path | 权限 | 说明 |
| --- | --- | --- | --- |
| `GET` | `/organizations` | `organizations.read` | 组织列表 |
| `POST` | `/organizations` | `organizations.manage` | 创建 |
| `GET` | `/organizations/{id}` | `organizations.read` | 详情 |
| `PATCH` | `/organizations/{id}` | `organizations.manage` | 更新 |
| `POST` | `/organizations/{id}/archive` | `organizations.manage` | 归档 |
| `GET` | `/service-categories` | 已登录 | 服务类别 |
| `POST` | `/service-categories` | `rules.manage` | 创建类别 |
| `PATCH` | `/service-categories/{id}` | `rules.manage` | 更新类别 |

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

## 10. 合约交付与变更管理

### 10.1 OpenAPI

后端实现前，应基于本文生成机器可读的 OpenAPI 3.x 文件，至少包含：

- 路径、方法、权限和请求/响应 Schema。
- 枚举、状态与错误码。
- 示例数据。
- 分页、幂等和并发头。
- 第三、第四阶段端点标记。

React 前端的 API Client 和 Mock Server 应由同一 OpenAPI 合约生成或校验。

### 10.2 变更规则

- 新增可选字段属于兼容变更。
- 删除字段、修改类型、改变枚举语义或收紧原有行为属于破坏性变更。
- 破坏性变更进入新的 API 主版本。
- 枚举新增值时，前端必须有未知值兜底展示。
- API 变更需同时更新 OpenAPI、Mock、合约测试和本文档。

### 10.3 完成定义

一个端点只有同时满足以下条件才视为完成：

- OpenAPI 已定义并通过校验。
- 权限与数据范围测试通过。
- 正常、校验失败、无权限、并发冲突和重复请求行为明确。
- 前端不依赖未记录字段。
- 审计与幂等要求按风险等级落实。

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

TypeScript 类型（如 `Project`、`ServiceRecord`、`Account`）仍从 `../../mock/data` 导入，因为 Mock 数据文件同时充当类型定义源。后端 OpenAPI 生成类型后，导入路径切换为生成的类型文件即可。

### 12.4 仪表盘聚合端点

学生首页使用 `GET /dashboards/student` 一次性获取进度、账户、近期记录和推荐项目。管理后台使用 `GET /dashboards/admin` 获取统计、待审核队列和活跃项目。聚合端点只用于首页，详情页仍使用资源端点。

### 12.5 组织与学校信息

组织列表通过 `api.organizations.list()` 获取，学校信息通过 `api.school.getInfo()` 获取。这些低频变化数据可在组件挂载时获取并缓存于组件状态。
