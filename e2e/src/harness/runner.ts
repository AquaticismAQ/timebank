import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import type { Page } from 'playwright';
import { AssertionFailure } from './assert.js';
import { BrowserHarness } from './browser.js';
import type { Config } from './config.js';
import { Api } from './api.js';
import { Narrator } from './narrator.js';
import { SlotAllocator } from './slots.js';
import type { Flow, FlowContext, Gate } from './types.js';
import { signIn } from '../steps/auth.js';
import { bindStage } from './stage.js';

export interface RunOutcome {
  flowId: string;
  ok: boolean;
  failedPhase?: string;
  reason?: string;
}

/**
 * Runs the phases a profile's gates select, announcing each one and stopping at
 * the first failure.
 *
 * The gate filter lives here rather than inside the flow, so a flow file stays a
 * description of the workflow and every phase is written exactly once. Skipped
 * phases are reported, not silently dropped — "it passed" and "it did not run"
 * must never look the same.
 */
/** What the narration says the first time a role is used. */
const FIRST_LOGIN_ZH: Record<'admin' | 'student', string> = {
  admin: '以老师的身份登录管理后台',
  student: '以学生的身份登录学生端',
};

/** And when the demonstration changes seats. */
const SWITCH_ZH: Record<'admin' | 'student', string> = {
  admin: '现在切换到老师的账号',
  student: '现在切换到学生的账号',
};

export async function runFlow(
  flow: Flow,
  config: Config,
  gates: Set<Gate>,
  runId: string,
  browser: BrowserHarness,
  narrator: Narrator,
): Promise<RunOutcome> {
  const artifactDir = join(config.artifactsDir, runId);
  mkdirSync(artifactDir, { recursive: true });

  // A student-scoped API session, used only for reads the UI makes expensive:
  // the existing records the allocator needs, and balances as numbers.
  const api = await Api.loginAs(config, config.student.username, config.student.password);
  // `/me` nests the account under `user` — the envelope is {data:{user:{…}}},
  // not {data:{…}}.
  const me = await api.get<{ user: { studentId: string | null; displayName: string } }>('/me');
  if (!me.user.studentId) {
    throw new Error(`${config.student.username} has no linked student record — cannot allocate service windows.`);
  }
  const slots = new SlotAllocator(api, me.user.studentId);
  await slots.load();
  const adminApi = await Api.loginAs(config, config.admin.username, config.admin.password);
  const startedAt = new Date();

  let currentRole: 'admin' | 'student' | null = null;
  const ctx: FlowContext = {
    config, api, adminApi, narrator, slots, runId,
    has: (gate: Gate) => gates.has(gate),
    as: async (role: 'admin' | 'student'): Promise<Page> => {
      const account = role === 'admin' ? config.admin : config.student;
      const prefix = role === 'admin' ? '/admin' : '/student';

      // Said out loud, because a demonstration that changes seats without saying
      // so looks like one person doing everything — which is the opposite of the
      // point. The workflow's whole shape is that a student and a teacher each do
      // their own part and neither can do the other's.
      if (currentRole !== role) {
        narrator.step(currentRole === null ? FIRST_LOGIN_ZH[role] : SWITCH_ZH[role]);
        currentRole = role;
      }

      const page = await browser.open(role);
      // The stage needs the narrator and the profile's pacing; binding here
      // keeps every step function's signature as it was.
      bindStage(page, {
        narrator,
        paced: gates.has('pace'),
        visible: !config.headless,
      });
      // Once per role per run. The context persists, so a later switch back is a
      // tab change rather than another trip through the login form.
      if (!page.url().includes(prefix)) {
        await signIn(page, config.baseUrl, account, prefix, role === 'admin' ? 'dark' : 'light');
      }
      return page;
    },
    page: () => browser.page(),
    state: { studentId: me.user.studentId, studentName: me.user.displayName, startedAt },
  };

  narrator.phase(flow.titleZh);

  for (const phase of flow.phases) {
    if (phase.gate && !gates.has(phase.gate)) {
      narrator.note(`(skipped: ${phase.titleZh} — gate '${phase.gate}' is off)`);
      continue;
    }
    narrator.phase(phase.titleZh);
    try {
      await phase.run(ctx);
    } catch (error) {
      const reason = error instanceof Error ? error.message : String(error);
      const kind = error instanceof AssertionFailure ? '断言失败' : '执行出错';
      await browser.screenshot(join(artifactDir, `${safe(phase.titleZh)}.png`));
      narrator.result(`${kind}：${phase.titleZh}`);
      narrator.note(reason);
      narrator.note(`screenshot: ${join(artifactDir, `${safe(phase.titleZh)}.png`)}`);
      return { flowId: flow.id, ok: false, failedPhase: phase.titleZh, reason };
    }
  }
  return { flowId: flow.id, ok: true };
}

function safe(s: string): string {
  return s.replace(/[^\p{L}\p{N}]+/gu, '-').slice(0, 40);
}
