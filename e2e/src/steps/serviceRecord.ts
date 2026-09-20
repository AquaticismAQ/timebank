import type { Locator, Page } from 'playwright';
import { choose, narrate, tap, type, visit } from '../harness/stage.js';
import { assertContains, assertThat } from '../harness/assert.js';

/**
 * The status chip text, taken from `labels.recordStatus` in the zh bundle.
 *
 * Collected here because the words are easy to get wrong from memory: the toast
 * after submitting says 待审核 while the chip says 已提交, and a returned record
 * reads 退回 rather than 已退回. Both mistakes look like a broken product rather
 * than a wrong expectation.
 */
export const RECORD_STATUS = {
  draft: '草稿',
  submitted: '已提交',
  underReview: '审核中',
  approved: '已通过',
  returned: '退回',
  rejected: '已驳回',
} as const;

export interface RecordDraft {
  projectTitle: string;
  /** `YYYY-MM-DD`, today or earlier — a future service date is refused at submit. */
  date: string;
  startHHMM: string;
  endHHMM: string;
  description: string;
  evidenceFile: string;
}

/**
 * Fills the new-record wizard as far as a saved draft.
 *
 * The form is four steps (source → details → evidence → declaration) and each
 * step mounts only its own `required` fields, so there is no way to shortcut it:
 * pressing Next is what validates the step you are leaving (issue 058/066).
 */
export async function createDraft(page: Page, baseUrl: string, draft: RecordDraft): Promise<void> {
  await visit(page, `${baseUrl}/student/records`, '服务记录页面');
  await tap(page.getByRole('button', { name: '新建记录' }).first(), '新建记录');

  // Step 0 — source. `学校项目` is what makes the record derive its organization,
  // category and supervisor from the project rather than asking for them. It is
  // already selected by default; clicked anyway, so the demo shows the choice
  // being made and the step does not depend on that default staying put.
  await tap(page.getByText('学校项目', { exact: true }), '学校项目');
  await next(page);

  // Step 1 — which project, when.
  await choose(page.locator('select').first(), '刚才报名的项目', { label: draft.projectTitle });

  // Choosing a project prefills the date and times from the project's own
  // schedule, and that prefill is an effect — it lands *after* the change event.
  // Filling the date first therefore works only while something else is slow
  // enough to let the effect run in between: with `--slow-mo` it passes, without
  // it the prefill overwrites the slot with the project's start date, which is in
  // the future, and submission is refused for a reason that never appears on
  // screen. Waiting for the prefill and then overwriting it is the only ordering
  // that holds at either speed.
  const serviceDate = field(page, '服务日期 *');
  await waitUntilFilled(page, serviceDate, '服务日期');
  await type(serviceDate, '服务日期', draft.date);
  await pickTime(page, '开始时间 *', draft.startHHMM);
  await pickTime(page, '结束时间 *', draft.endHHMM);
  await type(field(page, '服务内容描述 *'), '服务内容描述', draft.description);

  await tap(page.getByRole('button', { name: '保存草稿' }), '保存草稿');
  await page.getByText('草稿已保存').waitFor({ state: 'visible', timeout: 15_000 });
}

/**
 * Reopens the saved draft, changes its description, and saves again.
 *
 * The point is the read-back in {@link assertRecordDescription}: a form that
 * silently drops an edit looks identical to one that saved it.
 */
export async function editDraftDescription(page: Page, baseUrl: string, projectTitle: string, newDescription: string): Promise<void> {
  await openDraftForEditing(page, baseUrl, projectTitle);
  await next(page);
  await type(field(page, '服务内容描述 *'), '更详细的服务内容描述', newDescription);
  await tap(page.getByRole('button', { name: '保存草稿' }), '保存草稿');
  await page.getByText('草稿已保存').waitFor({ state: 'visible', timeout: 15_000 });
}

/**
 * Attaches the evidence file and submits.
 *
 * Evidence is not optional: submission is refused without at least one file in
 * `READY` state (`ServiceRecordServiceImpl:581`), so the wait for the filename to
 * appear is waiting for a real upload to finish, not a cosmetic delay.
 */
export async function uploadEvidenceAndSubmit(page: Page, baseUrl: string, projectTitle: string, filePath: string): Promise<string> {
  await openDraftForEditing(page, baseUrl, projectTitle);
  await next(page);   // source → details
  await next(page);   // details → evidence

  // The input is `hidden` behind a drop zone; setInputFiles drives it directly,
  // which is what a file chooser would do anyway. Announced all the same,
  // because on screen a file simply appears and an audience needs telling why.
  const fileInput = page.locator('input[type="file"]');
  say(page, '现在上传服务的佐证材料');
  await fileInput.setInputFiles(filePath);
  const fileName = filePath.split('/').pop()!;
  await page.getByText(fileName, { exact: false }).waitFor({ state: 'visible', timeout: 60_000 });

  await next(page);   // evidence → declaration
  const confirm = page.getByRole('button', { name: '确认提交' });
  await tap(confirm, '确认提交');
  // The wizard closing is the signal, not the toast. Toasts auto-dismiss, so
  // waiting on one makes the step depend on winning a race it does not control —
  // the submit itself had already succeeded when this first timed out.
  await confirm.waitFor({ state: 'hidden', timeout: 25_000 });
  return fileName;
}

/**
 * @param key a record number once one exists, or the project title while the
 *   record is still the project's only draft
 */
export async function assertRecordStatus(page: Page, baseUrl: string, key: string, expectedZh: string): Promise<void> {
  const text = await recordRowText(page, baseUrl, key, expectedZh === RECORD_STATUS.draft ? RECORD_STATUS.draft : undefined);
  assertContains(text, expectedZh, `该服务记录应当处于「${expectedZh}」状态`);
}

/**
 * Reads the description back through the edit form.
 *
 * The record list is a table of project, category, date, duration, status and
 * coins — the description is not on it, so asserting against the list would
 * always fail. Reopening the draft is also the honest check: it is what a
 * student does to see whether their edit stuck, and a save that silently drops
 * the change looks identical to one that worked.
 */
export async function assertRecordDescription(page: Page, baseUrl: string, projectTitle: string, expected: string): Promise<void> {
  await openDraftForEditing(page, baseUrl, projectTitle);
  await next(page);
  const actual = await field(page, '服务内容描述 *').inputValue();
  assertContains(actual, expected, '重新打开草稿后应当看到刚刚保存的服务内容描述');
  await page.goto(`${baseUrl}/student/records`, { waitUntil: 'domcontentloaded' });
}

/** The row carries the record number, not the filename — so this checks the detail view. */
export async function assertEvidenceShown(page: Page, baseUrl: string, recordNumber: string, fileName: string): Promise<void> {
  await gotoRecords(page, baseUrl);
  await tap(recordRow(page, recordNumber).getByRole('button', { name: '查看' }), '查看详情');
  // Wait for the dialog, not just for the click. Reading the page straight after
  // clicking races the render and fails against a detail view that is in fact
  // correct — which is how this read the first time.
  await page.getByText('服务记录详情').waitFor({ state: 'visible', timeout: 15_000 });
  await page.getByText('证明材料').waitFor({ state: 'visible', timeout: 15_000 });
  const body = await page.locator('body').innerText();
  assertThat(body.length > 0, '记录详情应当可以打开');
  assertContains(body, fileName, `记录详情应当显示已上传的佐证文件：${fileName}`);
  await tap(page.getByRole('button', { name: '关闭' }), '关闭');
}

/**
 * Opens the record list with every row on one page.
 *
 * The list holds 20 rows by default, sorted by service date descending, and the
 * records a run files are dated in the past — so by the third run this student's
 * newest records sit below the fold and the row simply is not there. Searching
 * does not help: the record search matches student and organization names, not
 * project titles or record numbers. Raising the page size is the control that
 * does, and it is one a student has too.
 */
async function gotoRecords(page: Page, baseUrl: string): Promise<void> {
  await visit(page, `${baseUrl}/student/records`, '服务记录页面');
  const pageSize = page.locator('select').filter({ hasText: '100' }).last();
  await pageSize.waitFor({ state: 'visible', timeout: 15_000 });
  await pageSize.selectOption('100');
  await page.waitForTimeout(800);
}

/**
 * The table row for one record.
 *
 * A record number identifies exactly one row. A project title does not, once the
 * branch phases have filed a second and third record against the same project —
 * so a title is only usable together with a status that narrows it to one, which
 * in practice means 草稿, since a project has at most one draft at a time.
 */
export function recordRow(page: Page, key: string, alsoContains?: string) {
  const byKey = page.locator('tr').filter({ hasText: key });
  return alsoContains ? byKey.filter({ hasText: alsoContains }) : byKey;
}

async function recordRowText(page: Page, baseUrl: string, key: string, alsoContains?: string): Promise<string> {
  await gotoRecords(page, baseUrl);
  const row = recordRow(page, key, alsoContains);
  await row.waitFor({ state: 'visible', timeout: 15_000 });
  return row.innerText();
}

/** The project's current draft — at most one, which is what makes the title enough here. */
async function openDraftForEditing(page: Page, baseUrl: string, projectTitle: string): Promise<void> {
  await gotoRecords(page, baseUrl);
  const row = recordRow(page, projectTitle, RECORD_STATUS.draft);
  await row.waitFor({ state: 'visible', timeout: 15_000 });
  await tap(row.getByRole('button', { name: '编辑' }), '编辑');
}

/** Polled from Node rather than in the page, so this file typechecks without the DOM lib. */
async function waitUntilFilled(page: Page, locator: Locator, what: string, timeoutMs = 15_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if ((await locator.inputValue()) !== '') return;
    await page.waitForTimeout(200);
  }
  throw new Error(`${what} was never prefilled from the selected project`);
}

function field(page: Page, labelZh: string) {
  return page.locator(`xpath=//label[normalize-space(.)="${labelZh}"]/following-sibling::*[self::input or self::textarea or self::select][1]`);
}

async function next(page: Page): Promise<void> {
  await tap(page.getByRole('button', { name: '下一步' }), '下一步');
}

/** For the few actions that are not a click on something visible. */
function say(page: Page, zh: string): void {
  narrate(page, zh);
}

/**
 * The time fields are a popover with an hour column and a minute column, not an
 * `<input type="time">` (`shared/TimeInput.tsx`).
 *
 * Both columns contain the same two-digit strings, so `09` is ambiguous across
 * the popover as a whole — the columns have to be addressed positionally.
 */
async function pickTime(page: Page, labelZh: string, hhmm: string): Promise<void> {
  const [hh, mm] = hhmm.split(':');
  // Radix's PopoverTrigger uses `asChild`, so the trigger *is* the button and it
  // is the label's immediate sibling — there is no wrapper element to descend
  // into.
  await page.locator(`xpath=//label[normalize-space(.)="${labelZh}"]/following-sibling::button[1]`).first().click();
  const columns = page.locator('div.flex.divide-x > div');
  await columns.nth(0).getByRole('button', { name: hh, exact: true }).click();
  await columns.nth(1).getByRole('button', { name: mm, exact: true }).click();
  await page.keyboard.press('Escape');
}

/**
 * The newest record on this student's file.
 *
 * Used to learn the record number a phase has just created. Reading it from the
 * table would mean matching on the project title, which stops being unique the
 * moment a second branch files another record against the same project.
 */
export async function readLatestRecord(api: import('../harness/api.js').Api): Promise<{
  id: string; recordNumber: string; status: string; projectTitle: string; durationMinutes: number;
}> {
  const rows = await api.get<Array<{
    id: string; recordNumber: string; status: string; projectTitle: string; durationMinutes: number;
  }>>('/service-records?page%5Bsize%5D=1&sort=-createdAt');
  if (rows.length === 0) throw new Error('No service record found for this student.');
  return rows[0];
}
