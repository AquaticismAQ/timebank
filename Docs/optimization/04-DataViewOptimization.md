# 数据展示与审核工作台优化 — 综合澄清与实施计划

## 0. 定位、范围与阅读方式

本文件基于当前前端、API client 与后端列表/审核接口的**实际实现**（逐项核实到 `file:line`，而非从设计文档推断）完善数据展示、查找和详情审核体验。它不重建报表模块，也不改变任何状态机、权限、审核事件或不可变账务规则。

本次优先覆盖三个需要处理大量记录并作出判断的页面：

1. **审核中心**（`AuditCenter.tsx`）：服务记录审核队列；
2. **申诉处理**（`CaseReview.tsx`）：申诉、补充材料和人工更正案件；
3. **活动审核**（`ProposalReview.tsx`）：学生发起活动提案的审核。

原计划中"活动审核"重复出现一次，按上述一个页面处理。学生清单、账户、项目、组织和报表页应复用本文件的列表规范，但**是否在本轮一并迁移是一个需要决定的范围问题**，见 §2.5。

**2026-08-11 — 全部九项决定已按建议方案确认，进入实施。** 下文每一处"⚠️ 需要确认"已改记为"**已确认（2026-08-11）**"，保留原有的方案对比与理由，不是简单地把状态翻转——后续如果要回头核对某个决定为什么这样定，原因仍在原文里。§8 决策索引表同步更新。实施进度见 `Docs/implementation/00-Status.md`（如适用）与本文件 §6 各阶段。

### 如何阅读本文件

沿用 `02-PorjectOrganization.md` 的约定，下文每一条陈述归为三类之一：

- **Finding（已核实的缺口）**——不需要你做选择，只是把当前实现里已确认的问题记录下来，避免遗漏。
- **Settled（可直接实施）**——原计划的做法与现状不冲突，做法明确。
- **已确认（原"⚠️ 需要决定"）**——原计划的表述本身有歧义，或与当前实现存在两种以上合理的实现方式。每一处保留 2–3 个具体方案、对比和已选定的方案。

**核实方式**：本次对 `AuditCenter.tsx`、`CaseReview.tsx`、`ProposalReview.tsx`、`usePagedApi`/`PageControls`/`ListStateBanner`/`EvidenceLink`，以及 `ReviewController`/`ReviewQueueRepository`、`CaseController`/`CaseServiceImpl`、`ProposalController`/`ProposalServiceImpl`、`ServiceRecordController`、`PageQueryArgumentResolver`/`PageableFactory` 逐一读码核实，下文所有"当前表现"陈述均可在对应文件中找到依据。

## 1. 已核实的当前实现

### 1.1 共用能力现状

- `usePagedApi`（`frontend/src/app/api/useApi.ts:55-101`）：默认 `pageSize: 20`（硬编码，`:59`），无 `setPageSize`；返回 `setSearch`/`setFilter`（均自动重置到第 1 页）、`setSort`（不重置页码，但**三个目标页面均未调用**）。
- `PageControls`：仅显示"总数 · 当前页/总页数"与上一页/下一页，**没有页码跳转、没有页大小选择器**。
- `ListStateBanner`：区分 `notAvailable`/`rateLimited`/`conflict`/其它网络错误与空态，但文案是**硬编码英文，未接入 `t()`**；空态**没有"清除筛选"按钮/回调**。
- **URL 参数同步：全仓库零先例。** 搜索 `useSearchParams`/`URLSearchParams`/`history.pushState` 在 `frontend/src/app` 下无一处命中；`react-router` v7 已引入且可用，但没有任何列表页把筛选/排序/页码写入 URL。这意味着本次要建立的是**全新模式**，不是扩展已有模式。
- 后端分页上限：`PageQueryArgumentResolver.java:25-26,46`，`DEFAULT_PAGE_SIZE = 20`，`MAX_PAGE_SIZE = 100`——与原计划提出的 20/50/100 三档完全兼容，无需后端改动即可支持。
- 详情弹层目前没有可复用的"标签页 + 底部固定操作栏"组件。最接近的先例是 `ProjectManagement.tsx` 编辑弹层（头部 `shrink-0` / 主体 `overflow-y-auto` / 底部 `border-t shrink-0` 操作栏），但**没有标签页**；`ui/tabs.tsx` 已作为 shadcn 组件被引入，但目前**仅 `ReportsPage.tsx` 使用**，没有任何弹层用过它。
- `EvidenceLink.tsx`：`{ fileId, name }`，点击时实时请求 5 分钟签名 URL，自行处理 `pending`/`processing`/`rejected`/`ready` 四态，但**不处理"无附件"态**——调用方需要自己判断空列表（`AuditCenter.tsx` 已经这样做）。

### 1.2 三个目标页面现状（更新表）

| 页面 | 搜索 | 状态/筛选 | 排序 | 详情入口 | 操作按钮 |
| --- | --- | --- | --- | --- | --- |
| 审核中心 | 真实服务端（`setSearch`→`params.search`） | `hasAnomaly` 真实服务端；**年级筛选是从当前页 `items` 去重出的选项，只筛当前页**（`AuditCenter.tsx:46-61`） | **纯客户端 `.sort()`，后端 `SORTABLE = Set.of()`（空集）**（`ReviewController.java:38`） | 无——`<tr>` 无 `onClick`，仅图标按钮和移动端"展开材料" | 有文字的 返回/驳回/通过 |
| 申诉处理 | **无输入框** | 状态胶囊按钮（禁止的模式），但**已接入真实服务端筛选**（`setFilter('status', ...)`） | 无控件 | 无——卡片无点击 | 仅图标 + `title` 提示，无可见文字 |
| 活动审核 | **无输入框** | 状态胶囊按钮，默认预筛 `submitted` | 无控件（后端已支持 `plannedStartAt`/`createdAt`/`status`/`id`，未接前端） | 无——卡片无点击 | 仅图标 + `title` 提示，无可见文字 |

### 1.3 原计划低估的三处工作量（Finding，直接影响后面的排期）

1. **审核队列排序不是"加白名单"能解决的**。`ReviewQueueRepository.queryPage`（`ReviewQueueRepository.java:34-59`）是手写 JDBC 原生 SQL，`order by sr.submitted_at asc` 是硬编码字符串（`:57`），根本不读取 `Pageable.getSort()`，也没有像其它列表那样的确定性 `id` 兜底排序。原计划 §3.2 把它写成"排序白名单至少包含…"，读起来像是在已有的 `Pageable` 机制上扩展一个 `Set`（如 Case/Proposal 那样），实际是要**改造查询拼接逻辑本身**，工作量更接近小型重写。
2. **三个域的"审核历史"后端其实已经全部做完了**——`GET /service-records/{id}/timeline`（`ServiceRecordController.java:139-148`）、`GET /cases/{id}/timeline`（`CaseController.java:89-93`）、`GET /event-proposals/{id}/timeline`（`ProposalController.java:116-120`）均已实现，且都是把"版本修订记录"和"审核决定事件"按时间合并排序后返回——正是原计划 §3.2 描述成"需要新增"的能力。真正的缺口完全在前端：`client.ts` 只有 `api.cases.timeline()` 一个方法被接上，`serviceRecords.timeline()`/`eventProposals.timeline()`/三个域各自的 `.revisions()`/`.reviews()` 都**不存在**，UI 也没有渲染。这大幅降低了"审核历史"标签页的实际工作量，但顺序上必须先补 `client.ts`。
3. **状态文案存在两套并行、已经互相矛盾的翻译**。`lib/statusLabels.ts` 里的 `PROPOSAL_STATUS_LABELS`（i18n key 前缀 `labels.proposalStatus.*`）和 `ProposalReview.tsx` 自己拼接的 `admin.proposals.status*` 是两套独立维护的键，对同一个 `submitted` 状态给出的中文分别是"待审核"和"已提交"（均已在 `zh.json` 中确认存在且不同）。三个目标页面目前都只用各自局部拼接的 `admin.xxx.status*` 键，完全没用 `lib/statusLabels.ts`（该文件目前只有 `ProjectManagement.tsx`/`AdminDashboard.tsx` 在用）。新详情工作台如果不先收敛这两套体系，会让同一条记录在列表和详情里显示不同的中文状态名。

## 2. 跨页共用基础设施：已确认的决定

以下决定对三个目标页面都适用，先在此定案，页面各自小节里只引用结论，不重复讨论。

### 2.1 URL 参数同步的实现方式

因为全仓库没有先例（§1.1），"怎么做"本身就是一个要现在定下来的架构选择，避免三个页面各写一套、以后迁移其它列表页时又要重新发明。

**方案 A——每个页面独立调用 `useSearchParams` 手动同步。** 每个页面自己在 `usePagedApi` 的 `params` 变化时 `setSearchParams(...)`，并在挂载时从 URL 读初始值传给 `usePagedApi`。改动完全局部，不碰共享 hook，但三份几乎相同的样板代码要分别维护，容易出现三页行为不一致（例如页大小参数名拼错）。

**方案 B——新增一个共享 hook，包一层 `usePagedApi`。** 新建 `useUrlSyncedPagedApi(fetcher, initialParams)`（放在 `frontend/src/app/api/useApi.ts` 旁），内部用 `useSearchParams` 读写 `search`/`filter[...]`/`sort`/`page[number]`/`page[size]`，其余行为完全代理给 `usePagedApi`。三个目标页面只需把 `usePagedApi(...)` 换成这一个 hook。以后要迁移学生/账户/项目/组织/服务记录列表页（如果做，见 §2.5）时直接复用，行为天然一致。

**建议：方案 B。** 这是三个页面共同需要的能力，且原计划 §2.1.5 明确要求参数写入 URL、§6 也要求其它列表页"按 §2 接入…URL 同步"——既然迟早要有一个统一实现，不如第一次就建成可复用的，避免以后要把三份手写逻辑再合并一次。

**已确认（2026-08-11）：方案 B——新建共享 hook `useUrlSyncedPagedApi`。**

### 2.2 状态文案权威来源收敛

针对 §1.3 第 3 点的具体矛盾。

**方案 A——以 `lib/statusLabels.ts` 为唯一权威源。** 补齐目前缺失的 `CASE_STATUS_LABELS`，把 `AuditCenter.tsx`/`CaseReview.tsx`/`ProposalReview.tsx` 的本地 `STATUS_COLOR` + 内联 `` t(`admin.xxx.status${...}`) `` 拼接全部替换为从 `statusLabels.ts` 取值；`admin.audit.status*`/`admin.cases.status*`/`admin.proposals.status*` 这些旧键逐步废弃（不必立刻删除翻译文件里的键，避免误伤其它引用，但新代码不再产生新的分叉）。中文措辞按 `labels.*` 现有值为准（如 `submitted` = "待审核"），因为这一套已经是仪表盘等其它页面在用的口径。

**方案 B——反过来，把 `statusLabels.ts` 废弃，统一到各页面自己的 `admin.xxx.status*` 键。** 工作量类似，但会让 `ProjectManagement.tsx`/`AdminDashboard.tsx` 也要跟着改，影响面更大，且这两处的措辞现在其实是稳定使用中的（没有已知投诉），没有必须改的理由。

**方案 C——保留两套，只解决具体的措辞冲突（人工挑一个中文词）。** 改动最小，但不解决"两套系统并存、以后还会分叉"的根因，等于把 §1.3 第 3 点的问题留到下一次优化再处理。

**建议：方案 A。** 是三处里改动集中在本次要重写的三个页面本身、影响面最小，又能实际消除根因的选项；C 只是把问题往后推。

**已确认（2026-08-11）：方案 A——统一到 `lib/statusLabels.ts`，补齐 `CASE_STATUS_LABELS`，中文措辞按 `labels.*` 现有值为准。**

### 2.3 详情工作台的呈现形态与打开方式

原计划各处写"全屏或大尺寸模态""点击行/卡片打开"，两处都留了歧义，且第二处与原计划自己在 §4.2/§5.2 主张的"操作要有可见文字，图标单独使用在移动端和无障碍场景不清晰"存在内部张力——一个没有任何可见提示、靠隐式约定才知道"点这一整行能打开详情"的 `<tr>`/卡片，是同一类无障碍问题。

**呈现形态：**

**方案 A——响应式二选一。** 移动端（Tailwind `sm` 断点以下，即当前 `AuditCenter.tsx` 卡片视图切换点）用铺满屏幕的 sheet；桌面/平板用居中大模态（`max-w-4xl max-h-[90vh]`），复用 `ProjectManagement.tsx` 编辑弹层已有的头部/可滚动主体/底部操作栏骨架，主体内部用刚引入项目但目前只有 `ReportsPage.tsx` 在用的 `ui/tabs.tsx` 分标签。三个页面共建一个 `components/shared/DetailWorkbench.tsx`，标签内容按域注入。

**方案 B——始终全屏，不分桌面/移动。** 实现更简单（不用处理两套断点样式），但桌面端大量留白，和现有其它弹层（编辑项目、登记花名册）的"居中卡片"视觉不一致，偏离本文件本身反复强调的"移动优先，逐步增强到桌面"。

**建议：方案 A**，与项目既有的响应式约定一致，也是原计划文字本身暗示的意思（"全屏**或**大尺寸模态"读作"移动端用一种、桌面端用另一种"最合理）。

**已确认（2026-08-11）：呈现形态取方案 A。**

**打开触发方式：**

**方案 A——整行/整卡片可点击。** 原计划字面写法。需要给选择框、证据链接、图标操作按钮逐个加 `stopPropagation`；`<tr>`/卡片本身没有原生可聚焦/可读性语义，键盘用户和屏幕阅读器用户没有明确提示"这里可以打开详情"。

**方案 B——只提供一个明确的"查看详情"文字+图标按钮。** 每行/每卡片新增一个真正的 `<button>`（或链接），其余图标操作不变，不需要处理事件冒泡。更符合本文件自己在别处要求的"带图标和可见文字"标准，但对惯用点击整行来看详情的桌面用户来说，发现性略低。

**方案 C——两者都要：整行/卡片可点击（鼠标用户的便捷路径），同时保留一个明确的"查看详情"按钮作为主要的可访问入口。** 选择框/证据链接/操作图标仍需 `stopPropagation`，但因为有真正的 `<button>` 承担"这是可交互的"语义，键盘和屏幕阅读器用户走按钮，鼠标用户可以图省事点整行。

**建议：方案 C。** 直接解决了上面指出的原计划内部矛盾——不牺牲鼠标用户已经习惯的整行点击，同时补上一个真正满足本文件自己无障碍标准的入口。

**已确认（2026-08-11）：打开方式取方案 C——行/卡片可点击 + 明确的"查看详情"按钮。**

### 2.4 决定操作的评语必填策略

当前三个页面互相不一致：`AuditCenter.tsx` 的提交按钮在**非通过**决定时前端要求 `reasonCode` 和 `comment` 都非空（`:454`，纯前端限制）；`CaseReview.tsx`/`ProposalReview.tsx` 完全没有这个限制（原因码硬编码，评语框可以留空提交）。后端三个决定接口（`ReviewRequests`/`CaseRequests`/`ProposalRequests` 的 `DecideRequest`）统一要求 `reasonCode` `@NotBlank`，但 `comment` 均**无校验注解**，后端本身对三个域一视同仁。

**方案 A——统一成 `AuditCenter.tsx` 现在的规则：非通过类决定（驳回/退回/要求修改/要求补充材料）前端强制要求填写评语，通过类决定评语可选。** 三个域行为一致，且是三者中已经在生产使用、没有已知投诉的那一条规则。

**方案 B——维持现状，三个页面各自的规则不变。** 零改动，但共用详情工作台的底部操作栏如果要做成一个可复用组件，"要不要校验评语"就得做成按域可配置的 prop，反而更复杂，且用户体验仍然不一致。

**方案 C——完全跟后端对齐，评语一律可选，只强制 `reasonCode`。** 改动最小（去掉 `AuditCenter.tsx` 现有的前端限制），但会让审核中心的把关变松，且没有任何迹象表明现有的更严格规则造成了问题。

**建议：方案 A。** 共用工作台组件本来就要统一操作栏，顺带统一规则比做成按域可配置更简单，也不降低现有最严格页面的把关力度。

**已确认（2026-08-11）：方案 A——非通过类决定统一要求评语，通过类决定评语可选。**

### 2.5 范围边界：本轮是否包含其它列表页与报表页

原计划 §6 用"应复用本规范"（§0，措辞留了余地）和"继续使用…并接入 §2"（§6 正文，读起来像本轮就要做）两种口径混着写，直接影响排期规模——如果把学生、账户、项目、组织、服务记录列表页和报表页/仪表盘的改造都算进本轮，工作量会成倍增加。

**方案 A——严格限定在三个目标页面（审核中心/申诉处理/活动审核）及其依赖的共用组件（分页、详情工作台框架）本身，其它列表页和报表页明确列为后续跟进项，不在本轮排期内。**

**方案 B——本轮一并完成所有列表页向共用分页规范（页大小/URL 同步/清除筛选）的迁移，但详情工作台仍只做三个目标页面；报表/仪表盘的"移除硬编码更新时间和学年选项"单列一个后续小任务。**

**方案 C——按原计划字面，本轮全部完成，包括报表页硬编码清理。**

**建议：方案 A。** 三个目标页面已经包含"后端契约补齐→共享分页组件→详情工作台框架→接入决定操作→端到端验证"五个实质性阶段（见 §6），先把这条主线做完、验证过，再决定是否把同一套分页规范复制到其它列表页，比一次性铺开更容易控制质量和范围。§6 的其它要求（学生/账户/项目/组织/服务记录列表页迁移、报表页硬编码清理）保留在文档中作为已记录但**不纳入本轮阶段计划**的后续工作。

**已确认（2026-08-11）：方案 A——严格限定三个目标页面，其它列表页/报表页列为后续跟进项。**

## 3. 审核中心：详情审核工作台

### 3.1 列表筛选与排序完善

**已确认——年级筛选用哪个字段。** `ReviewQueueItemDto`（`review/application/dto/ReviewQueueItemDto.java`）目前携带的是 `gradeLevel`（`int`，年级序号，如 7/8/9），这是 `ReviewQueueRepository` 原生 SQL 已经在 `select` 里算出来的列（`grade_sequence`）。而 `StudentController`/`CaseController` 等其它列表统一用字符串 `gradeId` 做筛选参数（`filter[gradeId]`，`StudentController.java:54`）。

- **方案 A——按 `gradeLevel`（`int`）筛选，参数为 `filter[gradeLevel]`。** 只需在现有原生 SQL 里加一个 `where` 条件，不涉及新增 join 或修改 DTO，成本最低。缺点：与其它页面的年级筛选参数名/类型不一致，如果以后要做一个跨页复用的"年级选择器"组件，这里要单独适配。
- **方案 B——按 `gradeId`（字符串）筛选，参数为 `filter[gradeId]`，与 Student/Case 一致。** 需要在原生 SQL 里同时选出 `grade_id`（大概率已经在 join 的年级表里，只是没有被选出来）、`ReviewQueueItemDto` 加一个字段。多一点改动，换来跨页一致性。

**建议：方案 B**，为的是能和 §2.5（如果以后确实要迁移其它列表页）共用同一个年级筛选组件；原计划 §3.2 本身写的是"`filter[gradeId]` 或等价的稳定年级筛选"，也把 B 列为首选表述。

**已确认（2026-08-11）：方案 B——按 `gradeId`（字符串）筛选。**

**已确认——排序怎么落地（对应 §1.3 第 1 点）。**

- **方案 A——在现有原生 SQL 上扩展：新增一个 `ORDER BY` 字段白名单到 SQL 片段的映射（`submittedAt`→`sr.submitted_at`、`durationMinutes`→`sr.duration_minutes`、`daysWaiting`→现有的 `days_waiting` 表达式），拼接时用白名单查表而非直接插入用户输入（防注入），并**始终**在末尾追加 `, sr.id asc` 作为确定性兜底排序，与 `PageableFactory` 给其它列表的保证对齐。不改变整体查询结构、不新建 Repository 类型。
- **方案 B——把审核队列从原生 JDBC 重写为 JPA `Specification`/`Pageable`，与 Case/Proposal 同构。** 一致性最好，能直接复用 `PageableFactory`/`SORTABLE` 现成机制，但 `daysWaiting`/`priority` 是查询时计算的派生列（非实体字段），`Specification` 表达起来不直接，可能要么退化成数据库视图/计算列，要么在应用层排序（丧失"服务端排序覆盖全部结果集"这条硬性验收标准）。工作量明显大于 A，且有退化风险。

**建议：方案 A。** 保留原生 SQL 是因为它本来就是为 `daysWaiting`/`priority` 这类派生列存在的，B 想要的"和其它列表同构"不值得为此牺牲派生列排序或走查询重构的风险；A 用白名单映射同样能防注入、同样能保证确定性排序。

**已确认（2026-08-11）：方案 A——原生 SQL 扩展 + 白名单映射 + `id` 兜底排序。**

**Finding——搜索范围扩展。** 现有 `search` 已覆盖 `student_name`/`organization_name`/`project.title`（`ReviewQueueRepository.java:78-89`），原计划要求追加"服务记录编号"（学号搜索原计划未提及，`sr.student_number` 需确认是否已在同一张视图里可用）。这是直接的字段追加，不构成分叉。

### 3.2 详情工作台设计

沿用 §2.3 的呈现形态（响应式全屏/大模态）与打开方式（整行可点击 + 明确"查看详情"按钮）、§2.4 的评语必填策略。

- **基本信息**标签：`GET /service-records/{id}` 已经是权威详情源（`ServiceRecordServiceImpl.java:119-129`），比列表行更完整；但注意 `ServiceRecordDto` 原本**没有** `gradeLevel`/`className`/`reviewerName`/`reviewedAt` 字段（这些只存在于队列专用的 `ReviewQueueItemDto`），前端类型里原先声明的 `reviewerName?`/`reviewedAt?` 实际上永远是 `undefined`（`client.ts:58-60` 的映射函数没有数据可填）。**已实施（2026-08-11）**：`ServiceRecordDto` 已扩展 `gradeLevel`/`className`/`reviewerName`（`gradeLevel`/`className` 批量解析自 `AcademicStructureService.listGrades`/`listClasses`，与既有 `categoryNameMap` 同一模式；`reviewerName` 仅对分页内出现的去重后 `assignedReviewerId` 做解析，避免逐行 N+1 查询）——`reviewedAt` **未加入**：记录本身没有单一"最近一次审核时间"列，真正的多次审核历史属于 `/timeline` 端点的审核历史标签页，不应该拍平进这个 DTO；前端 `reviewerName?`/`reviewedAt?` 字段里的 `reviewedAt` 需要在阶段 3 改为从 `timeline` 结果读取，而不是从 `serviceRecords.get()`。
- **审核历史**标签：`GET /service-records/{id}/timeline` 已经实现（§1.3 第 2 点），只需在 `client.ts` 新增 `api.serviceRecords.timeline(id)` 并渲染。
- **证明材料**标签：复用 `EvidenceLink`，现有移动端"展开材料"已经在做同一个详情请求（`AuditCenter.tsx:68-79`），按原计划直接删除该内联展开，逻辑平移进新标签页即可，不是新开发。

## 4. 申诉处理：案件详情与可读操作

### 4.1 列表

**Finding，非决策分叉。** `CaseController.list`（`CaseController.java:47-55`）目前把 `query.filter("status")`/`query.search()` 完全丢弃，只传 `pageable` 给 `CaseServiceImpl.list`——原计划的描述是准确的，需要按 Proposal 的既有写法（`ProposalServiceImpl.list` 已经在用 `FilterSpecifications.equalToEnum`/`searchAnyContains`）补上 `status`/`search` 两个参数的传递。`SORTABLE` 目前是 `Set.of("id","status","createdAt")`（`CaseController.java:37`），原计划要求的 `submittedAt` 字段在 `Case` 实体上**已经真实存在**（`Case.java:85-86`，`submitted_at` 列，已经在 `CaseDto` 里）——加入白名单是一行改动，不需要新增列。状态胶囊按钮已经接的是真筛选参数（`setFilter('status', ...)`），换成下拉框只是纯前端替换，不影响已有的服务端行为。

### 4.2 详情工作台

沿用 §2.3/§2.4。

**已确认——"关联对象"（`subjectType`/`subjectId`）怎么显示为可读链接。** `Case` 实体和 `CaseDto` 已经携带 `subjectType`/`subjectId`（`Case.java:49-54`，`CaseDto.java:7`），但 `CaseDto` 没有为它们提供人类可读的名称（不像 `studentName` 那样有配套的反规范化字段；`CaseDto.java` 顶部注释本身写着"Denormalized names travel with their id"，即这是该 DTO 已确立的约定，只是这一个字段组没跟上）。按仓库既有约定（"IDs are opaque server-generated strings"——前端不应该试图从 `subjectType` 猜测怎么拼一个详情链接或反查资源）：

- **方案 A——后端补一个反规范化字段，如 `subjectLabel`（人类可读名称，例如项目标题或服务记录编号），在 `CaseServiceImpl` 组装 `CaseDto` 时按 `subjectType` 查一次对应表算出来。** 与 `studentName` 完全同构，前端只是展示一个字符串（是否做成可点击链接跳转到对应服务记录/项目详情，看是否已有对应详情路由，若没有则先展示纯文本）。
- **方案 B——前端按 `subjectType` 分支，动态调用对应资源的详情接口（服务记录用 `serviceRecords.get`，项目用 `projects.get`）取名称。** 不用改后端，但需要前端维护一个 `subjectType`→API 方法的映射，多一次网络请求，且如果当前登录用户对目标资源没有读权限，前端还要单独处理 403（等于把权限判断逻辑复制到了前端，而不是让后端一次性返回它已经有权限算出的名称）。

**建议：方案 A**，直接符合 `CaseDto` 自己写明的既有约定，也避免在前端做本该后端一次性解决的权限和拼接判断。

**已确认（2026-08-11）：方案 A——后端补 `subjectLabel` 反规范化字段。**

## 5. 活动审核：完整提案后再决定

### 5.1 列表

**Settled。** 后端 `status`/`categoryId`/`search`（仅覆盖 `title`）/排序白名单（`plannedStartAt`/`createdAt`/`status`/`id`）均已实现且已被 `ProposalReview.tsx` 部分使用（状态筛选）；本节工作纯粹是前端替换胶囊按钮为下拉框、新增排序控件、把默认 `status: 'submitted'` 的预筛选保留（原计划要求"全部"选项存在即可，不要求默认值改变）。**Finding**：`search` 目前只搜 `title`（`ProposalServiceImpl.java` 用 `searchAnyContains(search, "title")`），原计划没有明确要求扩展搜索范围，保持现状即可，除非后续验收发现不够用。

### 5.2 详情工作台

沿用 §2.3/§2.4。

**Finding，非决策分叉。** 组织与安全所需的全部字段（`coOrganizers`/`mentorTeacher`/`partnerOrg`/`riskMeasures`/`materialNeeds`/`budgetNeeds`/`recruitmentMethod`/`proofMethod`）**已经存在**于 `EventProposal` 前端类型和后端 `ProposalDto`，只是从未被 `ProposalReview.tsx` 渲染过（目前只有学生自己的 `EventProposals.tsx` 表单在读写）——这是纯展示层的接线工作，不需要新字段。

**Finding。** 前端 `EventProposal.timeline` 字段虽然在类型里声明为必填（`types.ts:448`），但真实后端 `ProposalDto` 根本没有 `timeline` 字段，所以运行时永远是 `undefined`；真正的时间线来自独立端点 `GET /event-proposals/{id}/timeline`（已实现，§1.3 第 2 点），需要在 `client.ts` 新增 `api.eventProposals.timeline(id)` 并让详情工作台调用它，同时应删除或修正前端类型里名不副实的 `timeline` 字段声明，避免以后有人以为它已经有数据。

## 6. 实施顺序（六阶段，端到端验证为最后一阶段）

以下阶段假定 §2 的全部决定已确认（方案标注为 §2.x 的建议方案，如你选择其它方案，阶段划分不受影响，只是各阶段的具体任务替换）。

**阶段 0 — 后端契约补齐**
- 审核队列：新增年级筛选（§3.1，参数按 §3.1 决定）；排序改造（§3.1 决定，建议方案 A：白名单映射 + `id` 兜底）；`ServiceRecordDto` 补齐 `gradeLevel`/`className`/`reviewerName`/`reviewedAt`。
- 案件列表：`CaseController`/`CaseServiceImpl.list` 补齐 `status`/`search` 参数传递；`SORTABLE` 加入 `submittedAt`。
- 案件详情：按 §4.2 决定，`CaseDto` 补 `subjectLabel`（如选方案 A）。
- 每一项都补对应的集成测试（`*IT`，走 `mvn verify`），不在本阶段做前端改动，避免"当前页假筛选"掩盖后端还没做好的假象。

**阶段 1 — 共用列表基础设施**
- 按 §2.1 决定实现 URL 同步（建议：共享 hook `useUrlSyncedPagedApi`）。
- `PageControls` 增加页码跳转与页大小选择器（20/50/100）。
- `ListStateBanner` 增加"清除筛选"回调与 i18n。
- 三个目标页面接入以上能力，去除各自的客户端筛选/排序、状态胶囊按钮换成下拉框、补齐搜索框（申诉处理、活动审核目前没有）。

**阶段 2 — 详情工作台框架**
- 新建 `components/shared/DetailWorkbench.tsx`：按 §2.3 决定的呈现形态（响应式全屏/大模态）+ 打开方式（整行/卡片可点击 + 明确"查看详情"按钮），底部固定操作栏按 §2.4 决定统一评语必填策略。
- `client.ts` 补齐三个域缺失的方法：`serviceRecords.timeline()`、`eventProposals.timeline()`、`eventProposals.revisions()`/`.reviews()`（如需要）。

**阶段 3 — 三个页面分别接入详情内容**
- 审核中心：基本信息/审核历史/证明材料三个标签接入真实数据；移除移动端内联"展开材料"。
- 申诉处理：基本信息（含 §4.2 的关联对象展示）/处理历史/相关材料三个标签；操作栏图标补上可见文字。
- 活动审核：活动内容/组织与安全/审核历史三个标签，接入此前从未渲染过的字段；操作栏图标补上可见文字。

**阶段 4 — 状态文案收敛**
- 按 §2.2 决定（建议方案 A）统一到 `lib/statusLabels.ts`，补齐 `CASE_STATUS_LABELS`，三个页面切换到共用来源，核对中英文措辞与仪表盘等既有使用处保持一致。

**阶段 5 — 端到端验证（最终阶段）**

按仓库既定的真实测试服流程（`https://tb.example.com/`，见 `CLAUDE.md`"Bug-tracking and live-verification workflow"），不在本地/mock 环境下宣称验证完成：

1. 部署前先确认前端/后端均已按最新提交部署到共享测试服（`frontend/scripts/deploy.sh` / 后端对应脚本），避免用旧构建验证新行为。
2. 逐页验证原计划 §7（本文件的验收标准，见下）列出的全部场景：中英文双语、移动/桌面、空结果、请求错误、无权限、乐观锁并发冲突（同一记录两个审核人几乎同时提交决定）、跨多页的筛选与排序结果准确性、URL 参数在刷新/前进后退/复制链接后的一致性。
3. 发现的任何真实缺陷按 `CLAUDE.md` 约定写成 `Docs/issues/0NN-<slug>.md`（编号接续目录里最大编号），并同步更新 `integration-test/STATUS.md` 的缺陷表和修复记录，当场做，不要留到收尾批量补。
4. 全部场景通过后，在 `Docs/implementation/00-Status.md` 对应位置（若适用）或本文件补一条完成记录，注明验证日期和账号。

## 7. 验收标准（保留自原计划，按本次决定更新表述）

1. 三个目标页面均没有状态按钮/胶囊筛选；搜索、筛选、排序、页大小和页码写入 URL 查询参数，刷新/前进后退/复制链接后保持一致。
2. 任一筛选和排序均由服务端对完整结果集执行（含审核队列的新排序实现，§3.1）；总数来自服务端 `page.totalItems`，页间不重不漏。
3. 审核中心、申诉处理和活动审核均可从桌面行/移动卡片打开完整详情（§2.3 决定的打开方式）；附件访问使用既有权限和短时链接；关联对象（案件）以可读名称展示，不暴露裸 ID。
4. 每种决定操作都有可见文字（不再是纯图标 + `title`）、明确确认、统一的原因/评语校验策略（§2.4）、乐观锁版本号与幂等键；成功后只刷新必要数据并保留筛选、排序、页码。
5. 三个页面的状态文案与仪表盘等既有页面一致（§2.2），不存在同一状态两种中文译法的情况。
6. 报表和仪表盘等 §2.5 决定排除在本轮之外的页面，明确记录为后续工作，不在本轮验收范围内。

## 8. 决策索引

| # | 章节 | 问题 | 决定 | 状态 |
|---|------|------|----------|------|
| 1 | §2.1 | URL 参数同步：各页独立实现 vs 共享 hook | B（共享 hook） | **已确认（2026-08-11）** |
| 2 | §2.2 | 状态文案双轨制如何收敛 | A（统一到 `lib/statusLabels.ts`） | **已确认（2026-08-11）** |
| 3 | §2.3 | 详情工作台呈现形态 | A（响应式：移动全屏/桌面大模态） | **已确认（2026-08-11）** |
| 4 | §2.3 | 详情工作台打开方式 | C（行/卡片可点击 + 明确"查看详情"按钮） | **已确认（2026-08-11）** |
| 5 | §2.4 | 评语必填策略是否跨三域统一 | A（统一为非通过类决定必填） | **已确认（2026-08-11）** |
| 6 | §2.5 | 本轮是否包含其它列表页/报表页 | A（严格限定三个目标页面，其它列为后续） | **已确认（2026-08-11）** |
| 7 | §3.1 | 审核中心年级筛选字段 | B（`gradeId`，与其它页面一致） | **已确认（2026-08-11）** |
| 8 | §3.1 | 审核队列排序实现方式 | A（原生 SQL 扩展 + 白名单映射） | **已确认（2026-08-11）** |
| 9 | §4.2 | 案件"关联对象"如何显示可读名称 | A（后端补 `subjectLabel` 反规范化字段） | **已确认（2026-08-11）** |

全部九项已确认，进入实施——见 §6 各阶段，实施状态在本文件末尾追加记录（§9，实施过程中更新）。

## 9. 实施状态

**2026-08-11 — 开始实施，阶段 0 进行中。** 后续每完成一个 §6 阶段，在此追加一行记录（日期、阶段、要点），不做批量事后补写。

**2026-08-11 — 阶段 0（后端契约补齐）完成。** `mvn test`（ArchUnit + MockMvc 全量）绿。要点：
- 审核队列：`filter[gradeId]` 已接入（`ReviewQueueItemDto` 新增 `gradeId`）；排序改为白名单映射到原生 SQL `ORDER BY`（`submittedAt`/`durationMinutes`/`daysWaiting`→`timestampdiff`，`id` 兜底），无排序参数时默认 `daysWaiting desc`。
- `ServiceRecordDto` 新增 `gradeLevel`/`className`（批量解析，同 `categoryNameMap` 模式）/`reviewerName`（仅对分页内去重后的 `assignedReviewerId` 解析，避免逐行 N+1）；`reviewedAt` 按 §3.2 记录的理由未添加。
- 案件列表：`CaseController`→`CaseServiceImpl.list` 补齐 `status`/`type`/`search`（`search` 目前只匹配 `title`）；`SORTABLE` 新增 `submittedAt`。
- `CaseDto` 新增 `subjectLabel`：新增 `ServiceRecordService.recordLabel(id)`（不做权限范围检查，仅返回 `recordNumber`，历史无编号记录返回空——不回退到裸 ID），`caseflow`→`servicerecord` 的跨模块依赖通过其 `application` 层接口，符合 `ModuleBoundaryTest` 的模块边界规则。

**2026-08-11 — 阶段 1（共用列表基础设施）完成。** `pnpm build` 绿。要点：
- 新增 `useUrlSyncedPagedApi`（`api/useApi.ts`）：首次挂载从 URL 读取 `page[number]`/`page[size]`/`sort`/`search`/`filter[...]`（与 `buildListQuery` 同一套键名，不是第二套方言），此后状态单向写回 URL（`replace`，不堆历史记录）；`usePagedApi` 本身新增 `setPageSize`。
- `PageControls` 新增可选的 `pageSize`/`onPageSizeChange`（20/50/100）与页码跳转输入框——不传则与之前行为完全一致，未迁移的其余 10 处列表页不受影响（§2.5 范围决定）。
- `ListStateBanner` 接入 i18n，新增 `hasActiveFilters`/`onClearFilters`（"清除筛选"）。
- 审核中心：年级筛选改为真实 `filter[gradeId]`（来自 `useDictionaries()` 全量年级，教师按 `scopeGradeIds` 收窄，与 `AccountManagement.tsx` 同一约定），排序改为单一字段下拉 + 升降序按钮，服务端真实排序（`daysWaiting`/`submittedAt`/`durationMinutes`），默认 `daysWaiting desc`。
- 申诉处理：状态胶囊改为下拉，新增搜索框、类型筛选、排序下拉（`submittedAt`/`createdAt`/`status`）。顺带发现并修补 `client.ts`：新增的案件 `type` 筛选需要跟 `status` 一样经 `toBackendEnum` 转换驼峰→大写下划线，否则后端 `Enum.valueOf` 精确匹配会静默返回空集——`status` 早已这样处理，只是这次新增的 `type` 之前没有。
- 活动审核：状态胶囊改为下拉，新增搜索框、类别筛选（复用 `serviceCategories` 字典）、排序下拉（`plannedStartAt`/`createdAt`/`status`），默认预筛 `status=submitted` 保留。
- 详情工作台、决定操作的评语/原因校验统一（§2.3/§2.4）留给阶段 2/3——本阶段未改动三页现有的图标操作按钮和决定弹窗。

**2026-08-11 — 阶段 2（详情工作台框架 + client.ts 接线）完成。** `pnpm build` 绿。要点：
- 新增 `components/shared/DetailWorkbench.tsx`：响应式外壳（移动端铺满全屏 sheet / 桌面端 `sm:max-w-4xl` 居中大模态，与 `ProjectManagement.tsx` 编辑弹层同一套头部/可滚动主体/底部操作栏骨架），标签页采用本仓库已有的手写下划线样式（与 `ReportsPage.tsx` 一致——`ui/tabs.tsx` 组件在全仓库实际未被任何页面使用过，遂不引入，跟随已验证的既有约定而非引入新依赖）。标签内容、底部操作栏内容均由调用方注入（三页各自域的字段/操作差异较大，本阶段不做过度抽象）；`open`/`activeTab` 由调用方持有，因为 D5 的两个入口（整行/卡片点击 + 明确按钮）都要能从外部驱动同一份状态。
- `client.ts` 新增 `serviceRecords.timeline(id)`、`eventProposals.timeline(id)`（与既有 `cases.timeline()` 同一写法），`types.ts` 新增共享 `TimelineEntry` 类型（三个后端模块的 `TimelineEntryDto` 形状完全一致）。
- 顺带补齐 Phase 0 遗留的前端类型缺口：`Case` 加 `subjectLabel`，`ServiceRecord` 加 `gradeLevel`/`className`（两者早已随 DTO 扩展流到线上，只是前端类型没声明）。
- **顺带发现并修复一个真实缺陷**（非本轮范围内页面，但直接触碰到同一个字段）：学生端 `EventProposals.tsx` 详情卡"时间线"区块无条件 `p.timeline.map(...)`，而真实后端 `ProposalDto`从未有过 `timeline` 字段——每次打开提案详情都会崩溃。做了最小修复（类型改为可选、两处访问加 `?? []`），记录为 `Docs/issues/016-*.md`，未做完整数据接线（那需要把 `EventProposals.tsx` 接到刚新增的 `api.eventProposals.timeline()`，作为独立后续项，不在本轮范围内）。`integration-test/STATUS.md` 已同步。

**2026-08-11 — 阶段 3（三页详情内容接入）完成。** `pnpm build` 绿。要点：
- 三页均已接入 `DetailWorkbench`：打开方式按 §2.3 决定——行/卡片整体可点击，同时新增一个明确的"查看详情"文字+图标按钮（`common.viewDetails`）作为键盘/屏幕阅读器可达的主入口；选择框、既有的图标操作按钮全部补上 `stopPropagation`。
- 审核中心：基本信息（学生/班级/记录编号/来源/项目组织/服务日期/开始结束时间/时长/地点/服务性质/主管/路途时间/描述）、审核历史（`serviceRecords.timeline()`）、证明材料（复用 `EvidenceLink`）三个标签；移动端旧的内联"展开材料"已整体移除，由工作台取代。桌面/移动的返回、驳回、通过按钮保留在行/卡片上（高频操作，未强制收进工作台），工作台底部也提供同一组按钮供带完整上下文决策，两者复用同一个 `decisionModal` 确认流程。
- 申诉处理：基本信息（含 D8 的 `subjectLabel` 展示"关联对象"）、处理历史（`cases.timeline()`）、相关材料（仅当 `subjectType === 'serviceRecord'` 时尝试拉取，403/404 时静默不显示——不做客户端权限判断，交给后端）三个标签；图标操作按钮全部补上可见文字。
- 活动审核：活动内容、组织与安全（首次渲染 `coOrganizers`/`mentorTeacher`/`partnerOrg`/`riskMeasures`/`materialNeeds`/`budgetNeeds`/`recruitmentMethod`/`proofMethod`/`eligibility`——这些字段此前只有学生表单读写过，管理端从未展示）、审核历史（`eventProposals.timeline()`）三个标签；卡片列表补充地点字段；图标操作按钮全部补上可见文字；活动内容标签里若已批准会显示生成项目的标题（无可跳转链接——管理端没有单项目详情路由，不虚构一个）。
- 三页复用同一套 `student.proposals.*`/`admin.audit.*`/`admin.cases.*` 既有字段标签而非新造一套，减少与 §2.2（阶段 4）要处理的重复文案问题。

**2026-08-11 — 阶段 4（状态文案收敛）完成。** `pnpm build` 绿。要点：
- `lib/statusLabels.ts` 新增 `CASE_STATUS_LABELS`（此前完全没有）和共用的 `STATUS_COLOR_CLASSES`（颜色名→Tailwind 类名映射，此前只在 `ProjectManagement.tsx` 本地重复一份，本次是第三次需要同一份映射时才提出来共享；`ProjectManagement.tsx` 本身不在本轮范围内，未改动）。
- `CaseReview.tsx`/`ProposalReview.tsx` 的本地 `STATUS_COLOR` 映射表和 `` t(`admin.xxx.status${...}`) `` 拼接全部替换为 `CASE_STATUS_LABELS`/`PROPOSAL_STATUS_LABELS`（列表状态下拉框、卡片状态徽章、详情工作台"状态"字段三处）。
- 按 D2 决定，中文措辞以 `labels.*` 为准：`labels.proposalStatus.submitted`（"待审核"）现在是提案审核页里 `submitted` 状态显示的唯一来源，取代了之前该页自己的 `admin.proposals.statusSubmitted`（"已提交"）——这是一处真实的、刻意的文案变化，从今往后同一条提案在列表和详情里不会再出现两种中文状态名。旧的 `admin.cases.status*`/`admin.proposals.status*` 翻译键按计划保留在 i18n 文件里未删除（已确认无其它引用，纯粹是避免不必要的文件改动，不是还有地方在用）。

**2026-08-11 — 阶段 5（端到端验证，最后阶段）完成。** 后端、前端均已重新部署到 `https://tb.example.com/` 并通过 Playwright 实测验证。要点：
- 部署：`backend/scripts/deploy.sh`（`mvn -q -DskipTests package` + systemd 重启，`GET /api/v1/ping` 确认健康）、`frontend/scripts/deploy.sh`（含 `tsc --noEmit`，干净）。
- 验证覆盖：三页的 URL 参数同步（深链接、刷新均正确还原筛选/排序/页码——用 `sort=durationMinutes&filter[hasAnomaly]=true` 深链接实测确认）；年级/类型/类别筛选来自真实字典；三个详情工作台的全部标签页（审核中心：基本信息/审核历史/证明材料，真实证据文件正确渲染；申诉处理：基本信息/处理历史/相关材料，材料标签在当前种子数据下正确显示"暂无"——直接查库确认全部 5 条种子案件的 `subject_id` 均为 `NULL`，是既有种子数据缺口，不是代码缺陷，本轮因此无法实测"有真实关联证据"的正向路径；活动审核：活动内容/组织与安全/审核历史，此前从未渲染过的字段全部显示真实数据）；审核中心完成一次真实的"通过"决定全流程（队列 6→5，行消失，列表刷新后筛选条件保留）；决定弹窗从行内和工作台底部两个入口都能正确打开；中英双语（含验证 D2 的文案变化——提案 `submitted` 现在处处显示"待审核"）；移动端视口（390×844，截图确认工作台铺满全屏）。
- **实测发现并修复两个真实缺陷**（`Docs/issues/017-*.md`，已修复并重新部署验证）：(1) 审核中心"仅看异常"按钮在深链接/刷新时视觉状态与实际筛选不同步（数据筛选本身正确，只是按钮高亮状态用了独立的本地 state，没有从 `params` 派生）；(2) 活动审核工作台"组织与安全"标签的"学生发起人"字段错误复用了学生创建表单里一句很长的说明性文案作为字段值，而不是显示真实发起人姓名（应该用 `student.proposals.detailLabels.*` 这一套专为只读展示准备的键，之前误用了 `student.proposals.*` 顶层的表单专用键）。
- 顺带重新确认（非新缺陷）：已记录、刻意未修的 011 号问题（字典语言切换后未刷新）同样影响活动审核的类别下拉框，不只是年级下拉框——不重复立案。
- `integration-test/STATUS.md` 已同步（缺陷表第 20/21 行，§4 追加记录）。

## 10. 实施完成

六个阶段全部完成并实测通过，`04-DataViewOptimization.md` 计划关闭。三个目标页面（审核中心/申诉处理/活动审核）已具备统一的筛选/排序/URL 同步/分页规范，共用详情工作台框架，接入了此前从未展示过的真实数据，并已部署到共享测试服。已知遗留（均已在文档中记录，非本轮范围）：§2.5 排除的其它列表页/报表页迁移；已记录未修的 011 号字典语言切换问题；学生端提案时间线的真实数据接线（issue 016 footer 已注明）。

**2026-08-11 追加** — `Case.subjectId` 种子数据缺口（上面本节原先记录的"已知遗留"之一）已修复，不再遗留：`integration-test/mock-data.ts`/`seed.ts` 补上了 `SeedServiceRecord.key`→真实 id 的跨引用机制，5 条种子案件全部指向真实服务记录；顺带修了一个相邻缺口（`SELF` 来源记录种子从未发送 `supervisorKind`，issue 03 上线后本会导致全新播种失败）。已播种的共享测试服数据也直接修复（新建 1 条真实服务记录 + 对 5 条既有 `case_record` 行做 `UPDATE`），并通过 Playwright 实测确认"关联对象"和"相关材料"正向路径均正确显示真实数据。详见 `Docs/issues/018-*.md`。

**2026-08-11 追加 — §2.3 D5 与 §291 记录的卡片决定按钮布局已被后续变更取代。** `05-SearchFilterSortConsistency.md` §5（该文件在做筛选/排序一致性实测时，发现三页卡片右侧的决定按钮组在移动端把标题/正文挤到逐词换行，用户就此扩大范围）移除了本节原先描述的"卡片/行内决定按钮与工作台底部按钮并存"设计——三页卡片现在只保留一个 `MoreHorizontal` "..." 触发器（替代原"查看详情"文字按钮），决定操作只在 `DetailWorkbench` 底部保留一份。`DetailWorkbench.tsx` 也新增了 `closeButton` prop（`'always' | 'none' | 'mobileOnly'`），头部 `X` 在审核中心/申诉处理两页已完全移除、改为底部"取消"按钮；活动审核因决定按钮数（4个）+"取消"在移动端凑不出整齐网格，保留移动端 `X`、仅桌面端换成"取消"。本文件 §2.3/§9 记录的"D5：行/卡片可点击 + 明确查看详情按钮"这一决定本身**未被推翻**（整行/卡片仍可点击打开工作台，`MoreHorizontal` 按钮承担的正是原"查看详情"按钮同样的可访问入口角色，只是视觉从文字+图标简化为纯图标）——变化的只是卡片是否**额外**平铺决定按钮，与决定按钮本身在工作台底部的校验策略（§2.4）无关，§2.4 的决定继续有效。详见 `05-SearchFilterSortConsistency.md` §5 完整方案对比与实施记录，审核中心桌面表格（`hidden lg:block`）的"操作"列未受影响。
