import { PROPOSAL_STATUS, assertProposalStatus, createAndSubmitProposal, reviseAndResubmit } from '../steps/proposal.js';
import { decideOnProposal } from '../steps/review.js';
import { assertProjectIsOpenForRegistration, publishGeneratedProject } from '../steps/project.js';
import { proposalPreflight } from './phases/proposal/preflight.js';
import type { Flow, Phase } from '../harness/types.js';

/**
 * A student's own idea becoming an official school project.
 *
 * The project workflow shows the school running an activity it organised. This
 * one shows the other direction — a student proposes something, the school asks
 * for more thought on one point, the student answers it, and only then does it
 * become a project anyone can sign up for. That back-and-forth is the part worth
 * demonstrating: it is what makes the review a review rather than a gate.
 */
const PLANNED_DAYS_AHEAD = 21;

/** Written once, checked again after the teacher sends the proposal back. */
const RESOURCE_NEEDS = '手工材料一套（彩纸、剪刀、胶水），场地由敬老院活动室提供；预计费用约 200 元，申请学生会支持。';

/** Deliberately thin. This is the sentence the teacher opens the tab to find. */
const THIN_RISK = '注意安全。';

/** What the student writes instead, and what the teacher has to see before approving. */
const REVISED_RISK =
  '往返由学校统一安排校车，全程由带队教师陪同；现场分三组，每组一名联络员；'
  + '老人如出现不适，立即通知院方护理人员并停止活动，同时电话联系带队教师与家长。';

function plannedDate(): string {
  const d = new Date();
  d.setDate(d.getDate() + PLANNED_DAYS_AHEAD);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

const propose: Phase = {
  titleZh: '学生提交志愿活动提案',
  async run(ctx) {
    const page = await ctx.as('student');
    const title = `[AUTO] ${ctx.runId} 社区敬老院陪伴计划`;
    ctx.state.proposalTitle = title;

    ctx.narrator.step('学生填写活动提案：目标、计划、组织安排、安全与招募');
    await createAndSubmitProposal(page, ctx.config.baseUrl, {
      title,
      goal: '让社区敬老院的老人获得稳定的陪伴，同时让同学学会倾听与照护。',
      description: '每两周一次，由同学分组前往敬老院，陪老人聊天、读报、做简单手工。',
      outcomes: '预计服务 12 人次，累计志愿时长约 24 小时。',
      plannedDate: plannedDate(),
      location: '社区敬老院',
      format: '现场陪伴与手工活动',
      participants: '六至八年级同学，每次 6 人',
      tasks: '陪聊两人、读报两人、手工带领两人，另设一名联络员负责与院方对接。',
      mentorQuery: 'Alex',
      durationHours: 2,
      volunteers: 6,
      contact: 'alex.li@example-township-school.edu',
      proof: '签到表与带队教师确认函',
      // Left thin on purpose: this is the point the teacher asks about.
      risk: THIN_RISK,
      materials: RESOURCE_NEEDS,
      recruitment: '班级群公告与学生会渠道',
      eligibility: '全校学生均可，需提前参加一次简短培训。',
    });
    await assertProposalStatus(page, ctx.config.baseUrl, title, PROPOSAL_STATUS.submitted);
    ctx.narrator.result('提案已提交，等待老师审核');
  },
};

const requestChanges: Phase = {
  titleZh: '老师要求补充后再报',
  async run(ctx) {
    const title = ctx.state.proposalTitle as string;
    const page = await ctx.as('admin');
    ctx.narrator.step('老师打开提案，翻到组织与安全这一部分，看学生的风险措施写了什么');
    await decideOnProposal(page, ctx.config.baseUrl, title, 'requestChanges',
      '活动方向很好。但风险与应对措施只写了“注意安全”，请补充：往返交通如何安排、现场由谁带队、老人突发不适时的处理流程。',
      {
        // The comment below quotes the student's own words back at them, so
        // those words have to be on screen first. Otherwise the demonstration
        // is a teacher with an opinion about a field nobody was shown — and the
        // audience has no way to tell whether the criticism is fair.
        alsoRead: ['organization'],
        expectOnScreen: [THIN_RISK],
      });

    const studentPage = await ctx.as('student');
    await assertProposalStatus(studentPage, ctx.config.baseUrl, title, PROPOSAL_STATUS.changesRequested);
    ctx.narrator.result('提案退回学生，附上了具体的修改意见');
  },
};

const revise: Phase = {
  titleZh: '学生按意见补充后重新提交',
  async run(ctx) {
    const title = ctx.state.proposalTitle as string;
    const page = await ctx.as('student');
    ctx.narrator.step('学生按照老师的意见补充安全方案');
    await reviseAndResubmit(page, ctx.config.baseUrl, title,
      REVISED_RISK,
      // Checked on the way past: a returned proposal must still hold everything
      // the student wrote before it was sent back (issue 116).
      RESOURCE_NEEDS);
    await assertProposalStatus(page, ctx.config.baseUrl, title, PROPOSAL_STATUS.submitted);
    ctx.narrator.result('提案已重新提交');
  },
};

const approve: Phase = {
  titleZh: '老师通过提案，自动生成项目草稿',
  async run(ctx) {
    const title = ctx.state.proposalTitle as string;
    const page = await ctx.as('admin');
    ctx.narrator.step('老师回到组织与安全这一部分，对照自己提的三点读学生补充后的方案');
    await decideOnProposal(page, ctx.config.baseUrl, title, 'approve',
      '安全方案已经写清楚，同意立项。',
      // 写清楚了 is a judgement about a specific paragraph, and this is the one.
      // Reading it also proves the revision reached the reviewer at all — an
      // approval granted over the old thin sentence would look identical.
      { alsoRead: ['organization'], expectOnScreen: [REVISED_RISK] });

    const studentPage = await ctx.as('student');
    await assertProposalStatus(studentPage, ctx.config.baseUrl, title, PROPOSAL_STATUS.approved);
    // Approval generates the official project draft in the same transaction and
    // backfills `approvedProjectId` — the project is never created separately
    // and linked afterwards (`APIContract.md` §3).
    ctx.narrator.result('提案通过，系统已自动生成对应的项目草稿');
  },
};

const publish: Phase = {
  titleZh: '老师发布项目，学生可以报名',
  async run(ctx) {
    const title = ctx.state.proposalTitle as string;
    const page = await ctx.as('admin');
    ctx.narrator.step('老师在项目管理中找到这份草稿并发布');
    await publishGeneratedProject(page, ctx.config.baseUrl, title);
    await assertProjectIsOpenForRegistration(page, ctx.config.baseUrl, title);
    ctx.narrator.result('项目已发布，同学们现在可以报名参加');
  },
};

export const proposalWorkflow: Flow = {
  id: 'proposal-workflow',
  titleZh: '学生提案全流程演示',
  phases: [proposalPreflight, propose, requestChanges, revise, approve, publish],
};
