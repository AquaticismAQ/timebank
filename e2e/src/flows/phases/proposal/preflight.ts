import { assertThat } from '../../../harness/assert.js';
import type { Phase } from '../../../harness/types.js';

/**
 * Lighter than the project workflow's preflight, deliberately.
 *
 * A proposal is a plan for an activity that has not happened yet, so this flow
 * files no service record: it needs no free time windows and no evidence files.
 * Checking for them anyway would make the run fail on things it never uses.
 */
export const proposalPreflight: Phase = {
  titleZh: '运行前检查',
  async run(ctx) {
    ctx.narrator.step('检查配置与账号');
    ctx.narrator.note(`server: ${ctx.config.baseUrl}`);
    ctx.narrator.note(`run id: ${ctx.runId}`);

    const adminPage = await ctx.as('admin');
    assertThat(adminPage.url().includes('/admin'), '管理员应当能够登录并进入管理后台');
    const studentPage = await ctx.as('student');
    assertThat(studentPage.url().includes('/student'), '学生应当能够登录并进入学生端');
    ctx.narrator.result('管理员与学生账号均可登录');
  },
};
