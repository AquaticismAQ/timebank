import type { Phase } from '../../../harness/types.js';

/**
 * Lighter than the project workflow's preflight, deliberately.
 *
 * A proposal is a plan for an activity that has not happened yet, so this flow
 * files no service record: it needs no free time windows and no evidence files.
 * Checking for them anyway would make the run fail on things it never uses.
 *
 * Unannounced, like the project one. The accounts are already proven — the
 * runner signs both in over the API before the first phase — so each role signs
 * in on screen the first time the demonstration actually uses it.
 */
export const proposalPreflight: Phase = {
  titleZh: '运行前检查',
  silent: true,
  async run(ctx) {
    ctx.narrator.note(`server: ${ctx.config.baseUrl}`);
    ctx.narrator.note(`run id: ${ctx.runId}`);
  },
};
