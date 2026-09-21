import type { Page } from 'playwright';
import { assertContains, assertThat } from '../harness/assert.js';
import { choose, narrate, tap, type, visit } from '../harness/stage.js';

/**
 * Case status chips, from `labels.caseStatus` in the zh bundle.
 *
 * Read from the label map rather than from the review screen, which uses
 * different words for the same states — the teacher's footer says 解决 where the
 * student's chip says 已解决. Guessing from the reviewer's vocabulary is how the
 * proposal steps first failed, and cases have the same split.
 */
export const CASE_STATUS = {
  draft: '草稿',
  submitted: '已提交',
  needsInformation: '需补充信息',
  resolved: '已解决',
  rejected: '已驳回',
} as const;

/**
 * Files a case from My Cases, with no service record attached.
 *
 * **Why no record.** A case's title is not a field — `caseTitle()` in
 * `CaseFilingModal.tsx` takes the linked record's project title, and falls back
 * to *the first line of the reason* when there is no record. Attaching one
 * would title the case after whatever project it belonged to, which is neither
 * unique nor marked `[AUTO]`, so the flow could not find its own case among a
 * shared server's real ones and cleanup could never see it.
 *
 * Filing unattached keeps the title ours, and it is also a real scenario rather
 * than a testing convenience: a student claiming service the school has no
 * record of at all is exactly the 一般性申诉 the picker's first option offers.
 *
 * @param title becomes the first line of the reason, and therefore the case title
 * @param detail the rest of the reason, which becomes the description
 */
export async function fileCase(page: Page, baseUrl: string, title: string, detail: string): Promise<void> {
  await visit(page, `${baseUrl}/student/cases`, '我的申诉页面');
  await tap(page.getByRole('button', { name: '发起申诉' }).first(), '发起申诉');

  // Waited for explicitly, not left to the pause between actions. With the
  // `pace` gate off there is no pause, and the next locator then resolves
  // against the page *behind* the dialog — `select` last-on-page is the page
  // size control until the dialog mounts, which is a real control that accepts
  // a real selection, so nothing fails and the filing quietly goes wrong.
  const reason = reasonBox(page);
  await reason.waitFor({ state: 'visible', timeout: 15_000 });

  // The picker only appears for an unattached filing, and its first option is
  // the "no specific record" one. Chosen explicitly rather than left at its
  // default so the demonstration shows the choice being made. Scoped to the
  // dialog for the same reason the wait above exists.
  const picker = dialog(page).locator('select').last();
  if (await picker.isVisible().catch(() => false)) {
    await choose(picker, '关联记录', { index: 0 });
  }

  await type(reason, '申诉说明', `${title}\n${detail}`);
  await submitDialog(page);
}

/**
 * Answers the reviewer's question and sends the case back to them.
 *
 * The dialog shows the reviewer's own words above the reply box
 * (`latestRequest(timeline)`), which is the part worth showing: the student is
 * answering a specific question, not resubmitting the same text and hoping.
 * The reply is appended to the description under 学生回复 and the case is
 * submitted again — the original text is never overwritten, so the history
 * reads as a conversation.
 *
 * @param expectQuestion asserted to be on screen before typing — proof the
 *   student is seeing what the teacher actually asked, not an empty box
 */
export async function respondToCase(
  page: Page, baseUrl: string, title: string, reply: string, expectQuestion?: string,
): Promise<void> {
  await expandCase(page, baseUrl, title);
  // The action is inside the expanded panel, and only for a case in
  // 需补充信息 — which is the state machine, not this list, deciding what a
  // student may do next. Page-scoped rather than card-scoped because only one
  // case is ever expanded (`expandedId` is a single id).
  await tap(page.getByRole('button', { name: '补充并重新提交' }), '补充并重新提交');

  const replyBox = page.locator('textarea').last();
  await replyBox.waitFor({ state: 'visible', timeout: 15_000 });

  if (expectQuestion) {
    // The whole point of a returned case is that the student is told what is
    // missing. A run where the question failed to load would otherwise pass,
    // having demonstrated a student replying to nothing.
    //
    // Waited for rather than read straight off: the question comes from the
    // case's timeline, which is fetched when the card expands, so an immediate
    // read races the request and fails on a dialog that was about to be right.
    // The wait is allowed to expire — the assertion below is what reports, and
    // it names the sentence that was missing.
    await page.getByText(expectQuestion, { exact: false }).first()
      .waitFor({ state: 'visible', timeout: 15_000 }).catch(() => {});
    assertContains(await page.locator('body').innerText(), expectQuestion,
      '退回后的申诉应当把审核人的问题原样展示给学生');
  }

  await type(replyBox, '给审核人的回复', reply);
  await submitDialog(page);
}

export type CaseDecision = 'resolve' | 'requestInformation' | 'reject';

const CASE_BUTTON: Record<CaseDecision, string> = {
  resolve: '解决', requestInformation: '要求补充信息', reject: '驳回',
};

/**
 * A teacher opens a case in the review workbench and decides on it.
 *
 * The decision buttons live in the workbench footer and **only for a
 * `submitted` case** (`showDecisions`), which is what makes the return branch
 * real rather than cosmetic: between the teacher asking and the student
 * answering there is genuinely nothing the teacher can do.
 *
 * The comment is optional in the UI and always written here. On a
 * `requestInformation` it is the entire content of what the student receives —
 * a return with a blank comment is a case sent back with no explanation, which
 * would demonstrate the opposite of what this workflow is for.
 *
 * **The case is read before it is decided** (`review`). Opening a row and
 * clicking a decision in the same second is a rubber stamp, which is the
 * opposite of what a school is being shown: the whole reason a case gets a
 * human is that somebody weighs what the student wrote. The record review step
 * makes the same argument about opening the evidence before approving, and this
 * one had been written without it.
 */
export async function decideOnCase(
  page: Page, baseUrl: string, title: string, decision: CaseDecision, comment: string,
  review: CaseReviewOptions = {},
): Promise<void> {
  await visit(page, `${baseUrl}/admin/cases`, '申诉处理页面');
  await tap(page.getByText(title, { exact: false }).first(), '这份待处理的申诉');
  await readCase(page, review);

  const action = page.getByRole('button', { name: CASE_BUTTON[decision], exact: true }).last();
  await action.waitFor({ state: 'visible', timeout: 15_000 });
  await tap(action, CASE_BUTTON[decision]);

  await type(page.locator('textarea').last(), '给学生的处理说明', comment);
  await submitDialog(page);
}

/** Which tab of the workbench a reviewer opens beyond the one it lands on. */
export type CaseTab = 'history' | 'materials';

const TAB_LABEL: Record<CaseTab, string> = { history: '处理历史', materials: '相关材料' };

export interface CaseReviewOptions {
  /**
   * What must actually be legible on screen before a decision is taken.
   *
   * Asserted rather than assumed: a demonstration that says the teacher read
   * the student's account should fail if the account is not there. It also
   * catches the workbench having opened on the wrong case, which would
   * otherwise look identical.
   */
  expectOnScreen?: string[];
  /** Tabs beyond 基本信息, which is where the workbench lands. */
  alsoRead?: CaseTab[];
}

/**
 * The reviewer reads the case before deciding on it.
 *
 * Which tab matters depends on what is being decided, so the caller says. Asking
 * for more information is a judgement about what the student has *not* shown —
 * so 相关材料 is the tab, and finding it empty is the reason for the question.
 * Resolving afterwards is a judgement about the answer they gave, which is in
 * 处理历史 alongside the question it answers.
 */
async function readCase(page: Page, options: CaseReviewOptions): Promise<void> {
  // The workbench opens on 基本信息, where the student's own description is.
  narrate(page, '先看学生在申诉里写了什么');
  for (const fragment of options.expectOnScreen ?? []) {
    await page.getByText(fragment, { exact: false }).first()
      .waitFor({ state: 'visible', timeout: 15_000 }).catch(() => {});
    assertContains(await page.locator('body').innerText(), fragment,
      '处理之前，审核人应当能够看到学生写的内容');
  }

  for (const tab of options.alsoRead ?? []) {
    const label = TAB_LABEL[tab];
    const button = page.getByRole('button', { name: label, exact: true }).first();
    if (!(await button.isVisible().catch(() => false))) continue;
    await tap(button, label);
    // Loaded, not merely clicked: both tabs fetch, and a screenshot of a
    // spinner is not a reviewer reading anything.
    await page.getByText(/[^\s]/).first().waitFor({ state: 'visible', timeout: 15_000 }).catch(() => {});
    await page.getByText('加载中', { exact: false }).first()
      .waitFor({ state: 'hidden', timeout: 15_000 }).catch(() => {});
  }
}

/**
 * Opens the case's 处理历史 tab and returns what it says.
 *
 * The entries carry a machine `type` (`created`, `submitted`,
 * `decision.resolve`, …) beside a server-composed English description. Callers
 * should match on the type, not on the sentence: the codes are the contract and
 * the prose is not — it is written by the backend, which authors no localized
 * copy, so a Chinese assertion here would be asserting something the server
 * never promised to say.
 */
export async function showCaseHistory(page: Page, baseUrl: string, title: string): Promise<string> {
  await visit(page, `${baseUrl}/admin/cases`, '申诉处理页面');
  await tap(page.getByText(title, { exact: false }).first(), '这份申诉');
  await tap(page.getByRole('button', { name: '处理历史' }).first(), '处理历史');

  // The tab renders a loading line first and the list only once the timeline
  // request lands. Waiting for the *list item* rather than the `ol` is what
  // makes this real — an empty `ol` is visible immediately.
  const history = page.locator('ol').last();
  await history.locator('li').first().waitFor({ state: 'visible', timeout: 15_000 });
  const text = await history.innerText();
  await tap(page.getByRole('button', { name: '取消' }).last(), '关闭');
  return text;
}

export async function assertCaseStatus(page: Page, baseUrl: string, title: string, expectedZh: string): Promise<void> {
  await visit(page, `${baseUrl}/student/cases`, '我的申诉页面');
  const header = caseHeader(page, title);
  await header.waitFor({ state: 'visible', timeout: 15_000 });
  assertContains(await header.innerText(), expectedZh, `申诉「${title}」应当处于「${expectedZh}」状态`);
}

/**
 * The student's own record of what they wrote, and of what they added later.
 *
 * Asserted on the student's page rather than the teacher's: a case is a thing a
 * student is owed an answer about, so it is their view of it that has to be
 * right. The reply being present *alongside* the original text is the property —
 * an answer that replaced the question would leave the history unreadable.
 */
export async function assertCaseHolds(
  page: Page, baseUrl: string, title: string, ...fragments: string[]
): Promise<void> {
  await expandCase(page, baseUrl, title);
  const shown = await page.locator('body').innerText();
  for (const fragment of fragments) {
    assertContains(shown, fragment, `申诉「${title}」应当仍然保留：${fragment}`);
  }
}

/**
 * The clickable header of a case card.
 *
 * A card, not a row — and the header is a `<button>` carrying the status chip,
 * the type, the title and the date as its accessible name, which is why the
 * status assertion reads it rather than hunting for a chip element.
 */
function caseHeader(page: Page, title: string) {
  return page.getByRole('button', { name: new RegExp(escapeForRegExp(title)) }).first();
}

/**
 * Opens a case so its description, timeline and actions exist.
 *
 * They are rendered only while expanded, so every step that needs one has to
 * come through here. Expanding is idempotent in effect but not in the UI — a
 * second click closes it — so this checks before clicking rather than toggling
 * blind.
 */
async function expandCase(page: Page, baseUrl: string, title: string): Promise<void> {
  await visit(page, `${baseUrl}/student/cases`, '我的申诉页面');
  const header = caseHeader(page, title);
  await header.waitFor({ state: 'visible', timeout: 15_000 });
  await tap(header, '这条申诉，展开查看');
  // The description is the first thing the expanded panel renders.
  await page.locator('p.whitespace-pre-wrap').first().waitFor({ state: 'visible', timeout: 15_000 });
}

/** The run id contains no metacharacters today; escaping is cheap insurance. */
function escapeForRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * The open dialog, so a locator cannot reach the page behind it.
 *
 * Every dialog on these two screens is a fixed full-screen overlay at z-50;
 * `stage.ts`'s own error reporting scopes itself the same way.
 */
function dialog(page: Page) {
  return page.locator('div.fixed.inset-0').last();
}

/** Both filing dialogs are placeholder-only, with no label to hang a locator on. */
function reasonBox(page: Page) {
  return page.getByPlaceholder('请说明需要补充或申请的内容……');
}

/**
 * The dialog closing is the signal, never the toast.
 *
 * Toasts here auto-dismiss, so waiting on one makes a step depend on winning a
 * race it does not control — the record review step lost exactly that race once
 * on a decision that had in fact landed.
 */
/**
 * Clicks a dialog's 提交 and waits for **the request it makes**, not for the
 * dialog to disappear.
 *
 * Waiting on the dialog is what the other flows do, and it is wrong here in a
 * way that only shows up with the `pace` gate off. `fill()` sets the DOM value
 * and dispatches an event; React's state update is scheduled, not immediate.
 * With a pause after every action that difference never matters, so this flow
 * passed as a demonstration and failed as a verification — the click arrived
 * before the state did, the handler saw an empty field, returned early without
 * calling anything, and the step still reported success because the *absence*
 * of a dialog reads exactly like a dialog that closed.
 *
 * A response is the honest signal: it exists only if the app really asked the
 * server to do the thing, and its status says whether the server agreed. The
 * dialog is checked afterwards too, since closing is what the student sees.
 */
async function submitDialog(page: Page): Promise<void> {
  const submit = page.getByRole('button', { name: '提交', exact: true }).last();
  assertThat(await submit.isVisible().catch(() => false), '提交对话框应当处于打开状态');

  const posted = page.waitForResponse(
    r => r.request().method() === 'POST' && r.url().includes('/api/v1/cases'),
    { timeout: 25_000 });
  await tap(submit, '提交');
  const response = await posted;
  assertThat(response.ok(), `提交应当被服务端接受，实际返回 ${response.status()}`);

  await submit.waitFor({ state: 'hidden', timeout: 25_000 });
}
