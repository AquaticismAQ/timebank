import { assertThat } from '../../harness/assert.js';
import { auditActionsSince } from '../../steps/audit.js';
import type { Phase } from '../../harness/types.js';

/**
 * Every mutation this run made left an audit entry.
 *
 * Checked by querying the log, not by reading the code: a `record(...)` call
 * that is present but never reached, or rolled back with its transaction, looks
 * identical in source and is invisible until someone asks the database.
 *
 * `account.service_credit` is the one that matters most. It is the ledger entry
 * behind the balance the approval phase asserted, and it is what makes a coin
 * traceable to the service that earned it.
 */
export const auditTrail: Phase = {
  gate: 'audit',
  titleZh: '核对审计日志',
  async run(ctx) {
    const since = ctx.state.startedAt as Date;
    const actions = await auditActionsSince(ctx.adminApi, since);

    const expected = [
      'project.create',
      'project.publish',
      'enrollment.register',
      'serviceRecord.create',
      'serviceRecord.submit',
      'serviceRecord.reviewTransition',
      'account.service_credit',
    ];
    const missing = expected.filter(action => !actions.includes(action));
    ctx.narrator.note(`audit actions seen: ${[...new Set(actions)].join(', ')}`);
    assertThat(missing.length === 0, `以下操作应当留下审计记录，但没有找到：${missing.join('、')}`);

    // Three decisions were made when both branches ran, and each must be its own
    // entry — reviews are non-overwritable events, so a second decision appends
    // rather than replacing the first.
    const decisions = actions.filter(a => a === 'serviceRecord.reviewTransition').length;
    const expectedDecisions = 1 + (ctx.has('branch:return') ? 1 : 0) + (ctx.has('branch:reject') ? 1 : 0);
    assertThat(decisions >= expectedDecisions,
      `应当有 ${expectedDecisions} 条审核决定的审计记录，实际只有 ${decisions} 条`);

    ctx.narrator.result('每一步操作都在审计日志中留下了记录');
  },
};
