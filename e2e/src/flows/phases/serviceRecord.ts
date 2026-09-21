import {
  RECORD_STATUS, assertRecordDetail, assertRecordStatus,
  continueDraftAndSubmit, createDraft, readLatestRecord,
} from '../../steps/serviceRecord.js';
import { readProgress } from '../../steps/account.js';
import type { Slot } from '../../harness/slots.js';
import type { Phase } from '../../harness/types.js';

/**
 * The student writes up what they did: draft it, come back to it, attach
 * evidence, submit.
 *
 * Two visits to the form, not three. Being able to put a record down
 * half-finished and pick it up later is worth showing — it is the difference
 * between a form and a usable one — but showing the same screen a third time
 * reads as the script being stuck rather than as a student working.
 */
export const serviceRecord: Phase = {
  titleZh: '学生填写服务记录并提交',
  async run(ctx) {
    const page = await ctx.as('student');
    const title = ctx.state.projectTitle as string;
    const slot = (ctx.state.slots as Slot[])[0];
    const evidence = (ctx.state.evidenceFiles as string[])[0];
    const firstDraft = '在社区图书馆帮忙整理书架。';
    const finalDescription = '在社区图书馆协助整理少儿阅览区书架，登记归还图书，并引导读者使用借阅机。';

    // The baseline for the assertion the whole run exists to make. Taken before
    // anything is filed, so an approval elsewhere cannot be mistaken for ours.
    const before = await readProgress(ctx.api, ctx.state.studentId as string);
    ctx.state.balanceBefore = before.timeCoinBalance;
    ctx.state.minutesBefore = before.completedMinutes;
    ctx.narrator.note(`baseline: ${before.completedMinutes} min approved, ${before.timeCoinBalance} coins`);

    ctx.narrator.step('新建记录，选择参加过的项目，填写服务日期与起止时间');
    await createDraft(page, ctx.config.baseUrl, {
      projectTitle: title,
      date: slot.date,
      startHHMM: slot.startHHMM,
      endHHMM: slot.endHHMM,
      description: firstDraft,
      evidenceFile: evidence,
    });
    await assertRecordStatus(page, ctx.config.baseUrl, title, RECORD_STATUS.draft);
    ctx.narrator.result('记录已保存为草稿，可以稍后继续填写');

    ctx.narrator.step('回到草稿，补充细节并上传佐证材料，然后提交');
    const fileName = await continueDraftAndSubmit(page, ctx.config.baseUrl, title, {
      savedDescription: firstDraft,
      finalDescription,
      evidenceFile: evidence,
    });

    const record = await readLatestRecord(ctx.api);
    ctx.state.recordNumber = record.recordNumber;
    ctx.state.recordMinutes = record.durationMinutes;
    ctx.state.evidenceFileName = fileName;
    // Handed to the approval phase so the teacher can be shown reading the
    // student's own words before judging the photograph against them.
    ctx.state.recordDescription = finalDescription;
    await assertRecordStatus(page, ctx.config.baseUrl, record.recordNumber, RECORD_STATUS.submitted);
    await assertRecordDetail(page, ctx.config.baseUrl, record.recordNumber,
      { description: finalDescription, fileName });
    ctx.narrator.result('记录已提交，等待老师审核');
  },
};
