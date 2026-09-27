import {
  RECORD_STATUS, assertRecordDetail, assertRecordStatus,
  createAndSubmit, readLatestRecord,
} from '../../steps/serviceRecord.js';
import { readProgress } from '../../steps/account.js';
import type { Slot } from '../../harness/slots.js';
import type { Phase } from '../../harness/types.js';

/**
 * The student writes up what they did, attaches evidence and submits — one pass
 * through the form. Saving a draft and coming back to it is left to the branch
 * phases, which exercise it without an audience; in the demonstration it cost a
 * second trip through the same screen for nothing new on it.
 */
export const serviceRecord: Phase = {
  titleZh: '学生填写服务记录并提交',
  async run(ctx) {
    const page = await ctx.as('student');
    const title = ctx.state.projectTitle as string;
    const slot = (ctx.state.slots as Slot[])[0];
    const evidence = (ctx.state.evidenceFiles as string[])[0];
    const description = '在社区图书馆协助整理少儿阅览区书架，登记归还图书，并引导读者使用借阅机。';

    // The baseline for the assertion the whole run exists to make. Taken before
    // anything is filed, so an approval elsewhere cannot be mistaken for ours.
    const before = await readProgress(ctx.api, ctx.state.studentId as string);
    ctx.state.balanceBefore = before.timeCoinBalance;
    ctx.state.minutesBefore = before.completedMinutes;
    ctx.narrator.note(`baseline: ${before.completedMinutes} min approved, ${before.timeCoinBalance} coins`);

    ctx.narrator.step('新建服务记录，上传佐证材料后提交');
    const fileName = await createAndSubmit(page, ctx.config.baseUrl, {
      projectTitle: title,
      date: slot.date,
      startHHMM: slot.startHHMM,
      endHHMM: slot.endHHMM,
      description,
      evidenceFile: evidence,
    });

    const record = await readLatestRecord(ctx.api);
    ctx.state.recordNumber = record.recordNumber;
    ctx.state.recordMinutes = record.durationMinutes;
    ctx.state.evidenceFileName = fileName;
    // Handed to the approval phase so the teacher can be shown reading the
    // student's own words before judging the photograph against them.
    ctx.state.recordDescription = description;
    await assertRecordStatus(page, ctx.config.baseUrl, record.recordNumber, RECORD_STATUS.submitted);
    await assertRecordDetail(page, ctx.config.baseUrl, record.recordNumber,
      { description, fileName });
    ctx.narrator.result('记录已提交，等待老师审核');
  },
};
