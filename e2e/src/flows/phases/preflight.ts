import { existsSync } from 'node:fs';
import { assertThat } from '../../harness/assert.js';
import { pickEvidenceFiles } from '../../steps/evidence.js';
import { SlotAllocator, type Slot } from '../../harness/slots.js';
import type { Phase } from '../../harness/types.js';

/**
 * Fails before anything is created.
 *
 * That ordering is the whole point. Every later phase leaves something permanent
 * behind — a project that can only be cancelled, an enrolment, a record that can
 * never be deleted once submitted. A configuration or capacity problem discovered
 * at submit has already cost all of that; discovered here it costs nothing.
 */
export const preflight: Phase = {
  titleZh: '运行前检查',
  async run(ctx) {
    ctx.narrator.step('检查配置与账号');
    ctx.narrator.note(`server: ${ctx.config.baseUrl}`);
    ctx.narrator.note(`run id: ${ctx.runId}`);

    const adminPage = await ctx.as('admin');
    assertThat(adminPage.url().includes('/admin'), '管理员应当能够登录并进入管理后台');

    const studentPage = await ctx.as('student');
    assertThat(studentPage.url().includes('/student'), '学生应当能够登录并进入学生端');
    ctx.narrator.result('管理员与学生账号均可登录');

    ctx.narrator.step('确认学生所在年级');
    // The project is made eligible for exactly this grade, so it has to be known
    // before anything is created — and a student with no grade would otherwise
    // fail much later, at registration, looking like a permissions problem.
    const student = await ctx.api.get<{ gradeId: string }>(`/students/${ctx.state.studentId as string}`);
    const grades = await ctx.api.get<Array<{ id: string; name: string }>>('/grades');
    const grade = grades.find(g => g.id === student.gradeId);
    assertThat(Boolean(grade), `学生应当有所属年级（gradeId=${student.gradeId}）`);
    ctx.state.studentGradeName = grade!.name;
    ctx.narrator.result(`该学生就读于${grade!.name}`);

    ctx.narrator.step('检查佐证材料');
    const needed = recordCount(ctx.has('branch:return'), ctx.has('branch:reject'));
    const files = pickEvidenceFiles(ctx.config.evidenceDir, needed);
    for (const file of files) {
      assertThat(existsSync(file), `佐证文件应当存在：${file}`);
    }
    ctx.state.evidenceFiles = files;
    ctx.narrator.note(`evidence: ${files.map(f => f.split('/').pop()).join(', ')}`);

    ctx.narrator.step('预留服务记录时段');
    // Allocated here, not at filing time, so a shortage is found before a project
    // and an enrolment have been created and left behind permanently.
    const slots: Slot[] = ctx.slots.allocate(needed);
    ctx.state.slots = slots;
    if (SlotAllocator.driftedFromToday(slots)) {
      // Said out loud rather than logged quietly: it is why the demo's dates may
      // not read as today, and silence here would look like a bug.
      ctx.narrator.note('note: today is full for this student, using earlier dates');
    }
    ctx.narrator.note(`slots: ${slots.map(s => `${s.date} ${s.startHHMM}-${s.endHHMM}`).join(' | ')}`);
    ctx.narrator.result(`已预留 ${slots.length} 个可用服务时段`);
  },
};

function recordCount(withReturn: boolean, withReject: boolean): number {
  return 1 + (withReturn ? 1 : 0) + (withReject ? 1 : 0);
}
