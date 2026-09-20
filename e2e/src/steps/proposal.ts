import type { Page } from 'playwright';
import { assertContains } from '../harness/assert.js';
import { choose, narrate, tap, type, visit } from '../harness/stage.js';

/**
 * The status chip text, from `labels.proposalStatus` in the zh bundle.
 *
 * Taken from the label map rather than from the review screen's own strings,
 * which are different words for the same states: the chip on a student's row
 * reads 待审核 and 已批准 where the teacher's buttons say 通过. Guessing from the
 * teacher's vocabulary is how this first failed.
 */
export const PROPOSAL_STATUS = {
  draft: '草稿',
  submitted: '待审核',
  changesRequested: '需修改',
  approved: '已批准',
  rejected: '已驳回',
  withdrawn: '已撤回',
} as const;

export interface NewProposal {
  title: string;
  goal: string;
  description: string;
  outcomes: string;
  /** `YYYY-MM-DD`; a proposal plans a *future* activity, unlike a service record. */
  plannedDate: string;
  location: string;
  format: string;
  participants: string;
  tasks: string;
  risk: string;
  materials: string;
  recruitment: string;
  eligibility: string;
  /** Part of a teacher's name; the picker searches the real directory. */
  mentorQuery: string;
  durationHours: number;
  volunteers: number;
  contact: string;
  proof: string;
}

/**
 * The student's proposal wizard: six steps, then a truthfulness declaration.
 *
 * Every step is walked in its own order, which is also the order the form
 * presents them — a proposal is a long piece of writing and jumping around it
 * on screen reads as the script hunting rather than a student composing.
 */
export async function createAndSubmitProposal(page: Page, baseUrl: string, p: NewProposal): Promise<void> {
  await visit(page, `${baseUrl}/student/proposals`, '发起志愿活动页面');
  await tap(page.getByRole('button', { name: '新建提案' }).first(), '新建提案');

  // The six steps, in the order the form presents them. Which field lives on
  // which step is not obvious from the labels — 参与对象 and 志愿者任务分工 read
  // like organisation but belong to the plan — so this follows `STEP_KEYS` in
  // `EventProposals.tsx` rather than intuition.

  // 1 — 活动概述
  await type(field(page, '活动名称'), '活动名称', p.title);
  // The first real option: categories are server-derived (issue 078) and a
  // demonstration should not care which, only that one is chosen.
  await choose(page.locator('select').first(), '服务类别', { index: 1 });
  await type(field(page, '公益目标'), '公益目标', p.goal);
  await type(field(page, '活动说明'), '活动说明', p.description);
  await type(field(page, '预期成果'), '预期成果', p.outcomes);
  await next(page);

  // 2 — 实施计划
  await type(field(page, '计划日期'), '计划日期', p.plannedDate);
  await type(field(page, '活动地点'), '活动地点', p.location);
  await type(field(page, '预计时长'), '预计时长', String(p.durationHours));
  await type(field(page, '预计志愿者人数'), '预计志愿者人数', String(p.volunteers));
  await type(field(page, '活动形式'), '活动形式', p.format);
  await type(field(page, '参与对象'), '参与对象', p.participants);
  await type(field(page, '志愿者任务分工'), '志愿者任务分工', p.tasks);
  await next(page);

  // 3 — 组织安排
  await pickMentor(page, p.mentorQuery);
  await type(field(page, '联系方式'), '联系方式', p.contact);
  await next(page);

  // 4 — 安全与资源
  await type(field(page, '风险识别与安全措施'), '风险识别与安全措施', p.risk);
  await type(field(page, '物资、场地与预算需求'), '物资、场地与预算需求', p.materials);
  await next(page);

  // 5 — 招募与认定
  await type(field(page, '招募方式'), '招募方式', p.recruitment);
  await type(field(page, '报名条件'), '报名条件', p.eligibility);
  await type(field(page, '建议认定时长'), '建议认定时长', String(p.durationHours));
  await type(field(page, '服务证明方式'), '服务证明方式', p.proof);
  await next(page);

  // 6 — 确认提交
  await submitForReview(page);
}

/**
 * Ticks the truthfulness declaration and submits.
 *
 * The declaration is not decoration: submission is refused without it
 * (`checkDeclaration`), and it is the student putting their name to the claim
 * that the plan is real and has been thought through — which is the whole reason
 * a proposal gets a human review rather than being published on request.
 */
export async function submitForReview(page: Page): Promise<void> {
  const declaration = page.locator('input[type="checkbox"]').last();
  narrate(page, '现在勾选真实性声明');
  await declaration.check();
  await tap(page.getByRole('button', { name: '提交审核' }), '提交审核');
  await page.getByRole('button', { name: '提交审核' }).waitFor({ state: 'hidden', timeout: 25_000 });
}

/** Reopens a returned proposal, revises the part the teacher asked about, and sends it back. */
export async function reviseAndResubmit(
  page: Page, baseUrl: string, title: string, revisedRisk: string, expectResourcesIntact?: string,
): Promise<void> {
  await visit(page, `${baseUrl}/student/proposals`, '发起志愿活动页面');
  const row = proposalRow(page, title);
  await row.waitFor({ state: 'visible', timeout: 15_000 });
  await tap(row.getByRole('button', { name: '编辑' }), '编辑');

  // 安全与资源 is the fourth step, so three 下一步 from the first. Walked rather
  // than jumped because the wizard validates the step you are leaving — there is
  // no way past a step with an empty required field, which is the point of it.
  for (let i = 0; i < 3; i++) await next(page);

  // Everything the student wrote is still here. This is issue 116's regression
  // guard: the resource-needs box used to come back empty after a review,
  // because the form kept two fields over one backend column and never split
  // them on the way in. A flow that only checks the proposal reaches the next
  // status would pass with the student's text silently gone.
  if (expectResourcesIntact) {
    const resources = await field(page, '物资、场地与预算需求').inputValue();
    assertContains(resources, expectResourcesIntact,
      '被退回后重新打开提案时，之前填写的物资、场地与预算需求应当原样保留');
  }

  await type(field(page, '风险识别与安全措施'), '补充后的风险识别与安全措施', revisedRisk);
  for (let i = 0; i < 2; i++) await next(page);
  await submitForReview(page);
}

export async function assertProposalStatus(page: Page, baseUrl: string, title: string, expectedZh: string): Promise<void> {
  await visit(page, `${baseUrl}/student/proposals`, '发起志愿活动页面');
  const row = proposalRow(page, title);
  await row.waitFor({ state: 'visible', timeout: 15_000 });
  assertContains(await row.innerText(), expectedZh, `提案「${title}」应当处于「${expectedZh}」状态`);
}

function proposalRow(page: Page, title: string) {
  return page.locator('tr').filter({ hasText: title });
}

/**
 * Names a teacher to sponsor the activity.
 *
 * A search-as-you-type picker (`SearchPicker.tsx`), not an input: the trigger
 * opens a popover with its own search box, and the teacher has to be chosen from
 * real results. That is the point of the field — a proposal names someone who
 * has agreed to be accountable for it, so it cannot be free text.
 */
async function pickMentor(page: Page, query: string): Promise<void> {
  narrate(page, '现在搜索并选择拟邀请的指导教师');
  await page.getByText('按姓名搜索教师').first().click();

  const search = page.getByPlaceholder('输入姓名搜索教师...');
  await search.waitFor({ state: 'visible', timeout: 15_000 });
  await search.fill(query);

  // Results are fetched, so the first option only exists once the request lands.
  const option = page.getByRole('option').first();
  await option.waitFor({ state: 'visible', timeout: 15_000 });
  const chosen = (await option.innerText()).trim();
  await option.click();
  await page.keyboard.press('Escape');
  narrate(page, `已选择指导教师：${chosen}`);
}

function field(page: Page, labelZh: string) {
  return page.locator(
    `xpath=//label[starts-with(normalize-space(.), "${labelZh}")]/following-sibling::*[self::input or self::textarea or self::select][1]`,
  );
}

async function next(page: Page): Promise<void> {
  await tap(page.getByRole('button', { name: '下一步' }), '下一步');
}
