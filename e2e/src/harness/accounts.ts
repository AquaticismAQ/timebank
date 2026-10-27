import { readFileSync, existsSync } from 'node:fs';

export interface Account {
  username: string;
  password: string;
  label: string;
}

/**
 * Credentials come from `Docs/TestAccounts.md`, which is gitignored and already
 * the single source of truth for these accounts. Nothing in this package holds a
 * username or password, matching `backend/scripts/deploy.sh`'s rule that secrets
 * live in the environment and never in the script.
 *
 * Environment variables override, so a machine without that file (or a different
 * server) can still run: `E2E_ADMIN_USERNAME` / `E2E_ADMIN_PASSWORD` /
 * `E2E_STUDENT_USERNAME` / `E2E_STUDENT_PASSWORD`.
 */
const ROW = /^\|\s*`([^`]+)`\s*\|\s*`([^`]+)`\s*\|\s*([^|]*?)\s*\|/;

export function loadAccounts(testAccountsPath: string): { admin: Account; student: Account } {
  const fromEnv = (prefix: string): Account | null => {
    const username = process.env[`${prefix}_USERNAME`];
    const password = process.env[`${prefix}_PASSWORD`];
    return username && password ? { username, password, label: username } : null;
  };

  const envAdmin = fromEnv('E2E_ADMIN');
  const envStudent = fromEnv('E2E_STUDENT');
  if (envAdmin && envStudent) {
    return { admin: envAdmin, student: envStudent };
  }

  if (!existsSync(testAccountsPath)) {
    throw new Error(
      `No credentials. Either set E2E_ADMIN_USERNAME/PASSWORD and `
      + `E2E_STUDENT_USERNAME/PASSWORD, or make ${testAccountsPath} readable.`,
    );
  }

  const rows = readFileSync(testAccountsPath, 'utf8').split('\n')
    .map(line => line.match(ROW))
    .filter((m): m is RegExpMatchArray => m !== null)
    .map(m => ({ username: m[1], password: m[2], label: m[3] }));

  const admin = envAdmin ?? rows.find(r => r.username === (process.env.E2E_ADMIN_USERNAME ?? 'admin001'));
  // Staff keep readable names; a student login is 8 digits, which is what a real
  // roster import produces (`Design/*/10-StudentActivationFlow.md` §3). Picking by
  // shape rather than by position means the file can be reordered freely.
  const student = envStudent
    ?? rows.find(r => r.username === process.env.E2E_STUDENT_USERNAME)
    ?? rows.find(r => /^\d{8}$/.test(r.username));

  if (!admin) throw new Error(`No admin account found in ${testAccountsPath} (looked for admin001).`);
  if (!student) throw new Error(`No student account found in ${testAccountsPath} (looked for an 8-digit username).`);
  return { admin, student };
}
