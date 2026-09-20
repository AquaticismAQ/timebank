# 搜索/筛选/排序控件的跨页一致性 — 澄清与实施计划

## 0. 定位与范围

本文件基于当前实现（逐项核实到 `file:line`）梳理五个管理端列表页——**项目管理**（`ProjectManagement.tsx`，用户指定的目标样式）、**审核中心**（`AuditCenter.tsx`）、**申诉处理**（`CaseReview.tsx`）、**活动审核**（`ProposalReview.tsx`）、**学生管理**（`StudentList.tsx`）——各自的搜索框、筛选下拉框和排序控件的布局与交互差异，把后三个页面（审核中心/申诉处理/活动审核，在 `04-DataViewOptimization.md` 里刚做过一轮统一）和学生管理页统一到项目管理页当前的设计语言上，并顺带修正项目管理页自己在移动端已经暴露的两个布局问题。

不改变任何筛选/排序的**后端能力**——`04-DataViewOptimization.md` 已经把审核中心/申诉处理/活动审核三页的服务端筛选、排序、URL 同步能力建齐，本文件只调整这些能力在页面上的**视觉呈现和交互组件**（下拉框 vs 按钮、单行 vs 多行、flex vs grid）。学生管理页的筛选/排序后端能力本身没有变化（年级/班级筛选和姓名排序早已是服务端的，年级/时长/时间币排序保持仅本页客户端排序，见 §1.3）。

沿用 `02`/`03`/`04` 的阅读约定：**Finding**（已核实的缺口，无需选择）、**Settled**（可直接实施，做法明确）、**已确认/⚠️ 需要确认**（原计划或用户描述本身有歧义，给出方案对比和建议，请用户确认）。

## 1. 已核实的当前实现

### 1.1 项目管理页（目标样式）

`ProjectManagement.tsx`：

- **搜索+筛选行**（`:515`）：`<div className="flex gap-2 flex-wrap">`，内含搜索框（`flex-1 min-w-48`，`:516`）、状态下拉（`:521`）、类别下拉（`:526`）。桌面端因为容器足够宽，三者天然排在一行；**移动端搜索框本身就接近或超过屏幕宽度（`min-w-48` = 12rem = 192px，加上两侧内边距后在 375–390px 的手机屏幕上已经占满大半行），两个下拉框被挤到第二行，且由于是 `flex-wrap` 而不是 grid，两个下拉框各自按内容宽度排列，不保证等宽对齐、不保证两个都在同一行内（如果内容更长，可能进一步换到第三行）**——这正是用户描述的"filter dropdowns in second rows"问题。
- **排序行**（`:534`）：`<div className="flex gap-2">`——**没有 `flex-wrap`**。三个排序按钮（`:535-546`）在桌面端正常排成一行；**移动端由于没有换行，三个按钮要么被压缩变形，要么超出容器宽度导致横向溢出/裁切**——这是用户描述的排序按钮"需要修复成 Nx1 grid"的问题。
- 排序交互（`handleSortClick`，`:379-385`）：点击某个排序字段按钮——若已经是当前排序字段，切换升降序；若是新字段，切到该字段并默认降序。当前激活的按钮高亮（`bg-blue-100 ...`），并在文字后追加 `↓`/`↑`。**这个交互模式本身（一个字段一个按钮，点击态自身承担方向切换）就是用户要求其它页面对齐的目标交互**，不是需要修的问题。
- 筛选下拉默认选项文案：`{t('admin.projects.allStatuses')}`（"All Statuses"/全部状态）、`{t('admin.projects.allCategories')}`（"All Categories"/全部类别）——每个下拉框都有自己领域专属的"全部 XX"文案，不是一个通用的"全部"。

### 1.2 审核中心 / 申诉处理 / 活动审核（`04-DataViewOptimization.md` 刚统一过一轮）

三页结构已经和项目管理页一致，都是"搜索+筛选一行、排序控件另起一行"（`AuditCenter.tsx:194` 筛选行、`:217` 排序行；`CaseReview.tsx`/`ProposalReview.tsx` 同构），筛选行同样是 `flex flex-wrap gap-2`，在移动端有和项目管理页完全相同的"下拉框挤到第二行、不保证对齐"问题。

两个真实差异：

1. **排序控件形态不同**：三页当前是"一个字段下拉框 + 一个升降箭头按钮"（`AuditCenter.tsx:218-229`，`CaseReview.tsx`/`ProposalReview.tsx` 同构），而不是项目管理页"每个排序选项一个按钮"的形态。这是 `04-DataViewOptimization.md` §2.1.4 当时的决定（读作原计划文字"排序使用一个'排序字段'下拉框和一个升降序控件，而非由页面各自发明按钮"）——**本次用户的要求实际上是推翻那条决定，改成项目管理页的按钮形态**，理由是项目管理页才是"prefer design"。
2. **筛选下拉默认文案是通用"全部"**：`CaseReview.tsx` 的类型/状态下拉框（`:96,101`）和 `ProposalReview.tsx` 的类别/状态下拉框（`:107,112`）都写的是 `{t('common.all')}`（"All"/全部），不像项目管理页/学生管理页那样每个下拉框各自有"全部状态""全部类别"这样领域专属的文案。

### 1.3 学生管理页

`StudentList.tsx`：

- **筛选行**（`:292`）：`<div className="flex flex-wrap gap-2">`，搜索框 + 年级下拉 + 班级下拉 + 状态下拉，共 3 个筛选下拉框，移动端有同样的换行/不对齐问题。
- **排序**：**没有独立的排序控件区域**——排序完全通过桌面表格的可点击表头实现（`SortTh` 组件，`:246-254`，点击表头文字，激活列在表头显示 `↓`/`↑`）。**移动端卡片视图完全没有任何排序入口**——`ChevronDown`/`ChevronUp`（`:576`）是卡片展开/收起的图标，与排序无关。这是用户描述"toggle by table header, need to update to the same style"的确切所指：不只是形态要统一，移动端目前是**排序功能完全缺失**，不只是布局问题。
- 4 个可排序字段：姓名（`name`，服务端真实排序，wire 字段是 `displayName`）、年级（`gradeLevel`，仅本页客户端排序）、已完成时长（`completedHours`，仅本页客户端排序）、时间币余额（`timeCoinBalance`，仅本页客户端排序）——比其它四页的 3 个排序选项多一个。
- 筛选下拉默认文案已经是各自专属的："全部年级"（`admin.students.allGrades`）、"全部班级"（`admin.students.allClasses`）、"全部状态"（`admin.students.allStatuses`）——**学生管理页本身在文案这一点上已经是对的**，不需要按 §1.4 那一条修。

### 1.4 "全部"文案的既有约定（跨页核实）

| 页面 | 下拉框 | 当前文案 key | 当前文案 |
| --- | --- | --- | --- |
| 项目管理 | 状态 | `admin.projects.allStatuses` | All Statuses / 全部状态 |
| 项目管理 | 类别 | `admin.projects.allCategories` | All Categories / 全部类别 |
| 学生管理 | 年级 | `admin.students.allGrades` | All Grades / 全部年级 |
| 学生管理 | 班级 | `admin.students.allClasses` | All Classes / 全部班级 |
| 学生管理 | 状态 | `admin.students.allStatuses` | All Statuses / 全部状态 |
| 审核中心 | 年级 | `admin.audit.allGrades` | All Grades / 全部年级 |
| 申诉处理 | 类型 | `common.all` | **All / 全部**（不专属） |
| 申诉处理 | 状态 | `common.all` | **All / 全部**（不专属） |
| 活动审核 | 类别 | `common.all` | **All / 全部**（不专属） |
| 活动审核 | 状态 | `common.all` | **All / 全部**（不专属） |

七比二——"每个下拉框专属'全部 XX'文案"是已经确立、被大多数页面遵守的约定，申诉处理和活动审核两页（恰好是 `04-DataViewOptimization.md` 那一轮新建的下拉框）是例外，不是新约定要建立，是两个例外要修正。

## 2. 已确认/需要确认的决定

### 2.1 排序控件形态：下拉框+方向按钮 → 逐选项按钮（已确认，用户直接指定）

**决定：审核中心/申诉处理/活动审核的排序控件从"一个下拉框+一个方向按钮"改为项目管理页的"每个排序字段一个按钮，点击当前激活按钮切换方向"。** 这是用户在本次请求里直接给出的方向，不是一个需要权衡的分叉——`04-DataViewOptimization.md` §2.1.4 当时选下拉框形态，是因为原计划文字本身写的是"排序使用一个'排序字段'下拉框"；现在有了明确的更高优先级参照（项目管理页），按钮形态胜出。三页各自的 `SortKey` 类型、`setSort` 调用、`daysWaiting desc` 默认排序等底层逻辑不变，只是把 UI 从 `<select>` 换成按钮组，复用 `handleSortClick` 这套项目管理页已经验证过的交互（点相同字段切方向、点不同字段切字段+默认降序）。

### 2.2 学生管理页：表头点击排序 → 独立按钮排序区

**方案 A——完全替换。** 移除桌面表头的点击排序（`SortTh` 组件退化成普通 `<th>`，不再可点击、不再显示箭头），在筛选行下方新增一行排序按钮（4 个：姓名/年级/时长/时间币），桌面和移动端共用同一套按钮，与其它四页完全一致。表头本身仍然可以显示当前排序方向的静态箭头（不可点击，只读指示），或者干脆不显示。

**方案 B——两者并存。** 保留桌面表头点击排序作为额外的"高级用户"捷径，同时新增按钮排序区（移动端靠按钮，桌面端按钮和表头点击都能触发，状态互相同步）。多一条交互路径，多一处要维护一致（例如表头箭头和按钮高亮必须显示同一个 `sortKey`/`sortDesc`），但对已经习惯点表头的管理员没有引入变化。

**建议：方案 A。** 用户原话"need to update to the same style"读作"改成一样"，不是"追加一样"；两条路径触发同一个状态在功能上没问题，但会让表头看起来像还能点、实际上和按钮做同一件事，增加认知负担而非降低。表头保留静态方向指示（不可点击）作为向已排序状态的视觉提示即可。

**已确认：方案 A——完全替换，表头保留静态方向指示。**

### 2.3 排序选项数量较多时的 Nx1 grid 是否强制单行

学生管理页有 4 个排序选项（其它四页都是 3 个）。"Nx1 grid" 若严格按 4 列单行执行，在 375–390px 的手机屏幕上每个按钮平均可用宽度约为 (屏幕宽度 − 左右内边距 − 3 个 gap) ÷ 4 ≈ 70–80px，中文"时间币"或英文"Time Coins"这类稍长标签，配合当前激活态还要追加"↓"/"↑"，存在换行或裁切风险。

**方案 A——4 个排序选项也强制单行 4 列（Nx1，N=4）。** 与其它页面完全统一的规则（"排序区永远是 N 列单行网格，N=选项数"），不为学生管理页开特例。若在实测中出现文字挤压，后续可以单独缩短这一页的按钮文案（例如"时长"而不是"完成时长"）来解决，不改变布局规则本身。

**方案 B——排序选项 ≤3 时用 Nx1（单行 N 列），>3 时允许换行为 2 列多行（如 2x2）。** 用一个简单阈值区分"选项少、单行合理"和"选项多、单行会挤"的情况，学生管理页用 `grid-cols-2`（2 行 2 列）而不是 `grid-cols-4`。其余四页因为都恰好是 3 个选项，`grid-cols-3` 单行不受影响。

**建议：方案 A，但缩短学生管理页排序按钮的文案作为配套**（例如"时长"/"Hours"而不是"完成时长"/"Completed Hours"，"时间币"/"Coins" 而不是"时间币余额"/"Time Coin Balance"——排序按钮本来就是快捷操作入口，不是字段全称展示位，缩短文案不影响可理解性）。选择 A 而不是 B 是为了让"排序区是 Nx1 网格"成为一条不需要记住例外的规则，长期跨页面维护成本更低；一旦有第六个页面将来出现 5 个排序选项，B 方案需要重新定阈值，A 方案不需要。

**已确认：方案 A——4 列单行 + 缩短按钮文案。**

### 2.4 搜索+筛选行在移动端的断点选择

要让"移动端搜索独占一行、筛选下拉框们在第二行组成 Nx1 网格；桌面端搜索和筛选合并回一行"这套响应式行为成立，需要一个断点。

**方案 A——`sm:`（640px）。** 和项目管理页编辑弹窗、学生管理页编辑弹窗等既有弹层的"移动端全屏/桌面端居中卡片"断点一致（`04-DataViewOptimization.md` D4 当时也选的 `sm:`）。缺点：审核中心/申诉处理/活动审核/学生管理四页的表格与卡片视图切换用的是 `lg:`（1024px）断点（`hidden lg:block`/`lg:hidden`），640–1023px 之间（例如 iPad 竖屏）会出现"内容还在显示移动端卡片，但筛选行已经合并成桌面一行"的不一致状态。
**方案 B——`lg:`（1024px）。** 和四页本已使用的表格/卡片切换断点保持同一个断点概念——"lg 以下＝移动模式（卡片+筛选网格），lg 以上＝桌面模式（表格+筛选合并一行）"，同一页面内的所有响应式行为在同一个尺寸翻转，不会出现 640–1023px 之间部分内容"已经桌面化"、部分还"移动端"的错位。项目管理页本身没有表格/卡片切换（永远是卡片网格），但为了让**五个页面的筛选行断点彼此一致**（这正是本文件的目的），项目管理页的筛选行也采用 `lg:`，不影响它自己的卡片网格显示（后者用的是独立的 `xl:grid-cols-2`，互不相关）。

**建议：方案 B。** 四页已有 `lg:` 断点先例，跟随它比引入第二个断点概念更省心；项目管理页虽然自己没有内容断点，但采用 `lg:` 不会产生任何负面影响，换来五页统一。

**已确认：方案 B——`lg:`（1024px）。**

### 2.5 排序按钮区是否也要区分移动/桌面断点

与 §2.4 的筛选行不同——排序按钮区不需要在桌面端"合并"进别的行，它本来就独立成行。

**已确认（Settled，非需要选择的分叉）：排序按钮区始终是 `grid grid-cols-N`，不分断点。** 网格在桌面端只是让每个按钮分到更宽的格子（视觉上更疏朗，不会挤压或变形），没有必要像筛选行那样在桌面端切回 `flex`。这样排序区的实现比筛选行更简单——一处 class，不用写响应式条件。

## 3. 实施顺序

1. **项目管理页自身的两处移动端修复**：筛选行拆成"搜索独占一行（`lg:` 以下）+ 状态/类别两个下拉框组成 `grid grid-cols-2`（`lg:` 以下），`lg:` 以上恢复合并一行"；排序行从 `flex gap-2` 改为 `grid grid-cols-3 gap-2`（不分断点，见 §2.5）。这两处改完，项目管理页自己也符合本文件定的规则，可以作为其余四页对照的基准截图。
2. **审核中心 / 申诉处理 / 活动审核**：
   - 筛选行按 §2.4 决定的断点，拆成"搜索独占一行 + 筛选控件（下拉框和/或切换按钮）组成 Nx1 网格"，桌面端合并回一行。审核中心的"仅看异常"切换按钮算作筛选行的一个网格项（N=2：年级+异常）；申诉处理 N=2（类型+状态）；活动审核 N=2（类别+状态）。
   - 排序控件从"下拉框+方向按钮"换成"每个字段一个按钮"（复用项目管理页 `handleSortClick` 的交互），`grid grid-cols-3`（三页都是 3 个排序字段）。
   - 申诉处理、活动审核的筛选下拉默认文案从 `common.all` 换成各自专属的"全部类型"/"全部状态"（申诉处理）、"全部类别"/"全部状态"（活动审核），新增对应 i18n key，格式对齐 `admin.projects.allStatuses` 等既有 key 的命名方式。
3. **学生管理页**：按 §2.2 决定（方案 A：替换）移除表头点击排序交互，新增独立排序按钮行（`grid grid-cols-4`，按 §2.3 决定缩短按钮文案），筛选行按 §2.4 断点重排。
4. **中英文 i18n 双语核对**：新增的"全部类型"/"全部类别"等 key 以及学生管理页缩短后的排序按钮文案，中英文双语都要有对应词条，`en.json`/`zh.json` 同步添加。
5. **端到端实测（最后阶段）**：部署前端到共享测试服，Playwright 走查五个页面在移动视口（390×844）和桌面视口下的筛选行、排序行布局与交互，确认 Nx1 网格不换行、不溢出、不裁切文字，排序点击行为（切字段/切方向）与线上数据一致；双语切换下逐页确认新文案正确显示；发现的任何真实缺陷按仓库既有约定写成 `Docs/issues/0NN-*.md` 并同步 `integration-test/STATUS.md`。

## 4. 决策索引

| # | 章节 | 问题 | 建议方案 | 状态 |
|---|------|------|----------|------|
| 1 | §2.1 | 三页排序控件形态：下拉框+方向按钮 vs 逐选项按钮 | 逐选项按钮（用户直接指定） | 已确认 |
| 2 | §2.2 | 学生管理页排序交互：完全替换表头点击 vs 两者并存 | 方案 A（完全替换） | 已确认 |
| 3 | §2.3 | 学生管理页 4 个排序选项：强制 Nx1 单行 vs 超过 3 个换行为 2x2 | 方案 A（强制单行 + 缩短按钮文案） | 已确认 |
| 4 | §2.4 | 筛选行响应式断点：`sm:` vs `lg:` | 方案 B（`lg:`，与既有表格/卡片切换断点一致） | 已确认 |
| 5 | §2.5 | 排序按钮区是否需要断点区分 | 不需要，始终 `grid grid-cols-N` | 已确认（非分叉） |

全部五项已确认，进入 §3 的实施。

## 5. 追加：详情卡片操作按钮精简与模态框关闭方式统一（用户实测反馈驱动）

§3 步骤 5 的实测（Playwright，390×844 移动视口）发现活动审核卡片标题/正文在移动端被压缩成逐词换行——根因不是本文件的筛选/排序改动，而是卡片右侧 `shrink-0` 的决定按钮组（审核中心 3 个、申诉处理 3 个、活动审核 4 个，均带图标+文字）在窄屏下占用了大半宽度，把 `min-w-0` 的标题/正文列挤到接近 0。用户就此扩大范围：三页的卡片已经通过点击整行/"查看详情"打开 `DetailWorkbench`（`04-DataViewOptimization.md` §2.3 D5），决定操作在弹层底部操作栏里本来就有一份完整的，卡片上的这一份是纯重复，应当移除，仿照项目管理页卡片（`ProjectManagement.tsx:598-603`，右上角一个 `MoreHorizontal` "..." 触发器，无内联决定按钮）。同时用户指出弹层右上角的 `X` 关闭按钮应改为底部操作栏里的"取消"按钮（与其它已存在的"取消/提交"类弹窗一致，如 `ProjectManagement.tsx` 的取消确认弹层）。

### 5.1 卡片精简：Settled（用户直接指定，非分叉）

三页卡片的右侧列（`AuditCenter.tsx` 移动卡片、`CaseReview.tsx`/`ProposalReview.tsx` 的统一卡片）原有的"查看详情"图标文字按钮 + 决定按钮组，整体替换为一个 `MoreHorizontal`（`lucide-react`）图标按钮，位置比照 `ProjectManagement.tsx` 卡片的触发器（标题行右侧、`shrink-0`），点击行为与原"查看详情"按钮相同（`openDetail(id)` + `stopPropagation`）。审核中心桌面表格的"操作"列（`hidden lg:block` 表格，与移动卡片是两套独立 DOM）保留原样未动——那是行列表而非卡片，決定按钮在固定宽度的表格列里没有挤压标题的问题，用户的原话针对的是"card"场景。

### 5.2 模态框关闭方式：按钮数决定是否能加"取消"

`DetailWorkbench.tsx` 原来无条件在头部渲染一个 `X` 关闭按钮（`:47-49`，`onClick={onClose}`），底部 `footer` 完全由调用方注入、不含关闭语义（`canDecide`/`canReview` 为假或状态不可决定时 `footer` 是 `undefined`，此时唯一的关闭方式就是这个 `X` 或点击遮罩层）。

审核中心、申诉处理各自的决定按钮组是 3 个，加一个"取消"凑成 4 个，移动端 `grid-cols-2` 天然形成 2×2；活动审核是 4 个决定按钮，加"取消"会变成 5 个，移动端没有能不换行且不过窄的 2×N 排布——这是本节唯一的真实分叉，其余两页直接照搬 §2.3 已确认的"强制不换行 + 必要时缩短文案"原则并不适用（5 个按钮的文案已经是决定操作的专有名词，不适合再缩短到牺牲可读性）。

**方案 A——活动审核在移动端也加"取消"，5 按钮换行为不规则的 2+2+1 或改用更小字号挤进 2×3。** 与其它两页规则完全一致，但 5 分之 1 的孤按钮或被迫更小的字号，在手机屏幕上比"保留一个大家已经认识的 X"更破坏可用性。

**方案 B——活动审核移动端维持原有的 `X` 关闭，不加"取消"；4 个决定按钮组成 `grid-cols-2`（2×2，本来就是这个数量）；仅桌面端（横向空间充足）改为"取消 + 4 个决定按钮"一行 5 个，此时隐藏 `X`。** 审核中心、申诉处理两页（3 决定按钮）在移动端和桌面端都统一改为"取消 + N 个决定按钮"、完全移除 `X`（3+1=4，2×2 网格不需要特例）。

**建议：方案 B。** 唯一让所有页面在各自按钮数下都落在"整齐网格"而不是"孤按钮或过窄"的方案；代价是活动审核一个页面的关闭方式在移动/桌面两种视口下不完全一致（移动端 `X`、桌面端"取消"），但这个差异本身有清楚的物理原因（横向空间），不是任意选择。

**已确认：方案 B。** 为支持"部分页面全去 `X`、部分页面按断点显隐 `X`"，`DetailWorkbench` 新增 `closeButton?: 'always' | 'none' | 'mobileOnly'`（默认 `'always'`，向后兼容未来可能的其它调用方）：
- 审核中心、申诉处理：`closeButton="none"`；`footer` 改为始终渲染（不再在无权限/不可决定时整体 `undefined`），至少包含一个"取消"按钮，`grid grid-cols-2 gap-3 sm:flex`（有决定按钮时 2×2/桌面一行；只有"取消"时单列/单个 flex 项自然占满宽度）。
- 活动审核：`closeButton="mobileOnly"`（`X` 加 `sm:hidden`，即 `lg:` 断点无关，用的是 `DetailWorkbench` 组件自身既有的 `sm:` 呈现形态断点，见 `04-DataViewOptimization.md` §2.3 D4——不是本文件 §2.4 为筛选行新定的 `lg:`，两者概念不同，不应混用同一个断点常量）；`footer` 用两个响应式互斥的子块（`sm:hidden` 渲染移动端的纯 4 决定按钮 `grid-cols-2`，`hidden sm:flex` 渲染桌面端的"取消 + 4 决定按钮"一行 5 个），而不是用一套 class 覆盖两种按钮集合——因为两种视口下按钮的**数量**不同（4 vs 5），不只是排布变了,单纯改 class 无法表达。

### 5.3 实施记录

**2026-08-11 完成，`pnpm build` 绿，已部署 `tb.example.com` 并 Playwright 实测（移动 390×844 + 桌面 1440×900，三页 × 卡片/模态 = 6 张截图逐一核对）。**
- `DetailWorkbench.tsx` 新增 `closeButton` prop（默认 `'always'`）；发现并修复一处实现疏漏——prop 加了但初版忘记在头部 JSX 里实际读取它，第一次部署后桌面端 `X` 仍然显示，二次修复重新部署后确认消失。
- `AuditCenter.tsx`/`CaseReview.tsx`/`ProposalReview.tsx`：卡片右侧决定按钮组 + "查看详情" 文字按钮，替换为单个 `MoreHorizontal` 触发器；`Eye` 图标 import 移除（不再使用）。
- 三页模态底部按 §5.2 方案 B 重写；`common.cancel` 复用既有 i18n key，未新增翻译。
- 审核中心桌面表格"操作"列的按钮未动（§5.1 已说明原因）。

## Enforcement (added 2026-09-20, issue 138)

This document was written in August and every list built before it complies. The
next one — the student's My Cases — did not, and nobody noticed until a
maintainer did. **That is how a convention decays: not by being overruled, but by
the next screen not knowing it exists**, and a document cannot catch it.

`frontend/scripts/check-list-conventions.mjs` now runs in `frontend/scripts/deploy.sh`,
beside the type check. Every component calling `usePagedApi`/`useUrlSyncedPagedApi`
must offer a search box (`ListSearchInput`) and an empty/error state
(`ListStateBanner`).

It is deliberately narrow — those two are already true of every list, so it locks
in what holds rather than demanding a refactor nobody asked for. Sort keys, page
size and filter layout vary legitimately per screen, and **a rule that fires on
half the codebase gets switched off rather than obeyed.**

Opting out is expected, and has to be argued in the file:

```tsx
// list-conventions: no-search — `/notifications` takes no search parameter
```

An exemption with nothing after the dash fails the same as a missing control, and
every exemption is printed on each run so they stay visible instead of quietly
accumulating. Four exist today, each because the endpoint genuinely has no
`search` parameter or the list is one record's detail in a dialog.
