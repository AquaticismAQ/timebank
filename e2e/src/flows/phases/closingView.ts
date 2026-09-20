import { assertThat } from '../../harness/assert.js';
import type { Phase } from '../../harness/types.js';

/** Hours as the portal prints them: one decimal place, trailing `.0` dropped. */
function formatHours(minutes: number): string {
  const hours = minutes / 60;
  return Number.isInteger(hours) ? String(hours) : hours.toFixed(1);
}

/**
 * The last thing on screen: the student's own home page, showing the hours and
 * time coins they now have.
 *
 * The demo ends here rather than on an administrator's dashboard because this is
 * the school's actual question — not "was a decision recorded" but "does the
 * student get credit for what they did".
 */
export const closingView: Phase = {
  gate: 'core',
  titleZh: '学生看到自己的服务时长与时间币',
  async run(ctx) {
    const page = await ctx.as('student');
    await page.goto(`${ctx.config.baseUrl}/student/home`, { waitUntil: 'domcontentloaded' });

    const minutes = ctx.state.minutesAfter as number;
    const coins = ctx.state.balanceAfter as number;
    const hours = formatHours(minutes);

    // Waited for, not asserted on sight. The sidebar's label renders immediately
    // with `--h` in place of the figure, so anything that merely checks the label
    // is there is reading a page that is still loading — which is how this first
    // failed against a home page that went on to show exactly the right number.
    await page.getByText(`${hours}h`).first().waitFor({ state: 'visible', timeout: 20_000 });
    const body = await page.locator('body').innerText();
    // Compared as the page prints it: one decimal, trailing zero dropped.
    assertThat(body.includes(`${hours}h`), `首页应当显示更新后的服务时长 ${hours}h`);
    // `coins` is in minimum accounting units; the page shows whole coins.
    ctx.narrator.result(`这名学生现在有 ${hours} 小时服务时长和 ${coins / 100} 个时间币`);
  },
};
