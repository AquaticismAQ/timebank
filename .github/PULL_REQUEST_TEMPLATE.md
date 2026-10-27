<!--
Title format · 标题格式：`type: short summary` (lowercase after the colon · 冒号后小写开头)
  e.g. `fix: show the right grade on the student list`
       `docs: fix a typo in the activation guide`
  type = feat | fix | docs | test | refactor | chore — CI checks this · CI 会自动检查

Not sure about something? Open the PR anyway and ask here — reviewers will help.
有不确定的地方？照样提交，在这里提问，维护者会帮你。
-->

## What and why · 改了什么、为什么

<!-- A few sentences is enough. If there is an issue, add `Closes #12`.
     几句话即可。如有对应 Issue，写上 `Closes #12`。 -->

## How I checked it · 我是怎么验证的

<!-- Tick what you did. A screenshot helps a lot. · 勾选你做过的，附截图会很有帮助。 -->

- [ ] Docs only, nothing to run · 只改了文档，无需运行
- [ ] Frontend compiles · 前端能编译：`cd frontend && npx -y -p typescript tsc --noEmit -p tsconfig.json && pnpm build`
- [ ] Backend tests pass · 后端测试通过：`cd backend && mvn test` (Java 21)
- [ ] Tried it in the browser · 在浏览器里试过 — what I did and saw · 我的操作和看到的结果：

## Checklist · 自查

- [ ] No real names, school details, phone numbers or passwords — in code, docs, test data or screenshots. Made-up examples only.
      代码、文档、测试数据和截图里都没有真实姓名、学校信息、电话或密码，示例一律虚构。
- [ ] If I edited a doc that has a Chinese/English twin (`_CN.md`), I updated both — or said above why not.
      如果改了有中英两版（`_CN.md`）的文档，两版都已更新，或已在上面说明原因。
- [ ] Time coins are still a school record, not money — no "currency", "token", buying or cashing out.
      时间币仍是校内记录，不是钱——不出现"货币""代币"、购买或兑现。
- [ ] No AI-tool credit line (like `Co-authored-by: Claude`) in my commits or this description.
      提交信息和本描述中没有 AI 工具署名行（如 `Co-authored-by: Claude`）。

<details>
<summary>Risky changes — maintainers fill this in · 高风险改动（由维护者填写）</summary>

Only for changes to the database, login/permissions, or the time-coin ledger. Contributors can leave this empty.
仅适用于数据库、登录/权限或时间币账本相关的改动，贡献者可以留空。

- Database migration added? · 是否新增数据库迁移？
- `Docs/integration/APIContract.md` changed, and updated in this PR? · 接口契约是否变化并已在本 PR 中更新？
- How to undo it if it goes wrong · 出问题时如何回退：

</details>
