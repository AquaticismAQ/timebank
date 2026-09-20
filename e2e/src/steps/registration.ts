import type { Page } from 'playwright';
import { assertContains, assertThat } from '../harness/assert.js';
import { tap, type, visit } from '../harness/stage.js';
import { findProjectCard, waitForSingleResult } from './project.js';

/**
 * The student's side: find the project in the Project Center and register.
 *
 * Found by searching for the run id rather than scrolling: the Project Center is
 * paginated and pinned to the current academic year (issue 112), so an `[AUTO]`
 * project on a busy day would otherwise be off the end of the first page.
 */
export async function registerForProject(page: Page, baseUrl: string, title: string): Promise<void> {
  await visit(page, `${baseUrl}/student/projects`, '项目中心');
  const search = page.getByPlaceholder('搜索项目、组织、地点...');
  await type(search, '搜索框', title);
  await search.press('Enter');

  await waitForSingleResult(page, title);
  const card = studentCard(page, title);
  await tap(card.getByRole('button', { name: '立即报名' }), '立即报名');
  // The button becomes the registered badge in place, so its arrival is the
  // signal the registration landed — a toast would race its own dismissal.
  await card.getByText('已报名').waitFor({ state: 'visible', timeout: 15_000 });
}

export async function assertStudentSeesRegistered(page: Page, baseUrl: string, title: string): Promise<void> {
  await visit(page, `${baseUrl}/student/projects`, '项目中心');
  const search = page.getByPlaceholder('搜索项目、组织、地点...');
  await type(search, '搜索框', title);
  await search.press('Enter');
  await waitForSingleResult(page, title);
  const card = studentCard(page, title);
  assertContains(await card.innerText(), '已报名', `学生端应当显示已报名：${title}`);
}

/**
 * The school's side: open the project's registrant list.
 *
 * It does **not** click a confirm button, because there is nothing to confirm.
 * `EnrollmentServiceImpl:111` confirms a registration outright while places
 * remain and only waitlists once the project is full — so a manual confirm
 * exists for the waitlist case and for nothing else. An earlier version of this
 * step clicked 确认 and timed out waiting for a button the product had already
 * made unnecessary.
 *
 * The state still matters: a `sourceType: project` service record requires a
 * *confirmed* registration at both creation and submission, so this is where
 * that precondition is checked rather than assumed.
 */
export async function openRegistrantList(page: Page, baseUrl: string, title: string): Promise<void> {
  const card = await findProjectCard(page, baseUrl, title);
  // The row menu is the icon-only button inside the card.
  await tap(card.locator('button').filter({ hasText: /^$/ }).first(), '项目的更多操作');
  await tap(page.getByText('报名名单', { exact: true }), '报名名单');
  await page.getByRole('button', { name: '关闭' }).waitFor({ state: 'visible', timeout: 15_000 });
}

/**
 * Two signals, because either alone is weak: the named student is listed as
 * confirmed, and nothing is left awaiting confirmation. Checking only for the
 * word would pass on a list showing somebody else.
 */
export async function assertRegistrantConfirmed(page: Page, studentName: string): Promise<void> {
  // The dialog frame renders before its list arrives, so it is briefly open and
  // empty — indistinguishable from a project nobody signed up for. Waiting for
  // either the row or the empty-state message is what tells those apart; reading
  // the page straight after opening reported "no registrant" for a registration
  // that was perfectly fine.
  await page.getByText(studentName).or(page.getByText('暂无报名学生'))
    .first().waitFor({ state: 'visible', timeout: 20_000 });
  const body = await page.locator('body').innerText();
  assertContains(body, studentName, `报名名单中应当出现该学生：${studentName}`);
  assertContains(body, '已确认', '该学生的报名应当处于已确认状态');
  const awaiting = await page.getByTitle('确认').count();
  assertThat(awaiting === 0, `名额充足时报名应当直接确认，但仍有 ${awaiting} 条待确认`);
}

/** Only called once the list has narrowed to this project (`waitForSingleResult`). */
function studentCard(page: Page, title: string) {
  // Same shape as the admin card: filter on the title *and* a field only the
  // card carries, then take the innermost match — every ancestor contains both.
  return page.locator('div').filter({ hasText: title }).filter({ hasText: '查看详情' }).last();
}
