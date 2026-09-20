import { loadConfig } from './src/harness/config.js';
import { BrowserHarness } from './src/harness/browser.js';
import { Narrator } from './src/harness/narrator.js';
import { newRunId } from './src/harness/runId.js';
import { runFlow, type RunOutcome } from './src/harness/runner.js';
import { ALL_GATES, PROFILES, resolveGates } from './src/harness/profiles.js';
import { FLOWS, findFlow } from './src/flows/index.js';
import { Api } from './src/harness/api.js';
import { cleanupPreviousRuns, describeCleanup } from './src/harness/cleanup.js';

/**
 * `pnpm e2e [flow-id] [options]`
 *
 * Defaults to the `demo` profile because that is the reason this exists: a live
 * demonstration for the school. `--profile verify --headless --close` is the
 * unattended regression shape.
 */
async function main(): Promise<number> {
  const argv = process.argv.slice(2);
  const flag = (name: string) => argv.includes(`--${name}`);
  const value = (name: string): string | undefined => {
    const i = argv.indexOf(`--${name}`);
    return i >= 0 ? argv[i + 1] : undefined;
  };
  const multi = (name: string): string[] =>
    argv.reduce<string[]>((acc, a, i) => (a === `--${name}` && argv[i + 1] ? [...acc, argv[i + 1]] : acc), []);

  if (flag('list')) {
    console.log('flows:    ' + FLOWS.map(f => f.id).join(', '));
    console.log('profiles: ' + Object.entries(PROFILES).map(([k, v]) => `${k} [${v.join(' ')}]`).join('\n          '));
    console.log('gates:    ' + ALL_GATES.join(', '));
    return 0;
  }

  const profile = value('profile') ?? 'demo';
  const gates = resolveGates(profile, multi('gate'), multi('no-gate'));
  const config = loadConfig({
    headless: flag('headless'),
    close: flag('close'),
    slowMo: Number(value('slow-mo') ?? (gates.has('pace') ? 250 : 0)),
    evidenceDir: value('evidence-dir'),
  });

  const cleanupOnly = flag('cleanup-only');
  if (cleanupOnly || flag('cleanup')) {
    const adminApi = await Api.loginAs(config, config.admin.username, config.admin.password);
    const studentApi = await Api.loginAs(config, config.student.username, config.student.password);
    console.log('\ncleaning up earlier [AUTO] runs...');
    for (const line of describeCleanup(await cleanupPreviousRuns(adminApi, studentApi))) {
      console.log(`  ${line}`);
    }
    if (cleanupOnly) return 0;
  }

  const runId = newRunId();
  const narrator = new Narrator(config.narrationDir, runId);
  const browser = new BrowserHarness(config);
  await browser.start();

  const selected = argv[0] && !argv[0].startsWith('--') ? [findFlow(argv[0])] : FLOWS;
  const outcomes: RunOutcome[] = [];
  try {
    for (const flow of selected) {
      outcomes.push(await runFlow(flow, config, gates, runId, browser, narrator));
    }
  } finally {
    report(outcomes, narrator.scriptPath, profile, [...gates]);
    await browser.finish();
  }
  return outcomes.every(o => o.ok) ? 0 : 1;
}

function report(outcomes: RunOutcome[], scriptPath: string, profile: string, gates: string[]): void {
  console.log(`\n─── ${profile} [${gates.join(' ')}] ───`);
  for (const o of outcomes) {
    console.log(o.ok ? `  PASS  ${o.flowId}` : `  FAIL  ${o.flowId} — ${o.failedPhase}: ${o.reason}`);
  }
  console.log(`  narration script: ${scriptPath}`);
}

main().then(code => { process.exitCode = code; }).catch(error => {
  console.error(`\nfatal: ${error instanceof Error ? error.message : String(error)}`);
  process.exitCode = 1;
});
