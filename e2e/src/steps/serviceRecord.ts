import type { Locator, Page } from 'playwright';
import { choose, field, narrate, tap, type, visit } from '../harness/stage.js';
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
  await fillSourceAndDetails(page, baseUrl, draft);
  await tap(page.getByRole('button', { name: '保存草稿' }), '保存草稿');
  await page.getByText('草稿已保存').waitFor({ state: 'visible', timeout: 15_000 });
}

/**
 * Fills the new-record wizard and submits it, in one pass.
 *
 * What the demonstration shows: a student who has everything to hand files the
 * record without stopping. The draft round trip is still exercised by the
 * branch phases (`createDraft` then `submitDraft`).
 */
export async function createAndSubmit(page: Page, baseUrl: string, draft: RecordDraft): Promise<string> {
  await fillSourceAndDetails(page, baseUrl, draft);
  await next(page);   // details → evidence
  const fileName = await attachEvidence(page, draft.evidenceFile);
  await next(page);   // evidence → declaration
  const confirm = page.getByRole('button', { name: '确认提交' });
  await tap(confirm, '确认提交');
  // The wizard closing is the signal, not the toast. Toasts auto-dismiss, so
  // waiting on one makes the step depend on winning a race it does not control.
  await confirm.waitFor({ state: 'hidden', timeout: 25_000 });
  return fileName;
}

/** Opens a new record and fills the source and details steps, leaving the details step open. */
async function fillSourceAndDetails(page: Page, baseUrl: string, draft: RecordDraft): Promise<void> {
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
  await clearEndsNextDay(page);
  await type(field(page, '服务内容描述 *'), '服务内容描述', draft.description);

}

/** Straight from draft to submitted, with no edit — what the branch phases need. */
export async function submitDraft(
  page: Page, baseUrl: string, projectTitle: string, evidenceFile: string,
): Promise<string> {
  await openDraftForEditing(page, baseUrl, projectTitle);
  await next(page);
  await next(page);
  const fileName = await attachEvidence(page, evidenceFile);
  await next(page);
  const confirm = page.getByRole('button', { name: '确认提交' });
  await tap(confirm, '确认提交');
  await confirm.waitFor({ state: 'hidden', timeout: 25_000 });
  return fileName;
}

/**
 * Attaches the file and waits for it to be usable.
 *
 * Evidence is not optional: submission is refused without at least one file in
 * `READY` state (`ServiceRecordServiceImpl:581`), so this wait is waiting for a
 * real upload to finish rather than a cosmetic delay.
 */
async function attachEvidence(page: Page, filePath: string): Promise<string> {
  const fileName = filePath.split('/').pop()!;
  narrate(page, '现在上传服务的佐证材料');
  await page.locator('input[type="file"]').setInputFiles(filePath);
  await page.getByText(fileName, { exact: false }).waitFor({ state: 'visible', timeout: 60_000 });
  return fileName;
}

export async function assertRecordStatus(page: Page, baseUrl: string, key: string, expectedZh: string): Promise<void> {
  const text = await recordRowText(page, baseUrl, key, expectedZh === RECORD_STATUS.draft ? RECORD_STATUS.draft : undefined);
  assertContains(text, expectedZh, `该服务记录应当处于「${expectedZh}」状态`);
}

/**
 * Opens the record's detail view and checks both facts there.
 *
 * One dialog rather than two trips: the detail view carries the description *and*
 * the attached file, so this is where a dropped edit and a lost upload would both
 * show — and it is the screen a student opens to check their own work. The record
 * list itself carries neither; it is a table of project, category, date,
 * duration, status and coins.
 */
export async function assertRecordDetail(
  page: Page, baseUrl: string, recordNumber: string, expect: { description: string; fileName: string },
): Promise<void> {
  await gotoRecords(page, baseUrl);
  await tap(recordRow(page, recordNumber).getByRole('button', { name: '查看' }), '查看详情');
  // Wait for the dialog, not just for the click. Reading the page straight after
  // clicking races the render and fails against a detail view that is in fact
  // correct — which is how this read the first time.
  await page.getByText('服务记录详情').waitFor({ state: 'visible', timeout: 15_000 });
  await page.getByText('证明材料').waitFor({ state: 'visible', timeout: 15_000 });

  const body = await page.locator('body').innerText();
  assertContains(body, expect.description, '记录详情应当显示填写的服务内容描述');
  assertContains(body, expect.fileName, `记录详情应当显示已上传的佐证文件：${expect.fileName}`);
  await tap(page.getByRole('button', { name: '关闭' }), '关闭');
}

/**
 * Opens the record list.
 *
 * The newest record is on top because the page now sorts by creation time by
 * default — which was a product fix rather than something for this suite to
 * arrange. The run had been clicking the sort control itself, and a test that
 * rearranges a screen before it can find anything is describing a defect, not
 * working around one: a student filing a record for last month's session would
 * equally have watched it land below thirty newer ones.
 *
 * The page size is still raised, so a row is present even when something else
 * is on top. Searching would not help either way: the record search matches
 * student and organization names, not project titles or record numbers.
 */
async function gotoRecords(page: Page, baseUrl: string): Promise<void> {
  // Idempotent, because several assertions in a row each want this list. Saying
  // "now open the service records page" three times over, to an audience already
  // looking at it, reads as the script having lost its place.
  if (!page.url().includes('/student/records')) {
    await visit(page, `${baseUrl}/student/records`, '服务记录页面');
  } else {
    // Already here, so no announcement — but still reloaded, because the list
    // was rendered before whatever just happened elsewhere. Skipping the refresh
    // as well as the narration meant a record that had just been approved was
    // still being read as submitted.
    await page.reload({ waitUntil: 'domcontentloaded' });
  }

  const pageSize = page.locator('select').filter({ hasText: '100' }).last();
  await pageSize.waitFor({ state: 'visible', timeout: 15_000 });
  if ((await pageSize.inputValue()) !== '100') {
    await pageSize.selectOption('100');
  }

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
/**
 * Clears "ends the next day", which the project prefill may have set.
 *
 * Choosing a school project autofills the date and both times from the
 * project's own schedule, and also ticks this box when the project's start and
 * end fall on different calendar dates (issue 063 — a project running 16:00 to
 * 01:00 otherwise autofills a pair that looks like a negative duration). The
 * step above then overwrites the date and times with an allocated slot and left
 * the box exactly as the prefill set it.
 *
 * Every slot this harness allocates is inside one day, so a ticked box turns a
 * two-hour record into a twenty-six-hour one. That is invisible until two of
 * them overlap — which is precisely how it surfaced: the core phase's record and
 * the return branch's each spanned 26 hours, the second was refused for
 * overlapping the first, and the failure read as a stuck submit button.
 *
 * It depends on the time of day the demo runs, which is why it stayed hidden:
 * the demo project is created `now + 8 days` and runs two hours, so it only
 * straddles midnight when the run itself starts after 22:00.
 */
async function clearEndsNextDay(page: Page): Promise<void> {
  const box = page.getByRole('checkbox', { name: '跨日结束（次日）' });
  if (await box.isChecked().catch(() => false)) {
    await box.uncheck();
  }
}

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
