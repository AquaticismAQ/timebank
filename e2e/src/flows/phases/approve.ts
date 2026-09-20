import { assertEquals, assertRoseBy } from '../../harness/assert.js';
import { readProgress } from '../../steps/account.js';
import { decideOnRecord, assertRecordLeftTheQueue } from '../../steps/review.js';
import { RECORD_STATUS, assertRecordStatus } from '../../steps/serviceRecord.js';
import type { Phase } from '../../harness/types.js';

/** A time coin is held as 100 minimum accounting units; balances are never floats. */
const UNITS_PER_COIN = 100;

/**
 * The teacher approves, and the student's hours and time coins go up.
 *
 * This is the phase the whole run exists for. Everything before it can pass in a
 * system that credits nobody — the status chip changing to 已通过 says a decision
 * was recorded, not that anything reached the student's account.
 *
 * So the balance is asserted to have risen **by exactly the recognised amount**,
 * not merely to have risen. "Greater than" would pass on a double credit, on a
 * credit from an unrelated approval, and on a rounding bug.
 */
export const approve: Phase = {
  gate: 'core',
  titleZh: '老师审核通过，时间币入账',
  async run(ctx) {
    const recordNumber = ctx.state.recordNumber as string;
    // What the teacher approves is the time the student recorded, not the
    // project's headline figure — a shorter session credits less. Deriving the
    // expectation from the record is the difference between checking the rule
    // and restating an assumption about it.
    const minutes = ctx.state.recordMinutes as number;
    const studentId = ctx.state.studentId as string;

    const adminPage = await ctx.as('admin');
    ctx.narrator.step('老师在审核中心打开这条记录，查看佐证材料后通过');
    await decideOnRecord(adminPage, ctx.config.baseUrl, ctx.state.projectTitle as string, recordNumber, 'approve', '材料齐全，服务属实，予以认定。');
    await assertRecordLeftTheQueue(adminPage, ctx.config.baseUrl, recordNumber);
    ctx.narrator.result('老师已确认通过');

    const studentPage = await ctx.as('student');
    ctx.narrator.step('学生端查看记录状态与时间币账户');
    await assertRecordStatus(studentPage, ctx.config.baseUrl, recordNumber, RECORD_STATUS.approved);

    const after = await readProgress(ctx.api, studentId);
    // Balances are integers in minimum accounting units, never floats
    // (`CLAUDE.md`, domain conventions), so one time coin is 100 units and an
    // hour of recognised service is one coin. Asserting `hours` directly reads as
    // right and is wrong by a factor of a hundred — which is exactly what the
    // first run of this phase reported.
    const expectedUnits = (minutes / 60) * UNITS_PER_COIN;
    assertRoseBy(ctx.state.balanceBefore as number, after.timeCoinBalance, expectedUnits,
      '通过审核后，学生的时间币余额应当按认定时长增加');
    assertRoseBy(ctx.state.minutesBefore as number, after.completedMinutes, minutes,
      '通过审核后，学生的已认定服务时长应当增加');
    ctx.state.balanceAfter = after.timeCoinBalance;
    ctx.state.minutesAfter = after.completedMinutes;
    assertEquals(after.timeCoinBalance - (ctx.state.balanceBefore as number), expectedUnits,
      '入账金额应当与项目认定时长一致');
    ctx.narrator.result(`服务时长与时间币已入账：${minutes / 60} 小时，${expectedUnits / UNITS_PER_COIN} 个时间币`);
  },
};
