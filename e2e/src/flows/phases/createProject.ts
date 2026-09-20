import { assertProjectIsOpenForRegistration, createAndPublishProject } from '../../steps/project.js';
import type { Phase } from '../../harness/types.js';

/** What the school recognises for attending — and therefore what the balance must rise by. */
export const RECOGNIZED_HOURS = 2;

/**
 * A teacher creates a volunteer project and publishes it.
 *
 * The status is read back from the list row rather than trusted from the create
 * response: `registrationOpen` is time-derived and synced lazily on read, so the
 * row is the only place the real answer appears.
 */
export const createProject: Phase = {
  titleZh: '教师创建并发布志愿服务项目',
  async run(ctx) {
    const page = await ctx.as('admin');
    const title = `[AUTO] ${ctx.runId} 社区图书馆志愿服务`;
    ctx.state.projectTitle = title;
    ctx.state.recognizedHours = RECOGNIZED_HOURS;

    ctx.narrator.step('填写项目信息：名称、时间、地点、名额与适用年级');
    await createAndPublishProject(page, ctx.config.baseUrl, {
      title,
      summary: '协助社区图书馆整理书架、引导读者借阅。',
      description: '学生前往社区图书馆，协助工作人员整理书架、登记图书并引导读者借阅，由带队教师统一考勤。',
      recognizedHours: RECOGNIZED_HOURS,
      location: '社区图书馆',
      capacity: 20,
      // Exactly the student's own grade, looked up in preflight. Making it
      // eligible for every grade would pass without demonstrating that
      // eligibility does anything.
      eligibleGradeNames: [ctx.state.studentGradeName as string],
    });

    ctx.narrator.step('核对项目状态');
    await assertProjectIsOpenForRegistration(page, ctx.config.baseUrl, title);
    ctx.narrator.result('项目已发布，现在处于报名开放状态');
  },
};
