import type { Page } from 'playwright';
import { assertContains, assertThat } from '../harness/assert.js';

/**
 * The create-project modal.
 *
 * Fields are located by their visible label. The form has no test ids and its
 * `<label>` elements are not bound to inputs with `htmlFor`, so the DOM shape —
 * `<div><label>文案</label><input></div>` — is what there is to aim at. That makes
 * these selectors sensitive to copy changes, which is a real signal rather than
 * something to defend against: the labels are also what gets spoken, so if they
 * move the narration was wrong too.
 */
function field(page: Page, labelZh: string) {
  return page.locator(`xpath=//label[normalize-space(.)="${labelZh}"]/following-sibling::*[self::input or self::textarea or self::select][1]`);
}

export interface NewProject {
  title: string;
  summary: string;
  description: string;
  /** Hours the school recognises for attending — what the student's balance should rise by. */
  recognizedHours: number;
  location: string;
  capacity: number;
  /** Grade names as shown on the chips, e.g. 八年级. */
  eligibleGradeNames: string[];
}

/**
 * Creates and publishes in one action.
 *
 * The modal's own Publish button creates the draft and publishes it in sequence,
 * which is the path a teacher actually takes; driving create-then-publish
 * separately would be testing a route nobody uses.
 *
 * The registration window is deliberately **open now**: `registrationOpen` is
 * time-derived, never a manual action, so a project whose window has not started
 * can never be registered for and the next phase would hang with no way forward.
 */
export async function createAndPublishProject(page: Page, baseUrl: string, project: NewProject): Promise<void> {
  await page.goto(`${baseUrl}/admin/projects`, { waitUntil: 'domcontentloaded' });
  await page.getByRole('button', { name: '新建项目' }).click();
  await page.getByText('新建志愿服务项目').waitFor({ state: 'visible' });

  await field(page, '项目名称 *').fill(project.title);
  await field(page, '项目摘要').fill(project.summary);
  await field(page, '详细描述').fill(project.description);
  await field(page, '认定时长').fill(String(project.recognizedHours));
  await field(page, '地点').fill(project.location);
  await field(page, '名额上限').fill(String(project.capacity));

  const now = new Date();
  await field(page, '报名开放').fill(localDateTime(minutesFrom(now, -60)));
  await field(page, '报名截止').fill(localDateTime(minutesFrom(now, 60 * 24 * 7)));
  await field(page, '活动开始').fill(localDateTime(minutesFrom(now, 60 * 24 * 8)));
  await field(page, '活动结束').fill(localDateTime(minutesFrom(now, 60 * 24 * 8 + 120)));

  for (const grade of project.eligibleGradeNames) {
    await page.getByRole('button', { name: grade, exact: true }).click();
  }

  await page.getByRole('button', { name: '发布', exact: true }).click();
  // The modal closes on success and stays open on a field error, so its absence
  // is the signal — waiting on the toast would race its own dismissal.
  await page.getByText('新建志愿服务项目').waitFor({ state: 'hidden', timeout: 20_000 });
}

/** Reads a project's status from its row in the management list, not from any create response. */
export async function readProjectStatus(page: Page, baseUrl: string, title: string): Promise<string> {
  await page.goto(`${baseUrl}/admin/projects`, { waitUntil: 'domcontentloaded' });
  const card = await findProjectCard(page, baseUrl, title);
  // The status badge is the card's first line. Read from the row rather than
  // trusted from the create response, because `registrationOpen` is time-derived
  // and synced lazily on read — the row is where the real answer appears.
  const text = await card.innerText();
  return text.split('\n').map(l => l.trim()).filter(Boolean)[0] ?? '';
}

/**
 * Finds the card for one project by title, searching for it rather than paging —
 * the list defaults to the current academic year and 20 per page, and an `[AUTO]`
 * project from a busy day would otherwise fall off the end.
 */
export async function findProjectCard(page: Page, baseUrl: string, title: string) {
  if (!page.url().includes('/admin/projects')) {
    await page.goto(`${baseUrl}/admin/projects`, { waitUntil: 'domcontentloaded' });
  }
  const search = page.getByPlaceholder('搜索项目...');
  await search.fill(title);
  await search.press('Enter');
  // Narrowed by the owner line as well as the title. Filtering on the title
  // alone and taking `.last()` returns the innermost match — which is the title
  // element itself, text `"[AUTO] … 社区图书馆志愿服务"` and nothing else. The
  // card is the smallest element holding both the title and the footer.
  await waitForSingleResult(page, title);
  const card = page.locator('div').filter({ hasText: title }).filter({ hasText: '负责人' }).last();
  await card.waitFor({ state: 'visible', timeout: 15_000 });
  return card;
}

/**
 * Waits for a search to actually narrow the list to one project.
 *
 * Both project lists print their own result count, and that header is the only
 * unambiguous signal that the filter has been applied — counting cards does not
 * work, because once the list holds a single card every ancestor element
 * contains its text too.
 *
 * Without this wait, acting straight after pressing Enter acts on the
 * *unfiltered* list, and with a run id in every `[AUTO]` title that list is full
 * of near-identical cards. That is how a student came to register for a previous
 * run's project while every assertion still passed — they were all reading the
 * same wrong card.
 */
export async function waitForSingleResult(page: Page, what: string, timeoutMs = 15_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  let seen = '';
  while (Date.now() < deadline) {
    seen = await page.locator('main').innerText().catch(() => '');
    if (/(^|\s)1 个项目/.test(seen)) return;
    await page.waitForTimeout(250);
  }
  const shown = seen.match(/\d+ 个项目/)?.[0] ?? '(no count shown)';
  throw new Error(`Search for ${what} did not narrow to one project — list still says ${shown}`);
}

export async function assertProjectIsOpenForRegistration(page: Page, baseUrl: string, title: string): Promise<void> {
  const status = await readProjectStatus(page, baseUrl, title);
  assertThat(status.length > 0, `项目「${title}」应当出现在项目列表中`);
  assertContains(status, '报名开放', `项目「${title}」发布后应当处于报名开放状态`);
}

/** `datetime-local` wants local wall-clock time, never an ISO instant (issue 067). */
function localDateTime(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
}

function minutesFrom(base: Date, minutes: number): Date {
  return new Date(base.getTime() + minutes * 60_000);
}
