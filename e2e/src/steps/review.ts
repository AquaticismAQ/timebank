import type { Page } from 'playwright';
import { assertThat } from '../harness/assert.js';

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
  await page.goto(`${baseUrl}/admin/audit`, { waitUntil: 'domcontentloaded' });

  // Found by project title, because that is what the queue row shows
  // (`item.projectTitle || item.organizationName`). The search box above the list
  // matches students and organizations, so a record number typed into it finds
  // nothing at all.
  await page.getByText(projectTitle, { exact: false }).first().click();

  // Cross-check we opened the record we meant to. One project can have several
  // records once the return and reject branches have run, and deciding on the
  // wrong one would still look like a pass.
  // `.first()`: the number appears twice in the workbench — as the subtitle
  // and again in the detail fields.
  await page.getByText(recordNumber, { exact: false }).first().waitFor({ state: 'visible', timeout: 15_000 });

  await page.getByRole('button', { name: BUTTON[decision], exact: true }).last().waitFor({ state: 'visible', timeout: 15_000 });
  await page.getByRole('button', { name: BUTTON[decision], exact: true }).last().click();

  if (decision !== 'approve') {
    // The confirm button stays disabled until both are given, so skipping either
    // would hang on a click that can never land rather than failing clearly.
    await page.locator('select').last().selectOption(REASON_VALUE[decision]);
  }
  await page.locator('textarea').last().fill(comment);
  // The confirm button is two interpolated strings with a literal space between
  // them (`{t('confirm')} {t('approve')}`), so its accessible name is 确认 通过
  // rather than 确认通过 — an exact match on the joined word never lands.
  const confirm = page.getByRole('button', { name: new RegExp(`确认\\s*${BUTTON[decision]}`) });
  await confirm.click();
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
export async function assertRecordLeftTheQueue(page: Page, baseUrl: string, recordNumber: string): Promise<void> {
  await page.goto(`${baseUrl}/admin/audit`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1500);
  const body = await page.locator('body').innerText();
  assertThat(!body.includes(recordNumber), `审核完成后，该记录不应当再出现在待审核队列中：${recordNumber}`);
}
