import type { Slot } from '../../harness/slots.js';
import type { FlowContext } from '../../harness/types.js';
import { createDraft, readLatestRecord, uploadEvidenceAndSubmit } from '../../steps/serviceRecord.js';

/**
 * Files one submitted record against the run's project.
 *
 * Shared by the branch phases, which each need a record of their own: a record
 * that has been approved cannot then be rejected, so demonstrating three
 * outcomes means three records. The slot and the evidence file are taken by
 * index from what preflight reserved, so the branches cannot collide with each
 * other's time windows or upload the same picture three times.
 */
export async function fileRecordForBranch(
  ctx: FlowContext, index: number, descriptionZh: string,
): Promise<{ recordNumber: string; minutes: number }> {
  const page = await ctx.as('student');
  const title = ctx.state.projectTitle as string;
  const slot: Slot = (ctx.state.slots as Slot[])[index];
  const evidence = (ctx.state.evidenceFiles as string[])[index];

  await createDraft(page, ctx.config.baseUrl, {
    projectTitle: title,
    date: slot.date,
    startHHMM: slot.startHHMM,
    endHHMM: slot.endHHMM,
    description: descriptionZh,
    evidenceFile: evidence,
  });
  await uploadEvidenceAndSubmit(page, ctx.config.baseUrl, title, evidence);
  const record = await readLatestRecord(ctx.api);
  ctx.narrator.note(`record: ${record.recordNumber} (${slot.date} ${slot.startHHMM}-${slot.endHHMM})`);
  return { recordNumber: record.recordNumber, minutes: record.durationMinutes };
}
