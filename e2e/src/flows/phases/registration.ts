import { assertRegistrantConfirmed, assertStudentSeesRegistered, closeRegistrantList, openRegistrantList, registerForProject } from '../../steps/registration.js';
import type { Phase } from '../../harness/types.js';

/**
 * The student signs up, and the school confirms them.
 *
 * Asserted on **both** portals. Either side alone can pass while the other is
 * broken — a registration that the student sees but the school's list never
 * shows is exactly the failure worth catching, and it looks fine from one seat.
 */
export const registration: Phase = {
  titleZh: '学生报名，学校核对名单',
  async run(ctx) {
    const title = ctx.state.projectTitle as string;

    const studentPage = await ctx.as('student');
    ctx.narrator.step('学生在项目中心找到这个项目并报名');
    await registerForProject(studentPage, ctx.config.baseUrl, title);
    await assertStudentSeesRegistered(studentPage, ctx.config.baseUrl, title);
    ctx.narrator.result('学生端显示已报名');

    const adminPage = await ctx.as('admin');
    ctx.narrator.step('教师打开报名名单，核对这名学生');
    await openRegistrantList(adminPage, ctx.config.baseUrl, title);
    await assertRegistrantConfirmed(adminPage, ctx.state.studentName as string);
    // Said plainly because it is the product's actual rule and a viewer would
    // otherwise wonder where the approval step went.
    ctx.narrator.result('报名已确认，学生可以开始服务');
    await closeRegistrantList(adminPage);
  },
};
