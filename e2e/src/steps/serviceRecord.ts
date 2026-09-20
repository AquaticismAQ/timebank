import type { Page } from 'playwright';
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
  await page.goto(`${baseUrl}/student/records`, { waitUntil: 'domcontentloaded' });
  await page.getByRole('button', { name: '新建记录' }).first().click();

  // Step 0 — source. `学校项目` is what makes the record derive its organization,
  // category and supervisor from the project rather than asking for them. It is
  // already selected by default; clicked anyway, so the demo shows the choice
  // being made and the step does not depend on that default staying put.
  await page.getByText('学校项目', { exact: true }).click();
  await next(page);

  // Step 1 — which project, when.
  await page.locator('select').first().selectOption({ label: draft.projectTitle });
  await field(page, '服务日期 *').fill(draft.date);
  await pickTime(page, '开始时间 *', draft.startHHMM);
  await pickTime(page, '结束时间 *', draft.endHHMM);
  await field(page, '服务内容描述 *').fill(draft.description);

  await page.getByRole('button', { name: '保存草稿' }).click();
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
  await field(page, '服务内容描述 *').fill(newDescription);
  await page.getByRole('button', { name: '保存草稿' }).click();
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
  // which is what a file chooser would do anyway.
  await page.locator('input[type="file"]').setInputFiles(filePath);
  const fileName = filePath.split('/').pop()!;
  await page.getByText(fileName, { exact: false }).waitFor({ state: 'visible', timeout: 60_000 });

  await next(page);   // evidence → declaration
  await page.getByRole('button', { name: '确认提交' }).click();
  await page.getByText('服务记录已提交，待审核').waitFor({ state: 'visible', timeout: 20_000 });
  return fileName;
}

export async function assertRecordStatus(page: Page, baseUrl: string, projectTitle: string, expectedZh: string): Promise<void> {
  const text = await recordRowText(page, baseUrl, projectTitle);
  assertContains(text, expectedZh, `该项目的服务记录应当处于「${expectedZh}」状态`);
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
export async function assertEvidenceShown(page: Page, baseUrl: string, projectTitle: string, fileName: string): Promise<void> {
  await recordRow(page, baseUrl, projectTitle).getByRole('button', { name: '查看' }).click();
  // Wait for the dialog, not just for the click. Reading the page straight after
  // clicking races the render and fails against a detail view that is in fact
  // correct — which is how this read the first time.
  await page.getByText('服务记录详情').waitFor({ state: 'visible', timeout: 15_000 });
  await page.getByText('证明材料').waitFor({ state: 'visible', timeout: 15_000 });
  const body = await page.locator('body').innerText();
  assertThat(body.length > 0, '记录详情应当可以打开');
  assertContains(body, fileName, `记录详情应当显示已上传的佐证文件：${fileName}`);
  await page.getByRole('button', { name: '关闭' }).click();
}

/** The table row for one project's record. Scoped by title so a second draft cannot be picked up. */
function recordRow(page: Page, baseUrl: string, projectTitle: string) {
  return page.locator('tr').filter({ hasText: projectTitle });
}

async function recordRowText(page: Page, baseUrl: string, projectTitle: string): Promise<string> {
  await page.goto(`${baseUrl}/student/records`, { waitUntil: 'domcontentloaded' });
  const row = recordRow(page, baseUrl, projectTitle);
  await row.waitFor({ state: 'visible', timeout: 15_000 });
  return row.innerText();
}

async function openDraftForEditing(page: Page, baseUrl: string, projectTitle: string): Promise<void> {
  await page.goto(`${baseUrl}/student/records`, { waitUntil: 'domcontentloaded' });
  const row = recordRow(page, baseUrl, projectTitle);
  await row.waitFor({ state: 'visible', timeout: 15_000 });
  await row.getByRole('button', { name: '编辑' }).click();
}

function field(page: Page, labelZh: string) {
  return page.locator(`xpath=//label[normalize-space(.)="${labelZh}"]/following-sibling::*[self::input or self::textarea or self::select][1]`);
}

async function next(page: Page): Promise<void> {
  await page.getByRole('button', { name: '下一步' }).click();
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
