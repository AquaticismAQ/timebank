import {
  RECORD_STATUS, assertEvidenceShown, assertRecordDescription, assertRecordStatus,
  createDraft, editDraftDescription, uploadEvidenceAndSubmit,
} from '../../steps/serviceRecord.js';
import { readLatestRecord } from '../../steps/serviceRecord.js';
import { readProgress } from '../../steps/account.js';
import type { Slot } from '../../harness/slots.js';
import type { Phase } from '../../harness/types.js';

/**
 * The student writes up what they did: draft, edit, attach evidence, submit.
 *
 * The draft-then-edit detour is not padding. A record is a claim about a
 * student's time that a teacher will approve into their graduation requirement,
 * so being able to put it down half-finished and come back is the difference
 * between a form and a usable one — and an edit that silently fails to save
 * looks exactly like one that worked.
 */
export const serviceRecord: Phase = {
  titleZh: '学生填写服务记录并提交',
  async run(ctx) {
    const page = await ctx.as('student');
    const title = ctx.state.projectTitle as string;
    // The baseline for the assertion the whole run exists to make. Taken before
    // anything is filed, so an approval elsewhere cannot be mistaken for ours.
    const before = await readProgress(ctx.api, ctx.state.studentId as string);
    ctx.state.balanceBefore = before.timeCoinBalance;
    ctx.state.minutesBefore = before.completedMinutes;
    ctx.narrator.note(`baseline: ${before.completedMinutes} min approved, ${before.timeCoinBalance} coins`);
    const slot = (ctx.state.slots as Slot[])[0];
    const evidence = (ctx.state.evidenceFiles as string[])[0];
    const finalDescription = '在社区图书馆协助整理少儿阅览区书架，登记归还图书，并引导读者使用借阅机。';

    ctx.narrator.step('新建记录，选择参加过的项目，填写服务日期与起止时间');
    await createDraft(page, ctx.config.baseUrl, {
      projectTitle: title,
      date: slot.date,
      startHHMM: slot.startHHMM,
      endHHMM: slot.endHHMM,
      description: '在社区图书馆帮忙整理书架。',
      evidenceFile: evidence,
    });
    await assertRecordStatus(page, ctx.config.baseUrl, title, RECORD_STATUS.draft);
    ctx.narrator.result('记录已保存为草稿，可以稍后继续填写');

    ctx.narrator.step('重新打开草稿，补充服务内容的细节');
    await editDraftDescription(page, ctx.config.baseUrl, title, finalDescription);
    await assertRecordDescription(page, ctx.config.baseUrl, title, finalDescription);
    ctx.narrator.result('修改已保存');

    ctx.narrator.step('上传佐证材料，提交审核');
    const fileName = await uploadEvidenceAndSubmit(page, ctx.config.baseUrl, title, evidence);
    ctx.state.evidenceFileName = fileName;
    const record = await readLatestRecord(ctx.api);
    ctx.state.recordNumber = record.recordNumber;
    ctx.state.recordMinutes = record.durationMinutes;
    await assertEvidenceShown(page, ctx.config.baseUrl, record.recordNumber, fileName);
    await assertRecordStatus(page, ctx.config.baseUrl, record.recordNumber, RECORD_STATUS.submitted);
    ctx.narrator.note(`record: ${record.recordNumber}`);
    ctx.narrator.result('记录已提交，等待老师审核');
  },
};
