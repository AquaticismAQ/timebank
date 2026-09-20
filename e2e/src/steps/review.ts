import type { Page } from 'playwright';
import { assertThat } from '../harness/assert.js';
import { choose, tap, type, visit } from '../harness/stage.js';

export type Decision = 'approve' | 'return' | 'reject';

const BUTTON: Record<Decision, string> = { approve: '通过', return: '退回', reject: '驳回' };
/** Return and reject demand a reason *and* a comment; approve takes neither. */
const REASON_VALUE: Record<'return' | 'reject', string> = {
  return: 'MISSING_EVIDENCE', reject: 'FAKE_SERVICE',
};

/**
 * A teacher works one record in the review queue and decides on it.
 *
 * The queue is opened, the record found by its number, its detail workbench
 * opened, and the decision made there — the same path a reviewer takes. The
 * bulk-approve shortcut at the top of the list is deliberately not used: it
 * exists for a different job and would skip the detail view a reviewer is
 * supposed to read before deciding.
 */
export async function decideOnRecord(
  page: Page, baseUrl: string, projectTitle: string, recordNumber: string, decision: Decision, comment: string,
): Promise<void> {
  await visit(page, `${baseUrl}/admin/audit`, '审核中心');

  // Found by project title, because that is what the queue row shows
  // (`item.projectTitle || item.organizationName`). The search box above the list
  // matches students and organizations, so a record number typed into it finds
  // nothing at all.
  await tap(page.getByText(projectTitle, { exact: false }).first(), '这条待审核的记录');

  // Cross-check we opened the record we meant to. One project can have several
  // records once the return and reject branches have run, and deciding on the
  // wrong one would still look like a pass.
  // `.first()`: the number appears twice in the workbench — as the subtitle
  // and again in the detail fields.
  await page.getByText(recordNumber, { exact: false }).first().waitFor({ state: 'visible', timeout: 15_000 });

  await inspectEvidence(page);

  const action = page.getByRole('button', { name: BUTTON[decision], exact: true }).last();
  await action.waitFor({ state: 'visible', timeout: 15_000 });
  await tap(action, BUTTON[decision]);

  if (decision !== 'approve') {
    // The confirm button stays disabled until both are given, so skipping either
    // would hang on a click that can never land rather than failing clearly.
    await choose(page.locator('select').last(), '原因类别', REASON_VALUE[decision]);
  }
  await type(page.locator('textarea').last(), '审核意见', comment);
  // The confirm button is two interpolated strings with a literal space between
  // them (`{t('confirm')} {t('approve')}`), so its accessible name is 确认 通过
  // rather than 确认通过 — an exact match on the joined word never lands.
  const confirm = page.getByRole('button', { name: new RegExp(`确认\\s*${BUTTON[decision]}`) });
  await tap(confirm, `确认${BUTTON[decision]}`);
  // Wait for the dialog to go, not for the toast. The toast is real but it
  // auto-dismisses, so waiting on it makes the step depend on losing a race it
  // is not in control of — an approval that landed correctly failed here once
  // for exactly that reason. The dialog closing only happens on success.
  await confirm.waitFor({ state: 'hidden', timeout: 20_000 });
}

/**
 * A decided record leaves the pending queue, which is the admin-side proof.
 *
 * Asserting the decision word appeared somewhere on the page would pass on any
 * other record carrying it; asserting this one is *gone* from the queue is
 * specific and is what a reviewer actually observes.
 */
/**
 * Opens the evidence tab and looks at the picture full size, then closes it.
 *
 * This is the part of the workflow the school most needs to see. Approving a
 * record is a judgement about whether service actually happened, and the only
 * thing standing behind that judgement is the photograph or the signed
 * confirmation the student attached — a demonstration that clicks 通过 without
 * ever opening the evidence shows a rubber stamp, which is the opposite of what
 * this system is for.
 *
 * Tolerant of the tile not being there: a record whose evidence is still
 * processing, or a PDF rendered without a thumbnail, should not stop a
 * demonstration over something the teacher could simply skip.
 */
async function inspectEvidence(page: Page): Promise<void> {
  const tab = page.getByRole('button', { name: '证明材料' }).first();
  if (!(await tab.isVisible().catch(() => false))) return;
  await tap(tab, '证明材料');

  // The tile is the only button inside the evidence panel carrying the file's
  // own name as its title.
  const tile = page.locator('[title]').filter({ has: page.locator('img') }).first();
  if (!(await tile.isVisible().catch(() => false))) return;

  await tap(tile, '这张佐证照片，放大查看');
  const close = page.getByRole('button', { name: '关闭' }).last();
  await close.waitFor({ state: 'visible', timeout: 15_000 });
  await tap(close, '关闭');
}

export async function assertRecordLeftTheQueue(page: Page, baseUrl: string, recordNumber: string): Promise<void> {
  await visit(page, `${baseUrl}/admin/audit`, '审核中心');
  await page.waitForTimeout(1500);
  const body = await page.locator('body').innerText();
  assertThat(!body.includes(recordNumber), `审核完成后，该记录不应当再出现在待审核队列中：${recordNumber}`);
}
