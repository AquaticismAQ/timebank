import {
  CASE_STATUS, assertCaseHolds, assertCaseStatus, decideOnCase, fileCase, respondToCase, showCaseHistory,
} from '../steps/caseflow.js';
import { assertContains } from '../harness/assert.js';
import { proposalPreflight } from './phases/proposal/preflight.js';
import type { Flow, Phase } from '../harness/types.js';

/**
 * A student disagrees with the record, and the school answers them.
 *
 * The other two flows both end in a decision going the student's way, which is
 * the easy half of any review system. This one is about what happens when the
 * student thinks the system has it wrong: they say so, the teacher cannot
 * decide on what they were given and asks for more, the student answers, and
 * only then is it resolved.
 *
 * Worth showing to a school for a reason the other flows cannot demonstrate:
 * **the return is not a rejection.** A case sent back stays the student's case,
 * keeps everything they already wrote, and comes back to the same reviewer with
 * an answer attached — so the record afterwards reads as a conversation with
 * dates on it, which is what makes it answerable to a parent asking why.
 */

/** Written once, and checked again after the teacher sends the case back. */
const ORIGINAL_CLAIM =
  '上学期期末校运会，我在检录处做了两天志愿服务，但这段时长一直没有进入我的记录。'
  + '当时是临时被老师叫去帮忙的，没有走正式的项目报名。';

/** The question the teacher asks, and the words the student must be shown when they reply. */
const WHAT_THE_TEACHER_NEEDS =
  '这件事我有印象，但需要你补充三件具体的信息才能认定：具体是哪两天、当时带队或叫你去的是哪位老师、'
  + '以及有没有当天的照片、签到表或其他可以佐证的材料。';

const fileTheCase: Phase = {
  titleZh: '学生提交申诉',
  async run(ctx) {
    const page = await ctx.as('student');
    const title = `[AUTO] ${ctx.runId} 校运会志愿服务时长认定申请`;
    ctx.state.caseTitle = title;

    ctx.narrator.step('学生发起一份申诉，说明有一段服务时长没有被记录');
    await fileCase(page, ctx.config.baseUrl, title, ORIGINAL_CLAIM);
    await assertCaseStatus(page, ctx.config.baseUrl, title, CASE_STATUS.submitted);
    ctx.narrator.result('申诉已提交，等待老师处理');
  },
};

const requestInformation: Phase = {
  titleZh: '老师要求补充信息后再认定',
  async run(ctx) {
    const title = ctx.state.caseTitle as string;
    const page = await ctx.as('admin');

    ctx.narrator.step('老师打开这份申诉，读完学生的说明，再查看有没有可佐证的材料');
    await decideOnCase(page, ctx.config.baseUrl, title, 'requestInformation', WHAT_THE_TEACHER_NEEDS, {
      // The teacher has to have read the claim before answering it, and the
      // workbench has to be showing *this* student's words rather than
      // whichever case happened to open.
      expectOnScreen: [ORIGINAL_CLAIM],
      // And the reason the answer is "tell me more" rather than yes or no:
      // 相关材料 is empty. The question asked below names exactly what is
      // missing from that tab, so the audience sees the judgement being formed
      // rather than announced.
      alsoRead: ['materials'],
    });

    const studentPage = await ctx.as('student');
    await assertCaseStatus(studentPage, ctx.config.baseUrl, title, CASE_STATUS.needsInformation);
    ctx.narrator.result('申诉退回学生，并附上了老师具体想知道的三件事');
  },
};

const respond: Phase = {
  titleZh: '学生补充信息后重新提交',
  async run(ctx) {
    const title = ctx.state.caseTitle as string;
    const page = await ctx.as('student');

    ctx.narrator.step('学生看到老师的问题，逐条作答后重新提交');
    await respondToCase(page, ctx.config.baseUrl, title,
      '两天分别是 5 月 16 日和 5 月 17 日下午；当时是体育组的陈老师临时叫我去检录处帮忙的；'
      + '我手机里有那两天在检录处的照片，检录表上也有我的签名，可以随时提供。',
      // The teacher's own words have to be on screen while the student answers.
      WHAT_THE_TEACHER_NEEDS);

    await assertCaseStatus(page, ctx.config.baseUrl, title, CASE_STATUS.submitted);
    // The reply is appended, never substituted: what the student wrote at the
    // start is still there underneath it. A flow that only checked the status
    // came back to 已提交 would pass with the original claim silently gone.
    await assertCaseHolds(page, ctx.config.baseUrl, title, ORIGINAL_CLAIM, '学生回复');
    ctx.narrator.result('补充说明已提交，申诉回到老师处，原来的说明一并保留');
  },
};

const resolve: Phase = {
  titleZh: '老师认定并解决申诉',
  async run(ctx) {
    const title = ctx.state.caseTitle as string;
    const page = await ctx.as('admin');

    ctx.narrator.step('老师重新打开申诉，对照自己提的问题读学生的答复，确认信息齐备');
    await decideOnCase(page, ctx.config.baseUrl, title, 'resolve',
      '已与体育组核实，情况属实。两天的服务时长将按校运会志愿服务补录，后续以正式记录为准。', {
      // The thing actually being decided on is the answer, so it has to be on
      // screen. 处理历史 is where it sits next to the question it answers —
      // which is the view that makes this a second look rather than a first.
      expectOnScreen: ['学生回复'],
      alsoRead: ['history'],
    });

    const studentPage = await ctx.as('student');
    await assertCaseStatus(studentPage, ctx.config.baseUrl, title, CASE_STATUS.resolved);
    ctx.narrator.result('申诉已解决，学生可以看到处理结果与说明');
  },
};

/**
 * The trail the whole flow exists to produce.
 *
 * Gated on `audit` rather than run always: a demonstration ends on the student
 * seeing their answer, which is the human ending. This is the one a school's
 * office asks for afterwards — every step, who made it and when, in one place.
 */
const caseHistory: Phase = {
  titleZh: '查看这份申诉的完整处理历史',
  gate: 'audit',
  async run(ctx) {
    const title = ctx.state.caseTitle as string;
    const page = await ctx.as('admin');

    ctx.narrator.step('打开这份申诉的处理历史，逐条核对');
    const history = await showCaseHistory(page, ctx.config.baseUrl, title);
    // Matched on the entry types, which are the contract, rather than on the
    // descriptions beside them, which the backend composes in English because
    // it authors no localized copy.
    //
    // The return is asserted first and deliberately. A history holding only the
    // outcome would look tidy and be worthless — what makes an appeal trail
    // answerable to a parent is that the school's question, and the date it was
    // asked, are in it as well as the answer.
    assertContains(history, 'decision.request_information', '处理历史应当记下老师要求补充信息这一步');
    assertContains(history, 'submitted', '处理历史应当记下学生补充后的重新提交');
    assertContains(history, 'decision.resolve', '处理历史应当记下最终的处理结果');
    ctx.narrator.result('处理历史完整：要求补充、重新提交、解决，每一步都有时间与处理人');
  },
};

export const caseWorkflow: Flow = {
  id: 'case-workflow',
  titleZh: '学生申诉全流程演示',
  phases: [proposalPreflight, fileTheCase, requestInformation, respond, resolve, caseHistory],
};
