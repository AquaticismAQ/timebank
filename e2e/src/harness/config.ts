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

  const evidenceDir = resolve(cli.evidenceDir ?? process.env.EVIDENCE_DIR ?? join(repoRoot, '..', 'test-evidence'));
  if (!existsSync(evidenceDir)) {
    throw new Error(
      `Evidence directory not found: ${evidenceDir}. Set EVIDENCE_DIR or pass --evidence-dir. `
      + 'Without it the upload step is skipped and submit fails later with a confusing EVIDENCE_REQUIRED.',
    );
  }

  const { admin, student } = loadAccounts(join(repoRoot, 'Docs', 'TestAccounts.md'));

  return {
    baseUrl,
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
