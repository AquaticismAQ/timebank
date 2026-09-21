import { assertThat } from '../../harness/assert.js';
import { readProgress } from '../../steps/account.js';
import { decideOnRecord } from '../../steps/review.js';
import { RECORD_STATUS, assertRecordStatus, recordRow } from '../../steps/serviceRecord.js';
import type { Phase } from '../../harness/types.js';
import { fileRecordForBranch } from './fileRecord.js';

/**
 * A record refused outright.
 *
 * Asserted alongside the approval and the return because the three must be
 * *distinguishable*. A system that recorded every decision as the same thing
 * would satisfy any one of these phases on its own.
 */
export const rejectBranch: Phase = {
  gate: 'branch:reject',
  titleZh: '老师驳回不符合要求的记录',
  async run(ctx) {
    const title = ctx.state.projectTitle as string;
    const before = await readProgress(ctx.api, ctx.state.studentId as string);

    const description = '这条记录用于演示驳回流程。';
    ctx.narrator.step('学生提交一条不符合要求的记录');
    const { recordNumber } = await fileRecordForBranch(ctx, 2, description);

    const adminPage = await ctx.as('admin');
    ctx.narrator.step('老师读过记录、看过材料并另行核实后驳回，并写明理由');
    await decideOnRecord(adminPage, ctx.config.baseUrl, title, recordNumber, 'reject',
      '经与项目负责人核实，该时段学生未参与服务，予以驳回。',
      { expectOnScreen: [description] });

    const studentPage = await ctx.as('student');
    await assertRecordStatus(studentPage, ctx.config.baseUrl, recordNumber, RECORD_STATUS.rejected);

    const after = await readProgress(ctx.api, ctx.state.studentId as string);
    assertThat(after.timeCoinBalance === before.timeCoinBalance,
      `驳回不应当产生任何时间币入账，但余额从 ${before.timeCoinBalance} 变成了 ${after.timeCoinBalance}`);
    assertThat(after.completedMinutes === before.completedMinutes,
      '驳回不应当计入已认定服务时长');
    ctx.narrator.result('记录已驳回，没有计入服务时长，也没有时间币入账');
  },
};
