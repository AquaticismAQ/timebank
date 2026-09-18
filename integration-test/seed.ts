#!/usr/bin/env -S npx tsx
// Seeds integration-test/mock-data.ts onto the TimeBank shared test server.
// Read integration-test/README.md before running this. Hybrid approach:
// organizations/users/students are fast direct-SQL INSERTs (executed on
// the server over SSH, same recipe as Docs/TestAccounts.md's manual seed);
// projects/service-records/event-proposals are driven through the REAL
// HTTP API as the real personas, so the review queue, ledger, and
// notifications are genuinely exercised rather than hand-typed into a
// plausible-looking but workflow-inconsistent state.
//
// Required env vars (no secrets are hardcoded here):
//   TIMEBANK_DB_USERNAME, TIMEBANK_DB_PASSWORD   the app DB account (timebank_app)
// Optional (real defaults live in the untracked test-server-credits.md — this
// repo is public, so the fallbacks below are placeholders, not the real host):
//   TIMEBANK_SSH_TARGET   default root@tb.example.com
//   TIMEBANK_BASE_URL     default https://tb.example.com
//
// Usage:  cd integration-test && npm install && npm run seed

import { spawnSync } from 'node:child_process';
import { randomBytes, randomUUID } from 'node:crypto';
import bcrypt from 'bcryptjs';
import {
  organizations, users, students, projects, serviceRecords, eventProposals, cases,
  namedClasses, expectedCoinsByUsername, reviewerForGrade, generatedStudents, cohortAcademicYearStarts,
  type SeedProject, type SeedServiceRecord, type SeedEventProposal, type SeedCase, type SeedUser,
} from './mock-data.ts';

// ── Config ──────────────────────────────────────────────────────────────

const SSH_TARGET = process.env.TIMEBANK_SSH_TARGET ?? 'root@tb.example.com';
const BASE_URL = process.env.TIMEBANK_BASE_URL ?? 'https://tb.example.com';

/**
 * Direct-connection mode (issue 101). Set `TIMEBANK_DB_HOST` and the SQL phase
 * talks to MySQL over TCP instead of shelling through SSH.
 *
 * This is what makes the Docker stack seedable. Without it the seeder could
 * only reach a database that happened to sit on the far end of an SSH session,
 * so a contributor could bring the local stack up and then have no way to put
 * data in it — which is most of the point of having it up.
 *
 * The remote path stays the default and is untouched: on the shared server the
 * database is not reachable over TCP from a workstation at all (VPC-internal,
 * `08-mlps-level1-security-design-guide.md` §3), so SSH is not a stylistic
 * choice there.
 */
// `TIMEBANK_SEED_DB_HOST`, deliberately not `TIMEBANK_DB_HOST`. That name was
// taken: `~/.timebank.env` and `MySqlTestSchemaExtension` both already use it
// for the integration-test database, so keying direct mode off it meant that
// merely sourcing the standard env file silently switched the seeder from SSH
// to TCP — and pointed it at whatever MySQL happened to be on the workstation's
// own 127.0.0.1. That failed with "Access denied for user
// 'timebank_app'@'localhost'" from a completely unrelated server, which reads
// as a credentials problem with the shared box rather than as the seeder having
// quietly changed transport. A distinct name cannot be turned on by accident.
const DB_HOST = process.env.TIMEBANK_SEED_DB_HOST;
const DB_PORT = process.env.TIMEBANK_SEED_DB_PORT ?? '3306';
const DB_NAME = process.env.TIMEBANK_SEED_DB_NAME ?? 'timebank';
const USE_DIRECT_DB = Boolean(DB_HOST);
const DB_USERNAME = requireEnv('TIMEBANK_DB_USERNAME');
const DB_PASSWORD = requireEnv('TIMEBANK_DB_PASSWORD');

const sleep = (ms: number) => new Promise<void>(resolve => setTimeout(resolve, ms));

/** Round-robin records across their owning students, preserving each student's own order. See the call site for why. */
function interleaveByStudent<T extends { studentUsername: string }>(records: T[]): T[] {
  const queues = new Map<string, T[]>();
  for (const r of records) {
    const q = queues.get(r.studentUsername);
    if (q) q.push(r); else queues.set(r.studentUsername, [r]);
  }
  const lanes = [...queues.values()];
  const out: T[] = [];
  for (let round = 0; out.length < records.length; round++) {
    for (const lane of lanes) {
      if (round < lane.length) out.push(lane[round]);
    }
  }
  return out;
}

function requireEnv(name: string): string {
  const v = process.env[name];
  if (!v) {
    console.error(`Missing required env var ${name}. See integration-test/README.md.`);
    process.exit(1);
  }
  return v;
}

const ROLE_ID: Record<string, string> = {
  student: 'role_student', teacher: 'role_teacher', admin: 'role_admin', sysadmin: 'role_sysadmin',
};

// ── Small id generator (seed-only ids, don't need to match OpaqueIds' exact format) ──

function genId(prefix: string): string {
  return `${prefix}_${randomBytes(10).toString('hex')}`;
}

function genPassword(): string {
  return randomBytes(12).toString('base64').replace(/[^A-Za-z0-9]/g, '').slice(0, 16);
}

// ── SQL phase: run a SQL script on the server over SSH ─────────────────
// Credentials are embedded via a single-quoted `printf` argument to
// `--defaults-extra-file=<(...)`  — process substitution, so nothing is
// ever written to a file that outlives the command (same pattern used by
// hand for Docs/TestAccounts.md's seeding). The SQL itself goes over
// stdin, not the command string, so it needs no shell-escaping at all.

function shQuote(s: string): string {
  return `'${s.replace(/'/g, `'\\''`)}'`;
}

/**
 * The direct-TCP half of {@link runSqlOnServer} (issue 101), used when
 * `TIMEBANK_DB_HOST` is set — the Docker stack, or any MySQL you can reach
 * without a jump host.
 *
 * The password goes to the client as an environment variable, never as a
 * command-line argument: `mysql -p<secret>` is visible in `ps` output to every
 * other user on the machine for as long as the command runs. `MYSQL_PWD` is
 * what the client reads for exactly this reason. It is still a local
 * development password, but the habit is the point.
 *
 * No retry loop here. The remote path retries because sshd on the shared server
 * drops connections under bot-driven `MaxStartups` throttling; a socket to a
 * container on the same machine either connects or has a real problem, and
 * retrying it would only delay the error.
 */
function runSqlDirect(sql: string): string {
  const result = spawnSync(
    'mysql',
    ['--protocol=TCP', '-h', DB_HOST!, '-P', DB_PORT, '-u', DB_USERNAME, DB_NAME],
    { input: sql, encoding: 'utf8', env: { ...process.env, MYSQL_PWD: DB_PASSWORD } }
  );
  if (result.error) {
    throw new Error(
      `Could not run the mysql client: ${result.error.message}. `
      + 'The direct-connection mode needs a mysql client on your PATH '
      + '(macOS: `brew install mysql-client`).'
    );
  }
  if (result.status !== 0) {
    throw new Error(`SQL phase failed (exit ${result.status}): ${result.stderr}`);
  }
  return result.stdout;
}

/**
 * Retries on transient SSH failures. This is not defensive padding: the test
 * server is continuously scanned by SSH brute-force bots, and when they
 * saturate sshd's `MaxStartups` window it drops *our* connection too
 * ("exited MaxStartups throttling, 1 connections dropped" in auth.log). That
 * killed a seed run outright after the SQL phase had already started. Every
 * SQL batch here is wrapped in START TRANSACTION/COMMIT, so a dropped
 * connection rolls back and a retry is safe to repeat.
 */
function runSqlOnServer(sql: string): string {
  if (USE_DIRECT_DB) return runSqlDirect(sql);
  const cnf = `printf '[client]\\nuser=%s\\npassword=%s\\nhost=127.0.0.1\\nport=3306\\n' ${shQuote(DB_USERNAME)} ${shQuote(DB_PASSWORD)}`;
  const remoteCommand = `mysql --defaults-extra-file=<(${cnf}) timebank`;
  const attempts = 4;
  let lastError = '';
  for (let attempt = 1; attempt <= attempts; attempt++) {
    const result = spawnSync('ssh', ['-o', 'ConnectTimeout=20', SSH_TARGET, remoteCommand], { input: sql, encoding: 'utf8' });
    if (result.status === 0) return result.stdout;
    lastError = `exit ${result.status}: ${result.stderr}`;
    // 255 is ssh's own transport failure (refused, dropped, timed out) — a
    // real SQL error exits non-zero with a different code and retrying it
    // would just fail identically, so only transport failures are retried.
    if (result.status !== 255 || attempt === attempts) break;
    const backoffMs = 3000 * attempt;
    console.warn(`  SSH transport failure (attempt ${attempt}/${attempts}), retrying in ${backoffMs / 1000}s...`);
    spawnSync('sleep', [String(backoffMs / 1000)]);
  }
  throw new Error(`SQL phase failed (${lastError})`);
}

function sqlString(s: string | undefined | null): string {
  if (s === undefined || s === null) return 'NULL';
  return `'${s.replace(/\\/g, '\\\\').replace(/'/g, "\\'")}'`;
}

// ── bcrypt hashing on the server (Python's bcrypt module, already installed there) ──

/**
 * Hashes in-process, for direct-connection mode (issue 101).
 *
 * The server-side path runs Python's `bcrypt` over SSH, which is fine when
 * there is a server and impossible when there is not — a local Docker stack has
 * no SSH host, so without this the seeder reached Phase 1 and died on the
 * hashing step even though every SQL statement would have worked.
 *
 * `bcryptjs` rather than the native `bcrypt`: pure JavaScript, so `npm install`
 * needs no compiler on a contributor's machine. It emits `$2b$` where the
 * Python path emits `$2a$`; Spring Security's `BCryptPasswordEncoder` accepts
 * both, which is verified by the seeded accounts actually logging in at the end
 * of a run rather than by reading its source.
 */
function bcryptHashLocal(passwordsByUsername: Record<string, string>): Record<string, string> {
  const hashes: Record<string, string> = {};
  for (const [username, password] of Object.entries(passwordsByUsername)) {
    // Cost 12, matching the server-side path — a cheaper factor here would make
    // locally seeded accounts weaker than seeded ones for no visible reason.
    hashes[username] = bcrypt.hashSync(password, bcrypt.genSaltSync(12));
  }
  return hashes;
}

function bcryptHashOnServer(passwordsByUsername: Record<string, string>): Record<string, string> {
  const py = `
import bcrypt
passwords = ${JSON.stringify(passwordsByUsername)}
for u, p in passwords.items():
    salt = bcrypt.gensalt(12, prefix=b'2a')
    h = bcrypt.hashpw(p.encode(), salt).decode()
    print(f'{u}:{h}')
`;
  if (USE_DIRECT_DB) return bcryptHashLocal(passwordsByUsername);
  const result = spawnSync('ssh', [SSH_TARGET, 'python3 -'], { input: py, encoding: 'utf8' });
  if (result.status !== 0) {
    throw new Error(`bcrypt hashing failed (exit ${result.status}):\n${result.stderr}`);
  }
  const hashes: Record<string, string> = {};
  for (const line of result.stdout.trim().split('\n')) {
    const idx = line.indexOf(':');
    hashes[line.slice(0, idx)] = line.slice(idx + 1);
  }
  return hashes;
}

// ── HTTP session (cookie jar + CSRF, mirroring the app's own login flow) ──

/** A `429` from the API — retryable, unlike every other failure here. */
class RateLimitedError extends Error {}

class Session {
  cookies = new Map<string, string>();
  csrfToken = '';
  userId = '';
  username = '';

  constructor(private baseUrl: string) {}

  private cookieHeader(): string {
    return [...this.cookies].map(([k, v]) => `${k}=${v}`).join('; ');
  }

  private captureCookies(res: Response) {
    const anyHeaders = res.headers as unknown as { getSetCookie?: () => string[] };
    const setCookies = anyHeaders.getSetCookie?.() ?? [];
    for (const sc of setCookies) {
      const pair = sc.split(';')[0];
      const idx = pair.indexOf('=');
      const name = pair.slice(0, idx);
      const value = pair.slice(idx + 1);
      this.cookies.set(name, value);
      if (name === 'XSRF-TOKEN') this.csrfToken = decodeURIComponent(value);
    }
  }

  /**
   * Retries a `429` with backoff (issue 101).
   *
   * `RateLimitInterceptor` allows 20 upload-intents per *principal* per minute.
   * Seeding 287 records round-robin across the students means one student can
   * spend that budget in seconds — and does, against a local stack, where there
   * is no network latency to pace the loop. The same script had been fine
   * against the shared server only because the round trip to it was slow enough
   * to stay under the limit by accident.
   *
   * Waiting is the correct response to a 429 and the limit is a real control,
   * so this backs off rather than asking the server to allow more. The bucket
   * refills greedily at 20/minute, so a few seconds restores capacity; the
   * delay grows per attempt in case several students are queued behind the same
   * refill.
   */
  async request(method: string, path: string, body?: unknown, idempotencyKey?: string): Promise<any> {
    const maxAttempts = 6;
    for (let attempt = 1; ; attempt++) {
      try {
        return await this.requestOnce(method, path, body, idempotencyKey);
      } catch (err) {
        const rateLimited = err instanceof RateLimitedError;
        if (!rateLimited || attempt === maxAttempts) throw err;
        const waitMs = 4000 * attempt;
        process.stdout.write(`  rate limited on ${path}, waiting ${waitMs / 1000}s (attempt ${attempt}/${maxAttempts - 1})\n`);
        await new Promise(resolve => setTimeout(resolve, waitMs));
      }
    }
  }

  private async requestOnce(method: string, path: string, body?: unknown, idempotencyKey?: string): Promise<any> {
    const headers: Record<string, string> = {};
    if (this.cookies.size) headers['Cookie'] = this.cookieHeader();
    if (this.csrfToken && method !== 'GET') headers['X-XSRF-TOKEN'] = this.csrfToken;
    if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;
    let payload: BodyInit | undefined;
    if (body !== undefined) {
      headers['Content-Type'] = 'application/json';
      payload = JSON.stringify(body);
    }
    const res = await fetch(`${this.baseUrl}${path}`, { method, headers, body: payload });
    this.captureCookies(res);
    const text = await res.text();
    const json = text ? JSON.parse(text) : undefined;
    if (!res.ok) {
      const fieldErrors = json?.error?.fieldErrors;
      const detail = fieldErrors ? ` ${JSON.stringify(fieldErrors)}` : '';
      const message = `${method} ${path} -> ${res.status}: ${json?.error?.message ?? text}${detail}`;
      // Distinguished by type, not by matching the message text, so the retry
      // above cannot be defeated by a reworded error string.
      throw res.status === 429 ? new RateLimitedError(message) : new Error(message);
    }
    return json?.data;
  }

  async uploadRaw(path: string, content: Buffer): Promise<void> {
    const headers: Record<string, string> = {};
    if (this.cookies.size) headers['Cookie'] = this.cookieHeader();
    if (this.csrfToken) headers['X-XSRF-TOKEN'] = this.csrfToken;
    const res = await fetch(`${this.baseUrl}${path}`, { method: 'PUT', headers, body: new Uint8Array(content) });
    this.captureCookies(res);
    if (!res.ok) {
      throw new Error(`PUT ${path} -> ${res.status}: ${await res.text()}`);
    }
  }
}

async function login(baseUrl: string, username: string, password: string): Promise<Session> {
  const session = new Session(baseUrl);
  await session.request('GET', '/api/v1/auth/csrf');
  const data = await session.request('POST', '/api/v1/auth/login', { username, password });
  session.userId = data.user.id;
  session.username = username;
  return session;
}

/**
 * A genuinely parseable one-page PDF, built with real object offsets and an
 * xref table.
 *
 * It used to be `%PDF-1.4` plus a comment — enough for the magic-byte check
 * and nothing more, as its own comment admitted. That went unnoticed for as
 * long as nothing ever *parsed* the bytes. Evidence watermarking
 * (`08-SecurityPrivacyHardening.md` §3.7) does parse them, so every seeded
 * file became an unviewable 500, and PDF sanitization at upload would now
 * reject the fake outright. Seed evidence has to be a real document.
 */
function minimalValidPdf(): Buffer {
  const header = '%PDF-1.4\n';
  const objects = [
    '1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n',
    '2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n',
    '3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Resources << >> >>\nendobj\n',
  ];
  let body = '';
  const offsets: number[] = [];
  for (const obj of objects) {
    offsets.push(header.length + body.length);
    body += obj;
  }
  const xrefOffset = header.length + body.length;
  let xref = `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  for (const off of offsets) xref += `${String(off).padStart(10, '0')} 00000 n \n`;
  const trailer = `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xrefOffset}\n%%EOF\n`;
  return Buffer.from(header + body + xref + trailer, 'latin1');
}

async function uploadEvidence(session: Session): Promise<string> {
  const content = minimalValidPdf();
  const intent = await session.request('POST', '/api/v1/files/upload-intents', {
    filename: 'evidence.pdf', mimeType: 'application/pdf', sizeBytes: content.length, purpose: 'serviceRecordEvidence',
  });
  await session.uploadRaw(`/api/v1/files/${intent.fileId}/content`, content);
  const meta = await session.request('POST', `/api/v1/files/${intent.fileId}/complete`);
  if (meta.status !== 'READY') {
    throw new Error(`Evidence file was rejected: ${meta.rejectionReason}`);
  }
  return intent.fileId;
}

// ── Date helpers — every date in mock-data.ts is an offset in days relative to *now* ──

function daysFromNow(offsetDays: number, hour = 9): string {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() + offsetDays);
  d.setUTCHours(Math.floor(hour), Math.round((hour % 1) * 60), 0, 0);
  return d.toISOString();
}

function dateFromNow(offsetDays: number): string {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() + offsetDays);
  return d.toISOString().slice(0, 10);
}

// MySQL's DATETIME string literal wants 'YYYY-MM-DD HH:MM:SS.sss', not the
// 'T'-separated/'Z'-suffixed ISO 8601 shape `daysFromNow` returns — used only
// by the Phase 3.5 direct-SQL enrollment insert below (every other SQL phase
// uses the `NOW(3)` function, not a JS-computed timestamp).
function sqlDateTime(iso: string): string {
  return sqlString(iso.replace('T', ' ').replace('Z', ''));
}

// ── Main ────────────────────────────────────────────────────────────────

/**
 * Refuses to seed a fixture that violates its own invariants (issue 103).
 *
 * The checker is spawned rather than imported so this runs the *same* command a
 * contributor runs by hand — `npm run verify-fixture` — with no second code
 * path that could pass while that one fails. A couple of seconds against a run
 * that takes minutes and ends in a database full of data that has to be dropped
 * by hand is not a trade worth thinking about.
 */
function verifyFixtureOrExit(): void {
  console.log('== Phase 0a: fixture invariants ==');
  const result = spawnSync('npx', ['tsx', 'verify-fixture.ts'], {
    cwd: import.meta.dirname, encoding: 'utf8', stdio: ['ignore', 'inherit', 'inherit'],
  });
  if (result.status !== 0) {
    console.error('\nRefusing to seed: the fixture does not satisfy its own invariants (see above).');
    process.exit(1);
  }
}

async function main() {
  verifyFixtureOrExit();

  console.log('== Phase 0: pre-flight re-run check ==');
  const usernameList = users.map(u => sqlString(u.username)).join(',');
  const studentNumberList = students.map(s => sqlString(s.studentNumber)).join(',');
  const existing = runSqlOnServer(
    `SELECT 'user' AS kind, username AS k FROM user WHERE username IN (${usernameList}) ` +
    `UNION ALL SELECT 'student', student_number FROM student WHERE student_number IN (${studentNumberList});`
  );
  const existingRows = existing.trim().split('\n').slice(1).filter(Boolean);
  if (existingRows.length > 0) {
    console.error('Refusing to seed: these usernames/student numbers already exist on the server:');
    console.error(existingRows.join('\n'));
    console.error('This is a first-run seeding tool, not a reconciler — delete the conflicting rows first (see README.md).');
    process.exit(1);
  }

  console.log('== Phase 1: SQL — organizations, users, students ==');
  const now = 'NOW(3)';

  // The academic year is read from the database, never hardcoded (issue 100).
  // It used to be the literal 'ay_2025_2026' in both the class and student
  // inserts, while every fixture date in mock-data.ts is an offset relative to
  // *now* — so from September 2026 the seeder enrolled students into a year
  // that had already ended and dated their records outside it. `end_date` is a
  // real query bound, so annual progress then silently undercounts.
  // R__seed_base_data.sql derives the year from the current date; this asks it
  // which one that is rather than assuming.
  const yearRows = runSqlOnServer(
    'SELECT id FROM academic_year WHERE is_current = 1 ORDER BY start_date DESC;'
  ).trim().split('\n').slice(1).filter(Boolean);
  if (yearRows.length !== 1) {
    throw new Error(
      `Expected exactly one academic year flagged is_current, found ${yearRows.length}. `
      + 'Deploy the backend once so Flyway applies R__seed_base_data.sql before seeding.'
    );
  }
  const currentAcademicYearId = yearRows[0].trim();
  console.log(`   current academic year: ${currentAcademicYearId}`);

  // Enrolment-cohort years. Students enter at Grade 6 and stay six years, so an
  // honest `enrollment_academic_year_id` needs a row per cohort — Flyway's base
  // data ships only the current year and the one before it, and writing the
  // current year for all 50 (which is what this did) made every Grade 11
  // student a first-year enrollee and left every earlier year with an empty
  // population in the participation and completion reports, which join
  // `ay.start_date <= :end` (issue 103 §2.2).
  //
  // These belong here rather than in `R__seed_base_data.sql` for the same
  // reason the named test classes do: base data ships to every real deployment,
  // and a fixture's cohort history does not. They carry no data of their own.
  const cohortYearInserts = cohortAcademicYearStarts.map(startYear =>
    `INSERT IGNORE INTO academic_year (id, code, start_date, end_date, is_current, created_at, updated_at) VALUES ` +
    `('ay_${startYear}_${startYear + 1}', '${startYear}-${startYear + 1}', ` +
    `'${startYear}-09-01', '${startYear + 1}-08-31', 0, ${now}, ${now});`);

  const orgIdByName = new Map<string, string>();
  const orgInserts: string[] = [];
  for (const org of organizations) {
    const id = genId('org');
    orgIdByName.set(org.name, id);
    orgInserts.push(
      `INSERT INTO organization (id, name, type, contact_name, contact_email, contact_phone, address, status, version, created_at, updated_at) VALUES ` +
      `(${sqlString(id)}, ${sqlString(org.name)}, ${sqlString(org.type)}, ${sqlString(org.contactName)}, ${sqlString(org.contactEmail)}, ${sqlString(org.contactPhone)}, ${sqlString(org.address)}, 'ACTIVE', 1, ${now}, ${now});`
    );
  }

  // Named test classes (Mars, Luna) are created here rather than in
  // R__seed_base_data.sql: base data ships to every real deployment, and a
  // test fixture does not belong in it.
  const classInserts: string[] = namedClasses.map(c =>
    `INSERT INTO class (id, code, name, grade_id, academic_year_id, created_at, updated_at) VALUES ` +
    `(${sqlString(c.id)}, ${sqlString(c.code)}, ${sqlString(c.name)}, ${sqlString(c.gradeId)}, ${sqlString(currentAcademicYearId)}, ${now}, ${now});`
  );

  const studentIdByNumber = new Map<string, string>();
  const studentInserts: string[] = [];
  for (const s of students) {
    const id = genId('std');
    studentIdByNumber.set(s.studentNumber, id);
    // The student table has no email/guardian_name/guardian_contact columns:
    // dropped in V26 under data minimization
    // (`Docs/optimization/08-SecurityPrivacyHardening.md` §3.1).
    studentInserts.push(
      `INSERT INTO student (id, student_number, display_name, grade_id, class_id, enrollment_academic_year_id, graduation_year, status, joined_at, version, created_at, updated_at) VALUES ` +
      `(${sqlString(id)}, ${sqlString(s.studentNumber)}, ${sqlString(s.displayName)}, ${sqlString(s.gradeId)}, ${sqlString(s.classId)}, ${sqlString(s.enrollmentAcademicYearId)}, ${s.graduationYear}, 'ACTIVE', ${sqlString(dateFromNow(s.joinedAtOffsetDays))}, 1, ${now}, ${now});`
    );
  }

  const passwordByUsername: Record<string, string> = {};
  for (const u of users) passwordByUsername[u.username] = genPassword();
  const hashByUsername = bcryptHashOnServer(passwordByUsername);

  const userIdByUsername = new Map<string, string>();
  const userInserts: string[] = [];
  const roleInserts: string[] = [];
  const scopeInserts: string[] = [];
  for (const u of users) {
    const id = genId('usr');
    userIdByUsername.set(u.username, id);
    const studentId = u.linkedStudentNumber ? studentIdByNumber.get(u.linkedStudentNumber) : undefined;
    userInserts.push(
      // `staff_id` is written for every non-student account. Without it the
      // account exists and logs in fine, but `PATCH /users/{id}` refuses every
      // edit with "A staff ID is required for non-student accounts" — the
      // controller checks the *effective* value, which falls back to the stored
      // null (issue 100). Seeding it is what makes the admin UI's Edit User
      // dialog work on a freshly seeded server.
      `INSERT INTO user (id, username, password_hash, display_name, email, student_id, staff_id, status, preferred_language, failed_login_attempts, version, created_at, updated_at) VALUES ` +
      `(${sqlString(id)}, ${sqlString(u.username)}, ${sqlString(hashByUsername[u.username])}, ${sqlString(u.displayName)}, ${sqlString(u.email)}, ${sqlString(studentId)}, ${sqlString(u.staffId)}, 'ACTIVE', 'zh-CN', 0, 1, ${now}, ${now});`
    );
    for (const roleCode of u.roleCodes) {
      roleInserts.push(`INSERT INTO user_role (user_id, role_id) VALUES (${sqlString(id)}, ${sqlString(ROLE_ID[roleCode])});`);
    }
    for (const grade of u.scopeGrades ?? []) {
      // Every resourceType TeacherScopeAssignmentServiceImpl's MANAGED_RESOURCE_TYPES
      // covers (issue 002/006) — kept in sync so a hand-seeded teacher matches what
      // the real account-creation form would produce.
      for (const resourceType of ['serviceRecords', 'eventProposals', 'students', 'accounts', 'cases']) {
        scopeInserts.push(
          `INSERT INTO teacher_scope_assignment (id, user_id, resource_type, scope_type, scope_value, created_at) VALUES ` +
          `(${sqlString(genId('tsa'))}, ${sqlString(id)}, ${sqlString(resourceType)}, 'GRADE', ${sqlString(grade)}, ${now});`
        );
      }
    }
  }

  runSqlOnServer(['START TRANSACTION;', ...cohortYearInserts, ...classInserts, ...orgInserts, ...studentInserts, ...userInserts, ...roleInserts, ...scopeInserts, 'COMMIT;'].join('\n'));
  console.log(`Inserted ${cohortYearInserts.length} cohort academic years, ${organizations.length} organizations, ${students.length} students, ${users.length} users.`);

  console.log(`== Phase 2: API — logging in as ${users.length} personas ==`);
  // nginx rate-limits /api/v1/auth/login to 10r/m per IP with burst 5, and
  // every student must log in as themselves because there is no
  // create-a-record-on-behalf path. Without pacing, everything past the
  // burst gets 429'd. 6.5s keeps us just under the sustained rate; expect
  // this phase to take roughly `users.length * 6.5` seconds.
  // …but that limit is nginx's, and the local Docker stack has no nginx in
  // front of it (issue 101). Pacing there costs five minutes of a verification
  // loop whose whole value is being fast, so direct-DB mode — which only the
  // local stack uses — goes at full speed.
  const LOGIN_INTERVAL_MS = USE_DIRECT_DB ? 0 : 6_500;
  const sessions = new Map<string, Session>();
  let loginIndex = 0;
  for (const u of users) {
    if (loginIndex >= 5) await sleep(LOGIN_INTERVAL_MS); // first 5 ride the burst allowance
    loginIndex++;
    // Even paced under the limit, a 429 can still land if anything else is
    // talking to /auth/login from this IP. Back off and retry rather than
    // losing the whole run at minute four.
    let session: Session | undefined;
    for (let attempt = 1; attempt <= 3 && !session; attempt++) {
      try {
        session = await login(BASE_URL, u.username, passwordByUsername[u.username]);
      } catch (err) {
        if (attempt === 3 || !String(err).includes('429')) throw err;
        console.warn(`  login rate-limited for ${u.username}, backing off ${15 * attempt}s...`);
        await sleep(15_000 * attempt);
      }
    }
    sessions.set(u.username, session!);
    if (loginIndex % 10 === 0 || loginIndex === users.length) {
      console.log(`  logged in ${loginIndex}/${users.length}`);
    }
  }
  const session = (username: string): Session => {
    const s = sessions.get(username);
    if (!s) throw new Error(`No session for ${username} — check mock-data.ts references a real username`);
    return s;
  };

  console.log('== Phase 3: API — projects ==');
  const projectIdByTitle = new Map<string, string>();
  for (const p of projects as SeedProject[]) {
    const owner = session(p.ownerUsername);
    const created = await owner.request('POST', '/api/v1/projects', {
      title: p.title, summary: p.summary, description: p.description, categoryId: p.categoryId,
      organizationId: p.organizationName ? orgIdByName.get(p.organizationName) : undefined,
      deliveryScope: p.deliveryScope,
      startAt: daysFromNow(p.startOffsetDays, 8), endAt: daysFromNow(p.endOffsetDays, 17),
      location: p.location, capacity: p.capacity, recognizedMinutes: p.recognizedMinutes,
      eligibleGradeIds: p.eligibleGradeIds, evidenceRequirements: p.evidenceRequirements,
      registrationOpenAt: daysFromNow(p.registrationOpenOffsetDays, 0),
      registrationCloseAt: daysFromNow(p.registrationCloseOffsetDays, 23.5),
    });
    projectIdByTitle.set(p.title, created.id);
    let version = created.version;
    if (p.targetStatus !== 'draft') {
      const published = await owner.request('POST', `/api/v1/projects/${created.id}/publish`, { version });
      version = published.version;
    }
    console.log(`  project "${p.title}" -> ${p.targetStatus}`);
  }

  console.log('== Phase 3.5: SQL — enrollments (see SeedProject.registrantStudentNumbers doc comment) ==');

  // This used to reconcile registrants against the PROJECT-sourced records here,
  // by mutating the imported `projects` array at run time. That hid a real
  // inconsistency: the fixture as written did not enrol those students, so no
  // offline check could tell a correct dataset from a broken one, and the whole
  // question only ever surfaced as a 422 mid-run. `mock-data.ts` now exports
  // projects whose registrant lists already include them
  // (`withPinnedRegistrants`), and `verify-fixture.ts` asserts it.

  const enrollmentInserts: string[] = [];
  for (const p of projects as SeedProject[]) {
    if (!p.registrantStudentNumbers?.length) continue;
    const projectId = projectIdByTitle.get(p.title)!;
    const appliedAt = daysFromNow(p.registrationOpenOffsetDays + 1, 10);
    const confirmedAt = daysFromNow(p.registrationOpenOffsetDays + 1, 11);
    for (const studentNumber of p.registrantStudentNumbers) {
      const studentId = studentIdByNumber.get(studentNumber);
      if (!studentId) throw new Error(`Project "${p.title}" references unknown student number "${studentNumber}".`);
      enrollmentInserts.push(
        `INSERT INTO enrollment (id, project_id, student_id, status, applied_at, confirmed_at, version) VALUES ` +
        `(${sqlString(genId('enr'))}, ${sqlString(projectId)}, ${sqlString(studentId)}, 'CONFIRMED', ${sqlDateTime(appliedAt)}, ${sqlDateTime(confirmedAt)}, 1);`
      );
    }
  }
  if (enrollmentInserts.length > 0) {
    runSqlOnServer(['START TRANSACTION;', ...enrollmentInserts, 'COMMIT;'].join('\n'));
    console.log(`Inserted ${enrollmentInserts.length} CONFIRMED enrollments.`);
  }

  console.log(`== Phase 4: API — ${serviceRecords.length} service records ==`);
  const serviceRecordIdByKey = new Map<string, string>();
  let draftCount = 0;
  let submittedCount = 0;
  const decidedCount: Record<string, number> = {};
  // `RateLimitInterceptor` allows 20 upload-intents per minute keyed by
  // *principal*, so a student with thirty-odd records exhausts their own
  // budget if we create them back-to-back. Round-robining across students
  // spends one request per student per round instead, which stays far under
  // the limit without slowing anything down or touching server config.
  for (const r of interleaveByStudent(serviceRecords as SeedServiceRecord[])) {
    const student = session(r.studentUsername);
    const evidenceFileIds = r.targetStatus === 'draft' ? [] : [await uploadEvidence(student)];
    const created = await student.request('POST', '/api/v1/service-records', {
      sourceType: r.sourceType,
      projectId: r.sourceType === 'PROJECT' && r.projectTitle ? projectIdByTitle.get(r.projectTitle) : undefined,
      organizationName: r.organizationName, serviceCategoryId: r.serviceCategoryId,
      serviceDate: dateFromNow(r.serviceDateOffsetDays),
      startAt: daysFromNow(r.serviceDateOffsetDays, r.startHour), endAt: daysFromNow(r.serviceDateOffsetDays, r.endHour),
      location: r.location, description: r.description, supervisorKind: r.supervisorKind, supervisorName: r.supervisorName,
      supervisorContact: r.supervisorContact, evidenceFileIds, includesTravelTime: false,
    });
    if (r.key) serviceRecordIdByKey.set(r.key, created.id);
    if (r.targetStatus === 'draft') {
      draftCount++;
      continue;
    }
    const submitted = await student.request('POST', `/api/v1/service-records/${created.id}/submit`, undefined, randomUUID());
    if (r.targetStatus === 'submitted') {
      submittedCount++;
      continue;
    }
    const reviewer = session(r.reviewerUsername!);
    // The reviewer must hold scope over this student's grade, or the record
    // 404s rather than 403s (B1.7's no-existence-leak). mock-data routes every
    // decision through `reviewerForGrade` for exactly that reason.
    const decision = r.targetStatus === 'approved' ? 'APPROVE'
      : r.targetStatus === 'returned' ? 'RETURN_FOR_CHANGES'
      : r.targetStatus === 'rejected' ? 'REJECT'
      : 'ESCALATE'; // underReview
    await reviewer.request('POST', `/api/v1/service-records/${created.id}/reviews`, {
      decision, reasonCode: r.reasonCode, comment: r.comment, recordVersion: submitted.version,
    }, randomUUID());
    decidedCount[r.targetStatus] = (decidedCount[r.targetStatus] ?? 0) + 1;
  }
  console.log(`  ${draftCount} draft, ${submittedCount} submitted, ` +
    Object.entries(decidedCount).map(([k, v]) => `${v} ${k}`).join(', '));

  console.log('== Phase 5: API — event proposals ==');
  for (const p of eventProposals as SeedEventProposal[]) {
    const student = session(p.studentUsername);
    const supervisorId = session(p.supervisorUsername).userId;
    const created = await student.request('POST', '/api/v1/event-proposals', {
      title: p.title, objective: p.objective, description: p.description, expectedOutcome: p.expectedOutcome,
      categoryId: p.categoryId, plannedStartAt: daysFromNow(p.plannedStartOffsetDays, 9),
      plannedEndAt: daysFromNow(p.plannedEndOffsetDays, 17), location: p.location, format: p.format,
      targetParticipants: p.targetParticipants, capacity: p.capacity, volunteerTasks: p.volunteerTasks,
      proposedSupervisorId: supervisorId,
      organizationId: p.organizationName ? orgIdByName.get(p.organizationName) : undefined,
      newOrganizationName: p.newOrganizationName, externalContact: p.externalContact, safetyPlan: p.safetyPlan,
      resourceNeeds: p.resourceNeeds, recruitmentPlan: p.recruitmentPlan,
      proposedRecognizedMinutes: p.proposedRecognizedMinutes, evidencePlan: p.evidencePlan,
    });
    if (p.targetStatus === 'draft') {
      console.log(`  proposal "${p.title}" -> draft`);
      continue;
    }
    const submitted = await student.request('POST', `/api/v1/event-proposals/${created.id}/submit`, undefined, randomUUID());
    if (p.targetStatus === 'submitted') {
      console.log(`  proposal "${p.title}" -> submitted`);
      continue;
    }
    const reviewer = session(p.reviewerUsername!);
    const decision = p.targetStatus === 'approved' ? 'approve' : 'requestChanges';
    await reviewer.request('POST', `/api/v1/event-proposals/${created.id}/reviews`, {
      decision, reasonCode: p.reasonCode, comment: p.comment, proposalVersion: submitted.version,
    }, randomUUID());
    console.log(`  proposal "${p.title}" -> ${p.targetStatus} (by ${p.reviewerUsername})`);
  }

  console.log('== Phase 6: API — cases ==');
  for (const c of cases as SeedCase[]) {
    const filer = session(c.filedByUsername ?? c.studentUsername);
    // Staff filing on a student's behalf must send the *roster row* id
    // (`student.id`, what CaseServiceImpl hands to studentRepository.findById),
    // not the student's login user id — the two are different entities linked
    // by `user.student_id`.
    const filedForStudentId = c.filedByUsername
      ? studentIdByNumber.get(users.find(u => u.username === c.studentUsername)?.linkedStudentNumber ?? '')
      : undefined;
    if (c.filedByUsername && !filedForStudentId) {
      throw new Error(`Case "${c.title}" is filed by ${c.filedByUsername} for ${c.studentUsername}, but that user has no linkedStudentNumber in mock-data.ts.`);
    }
    // `c.subjectId` is a SeedServiceRecord.key, not a real id, when subjectType is 'serviceRecord' —
    // real ids don't exist until Phase 4 creates that record (see SeedCase.subjectId's doc comment).
    const subjectId = c.subjectType === 'serviceRecord' && c.subjectId ? serviceRecordIdByKey.get(c.subjectId) : c.subjectId;
    if (c.subjectType === 'serviceRecord' && c.subjectId && !subjectId) {
      throw new Error(`Case "${c.title}" references unknown service record key "${c.subjectId}" — check serviceRecords[].key in mock-data.ts.`);
    }
    const created = await filer.request('POST', '/api/v1/cases', {
      studentId: filedForStudentId,
      type: c.type, subjectType: c.subjectType, subjectId,
      title: c.title, description: c.description,
      requestedAmountCentiCoins: c.requestedAmountCentiCoins, reasonCode: c.reasonCode,
    });
    if (c.targetStatus === 'draft') {
      console.log(`  case "${c.title}" -> draft`);
      continue;
    }
    const submitted = await filer.request('POST', `/api/v1/cases/${created.id}/submit`, undefined, randomUUID());
    if (c.targetStatus === 'submitted') {
      console.log(`  case "${c.title}" -> submitted`);
      continue;
    }
    const reviewer = session(c.reviewerUsername!);
    const decision = c.targetStatus === 'resolved' ? 'resolve' : c.targetStatus === 'rejected' ? 'reject' : 'requestInformation';
    await reviewer.request('POST', `/api/v1/cases/${created.id}/decisions`, {
      decision, reasonCode: c.decisionReasonCode ?? c.reasonCode, comment: c.decisionComment, caseVersion: submitted.version,
    }, randomUUID());
    console.log(`  case "${c.title}" -> ${c.targetStatus} (by ${c.reviewerUsername})`);
  }

  // ── Phase 6.5: SQL — backdate the audit timestamps ──────────────────────
  //
  // Everything above is created *now*, because it is created by really calling
  // the API — which is the point. But the service dates span two school years,
  // so a record of last October carries a `created_at` of this afternoon, and
  // two reports read those columns rather than `service_date`:
  //
  //   · `adminStats.approvedThisMonth` counts `updated_at` inside the current
  //     month, so the dashboard announces every approval ever made as this
  //     month's — 622 of them, against a school of 50 students.
  //   · `reviewEfficiencyByReviewer` averages `submitted_at` to decision, which
  //     collapses to a couple of minutes and reads as though nobody reviews
  //     anything.
  //
  // So the columns are moved to where the story says they happened: logged the
  // evening of the service, submitted the next day, decided a couple of days
  // after that. `LEAST(..., NOW(3))` keeps a record served yesterday from
  // acquiring a submission in the future.
  console.log('== Phase 6.5: SQL — backdating created/submitted/decided timestamps ==');
  runSqlOnServer([
    'START TRANSACTION;',
    // Logged the evening of the service; submitted the following evening.
    `UPDATE service_record SET
       created_at = LEAST(TIMESTAMP(service_date, '18:30:00'), NOW(3)),
       submitted_at = IF(submitted_at IS NULL, NULL,
                         LEAST(TIMESTAMP(service_date + INTERVAL 1 DAY, '19:15:00'), NOW(3)));`,
    // Decided two to four days after the service, deterministically per row.
    `UPDATE review_decision rd JOIN service_record sr ON sr.id = rd.service_record_id
       SET rd.created_at = LEAST(
             TIMESTAMP(sr.service_date + INTERVAL (2 + (CRC32(rd.id) % 3)) DAY, '09:40:00'), NOW(3));`,
    // A record's own updated_at is when it was last decided…
    `UPDATE service_record sr
       JOIN (SELECT service_record_id, MAX(created_at) AS decided_at
               FROM review_decision GROUP BY service_record_id) d ON d.service_record_id = sr.id
       SET sr.updated_at = GREATEST(d.decided_at, COALESCE(sr.submitted_at, sr.created_at));`,
    // …and for anything still undecided, when it was last touched.
    `UPDATE service_record SET updated_at = COALESCE(submitted_at, created_at)
       WHERE id NOT IN (SELECT service_record_id FROM review_decision);`,
    // The ledger row belongs to the moment of the approval that created it.
    `UPDATE account_transaction at JOIN service_record sr ON sr.id = at.source_id
       SET at.created_at = sr.updated_at
       WHERE at.source_type = 'serviceRecord';`,
    'COMMIT;',
  ].join('\n'));
  console.log('Backdated service records, review decisions and ledger rows.');

  // ── Phase 7: consistency check ──────────────────────────────────────────
  // The whole point of driving records through the real API is that balances
  // are *derived*. This verifies that actually happened: every student's live
  // coin balance must equal the hours of their approved records, at 1 coin per
  // hour under rule_default_2025 (30-minute rounding, 60 min/unit, 100
  // centi-coins/unit). A mismatch means either the rounding assumption broke
  // or something credited the ledger outside the record flow — both of which
  // would leave a number on screen nobody can explain.
  console.log('== Phase 7: verifying balances are consistent with approved records ==');
  const admin = session('admin001');
  const mismatches: string[] = [];
  let checked = 0;
  for (const s of generatedStudents) {
    if (!s.username) continue;
    const studentId = studentIdByNumber.get(s.studentNumber)!;
    const expectedCoins = expectedCoinsByUsername[s.username] ?? 0;
    const accounts = await admin.request('GET', `/api/v1/accounts?filter%5BstudentId%5D=${studentId}`);
    // `/accounts` is paginated, so `data` is the array itself; the fallbacks
    // cover the envelope shapes without assuming which one it is.
    const rows = Array.isArray(accounts) ? accounts : (accounts?.items ?? accounts?.content ?? []);
    // AccountDto.balance is an int in centi-coins (the same unit
    // adjustForStudent credits), not a decimal coin count.
    const actualCenti = rows[0]?.balance ?? 0;
    const expectedCenti = Math.round(expectedCoins * 100);
    checked++;
    if (actualCenti !== expectedCenti) {
      mismatches.push(`${s.username}: expected ${expectedCenti} centi-coins (${expectedCoins}h approved), got ${actualCenti}`);
    }
  }
  if (mismatches.length > 0) {
    console.error(`\nBalance verification FAILED for ${mismatches.length}/${checked} students:`);
    for (const m of mismatches.slice(0, 10)) console.error(`  ${m}`);
    throw new Error('Seeded balances do not match approved service records — refusing to report success.');
  }
  console.log(`  ${checked} students checked, every balance matches its approved records.`);

  console.log('\n== Done ==');
  console.log('Accounts created (see Docs/TestAccounts.md for the committed record):');
  for (const u of users) console.log(`  ${u.username} / ${passwordByUsername[u.username]}  (${u.displayName})`);
}

main().catch(err => {
  console.error('\nSeed failed:', err);
  process.exit(1);
});
