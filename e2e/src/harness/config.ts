import { existsSync, readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join, resolve } from 'node:path';
import { loadAccounts, type Account } from './accounts.js';

export interface Config {
  baseUrl: string;
  repoRoot: string;
  evidenceDir: string;
  artifactsDir: string;
  narrationDir: string;
  admin: Account;
  student: Account;
  headless: boolean;
  closeAtEnd: boolean;
  slowMo: number;
  /**
   * The maintenance gate's bypass key, when the target server has one.
   *
   * A gated server answers **every** request with the bilingual 503 page —
   * `/api/*` included (`Docs/operations/MaintenanceMode.md`) — so without this
   * the first navigation lands on the maintenance page and the run fails on a
   * missing login form, which reads like a broken selector rather than a closed
   * door. Empty on a server with no gate, which is most of them.
   */
  bypassKey: string;
}

/**
 * Everything the run needs, resolved once and fail-fast.
 *
 * No hostname or credential is written here. The base URL comes from
 * `~/.timebank.env` (the same file `frontend/scripts/deploy.sh` sources) or from
 * `TIMEBANK_BASE_URL`; the accounts come from `Docs/TestAccounts.md`. A missing
 * value fails immediately, naming what to set — never a placeholder fallback,
 * which is the mistake issue 027 cost a day to find.
 */
export function loadConfig(cli: {
  headless: boolean;
  close: boolean;
  slowMo: number;
  evidenceDir?: string;
}): Config {
  const repoRoot = resolve(new URL('../../..', import.meta.url).pathname);
  const baseUrl = (process.env.TIMEBANK_BASE_URL ?? readFromEnvFile('TIMEBANK_BASE_URL') ?? '').replace(/\/$/, '');
  if (!baseUrl) {
    throw new Error(
      'TIMEBANK_BASE_URL is not set. Export it, or put it in ~/.timebank.env '
      + '(see Docs/operations/DeploymentEnv.md).',
    );
  }

  // `demo-evidence`, not `test-evidence`. The latter holds a genuine volunteer
  // log sheet carrying a real school's name and crest — nothing committed, since
  // both folders sit outside the repo, but a demonstration puts whatever it
  // uploads on a projector, and that is the exposure this repo has twice had to
  // rewrite its history to undo. The demo set is the same shape of document
  // built from the placeholder names (`scripts/make-demo-evidence.ts`).
  // `EVIDENCE_DIR` still points anywhere you like.
  const evidenceDir = resolve(cli.evidenceDir ?? process.env.EVIDENCE_DIR ?? join(repoRoot, '..', 'demo-evidence'));
  if (!existsSync(evidenceDir)) {
    throw new Error(
      `Evidence directory not found: ${evidenceDir}. Generate it with `
      + '`pnpm tsx scripts/make-demo-evidence.ts`, or set EVIDENCE_DIR / --evidence-dir. '
      + 'Without it the upload step is skipped and submit fails later with a confusing EVIDENCE_REQUIRED.',
    );
  }

  // Scoped by host: the file documents two servers and both call their
  // administrator `admin001`, so an unscoped read is a coin toss.
  const { admin, student } = loadAccounts(
    join(repoRoot, 'Docs', 'TestAccounts.md'), new URL(baseUrl).hostname);

  return {
    baseUrl,
    // Same source as everything else here: the environment, or ~/.timebank.env.
    // Never a literal — this one is a real key.
    bypassKey: process.env.TIMEBANK_MAINTENANCE_BYPASS_KEY
      ?? readFromEnvFile('TIMEBANK_MAINTENANCE_BYPASS_KEY')
      ?? '',
    repoRoot,
    evidenceDir,
    artifactsDir: join(repoRoot, 'e2e', 'artifacts'),
    narrationDir: join(repoRoot, 'e2e', 'narration'),
    admin,
    student,
    headless: cli.headless,
    // --headless implies --close: an unattended run that waits for someone to
    // close a window nobody can see would hang forever.
    closeAtEnd: cli.close || cli.headless,
    slowMo: cli.slowMo,
  };
}

/** Reads one `export NAME=value` from ~/.timebank.env without sourcing it. */
function readFromEnvFile(name: string): string | null {
  const path = join(homedir(), '.timebank.env');
  if (!existsSync(path)) return null;
  // Last assignment wins, matching shell semantics — the file carries more than
  // one block and the later one is the active server.
  let found: string | null = null;
  for (const line of readFileSync(path, 'utf8').split('\n')) {
    const m = line.match(new RegExp(`^\\s*(?:export\\s+)?${name}=(.*)$`));
    if (m) found = m[1].trim().replace(/^["']|["']$/g, '');
  }
  return found || null;
}
