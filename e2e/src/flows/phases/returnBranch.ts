import { assertThat } from '../../harness/assert.js';
import { readProgress } from '../../steps/account.js';
import { decideOnRecord } from '../../steps/review.js';
import { RECORD_STATUS, assertRecordStatus, recordRow } from '../../steps/serviceRecord.js';
import type { Phase } from '../../harness/types.js';
import { fileRecordForBranch } from './fileRecord.js';

/**
 * A record sent back for more information.
 *
 * What distinguishes a return from a rejection is that the student can act on
 * it, so that is what this asserts: the record is editable again. A UI that
 * showed 退回 but left the record locked would look entirely correct from the
 * teacher's seat, and would quietly cost the student their hours.
 *
 * It also asserts **no** coins moved. A return is not a decision about whether
 * the service happened, so crediting anything would be wrong.
 */
export const returnBranch: Phase = {
  gate: 'branch:return',
  titleZh: '老师退回记录，学生可以补充后重新提交',
  async run(ctx) {
    const title = ctx.state.projectTitle as string;
    const before = await readProgress(ctx.api, ctx.state.studentId as string);

    const description = '在社区图书馆协助本周的图书清点工作。';
    ctx.narrator.step('学生再提交一条服务记录');
    const { recordNumber } = await fileRecordForBranch(ctx, 1, description);

    const adminPage = await ctx.as('admin');
    ctx.narrator.step('老师读过记录并打开佐证材料，认为材料不足，退回并说明原因');
    await decideOnRecord(adminPage, ctx.config.baseUrl, title, recordNumber, 'return',
      '佐证材料看不清签字，请补充一张清晰的确认函后重新提交。',
      { expectOnScreen: [description] });

    const studentPage = await ctx.as('student');
    await assertRecordStatus(studentPage, ctx.config.baseUrl, recordNumber, RECORD_STATUS.returned);
    ctx.narrator.step('学生端可以重新编辑这条记录');
    const row = recordRow(studentPage, recordNumber);
    await row.waitFor({ state: 'visible', timeout: 15_000 });
    // 修改 since issue 142 renamed it. It had been 补充重提, and before that the
    // draft's own 编辑 — this assertion went on matching an old word for eight
    // days without anyone noticing, because the project flow's return branch
    // only runs under `verify` and nothing had run it since the rename. All
    // three are accepted: what is being asserted is that *a* way back exists,
    // not what this month's word for it is.
    const canEdit = await row.getByRole('button', { name: /修改|编辑|补充重提/ }).count();
    assertThat(canEdit > 0, '被退回的记录应当可以重新编辑并再次提交——这正是退回与驳回的区别');

    const after = await readProgress(ctx.api, ctx.state.studentId as string);
    assertThat(after.timeCoinBalance === before.timeCoinBalance,
      `退回不应当产生任何时间币入账，但余额从 ${before.timeCoinBalance} 变成了 ${after.timeCoinBalance}`);
    ctx.narrator.result('记录已退回，学生可以补充材料后重新提交，时间币没有入账');
  },
};
