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
// Optional:
//   TIMEBANK_SSH_TARGET   default root@tb.example.com
//   TIMEBANK_BASE_URL     default https://tb.example.com
//
// Usage:  cd integration-test && npm install && npm run seed

import { spawnSync } from 'node:child_process';
import { randomBytes, randomUUID } from 'node:crypto';
import {
  organizations, users, students, projects, serviceRecords, eventProposals, cases,
  type SeedProject, type SeedServiceRecord, type SeedEventProposal, type SeedCase,
} from './mock-data.ts';

// ── Config ──────────────────────────────────────────────────────────────

const SSH_TARGET = process.env.TIMEBANK_SSH_TARGET ?? 'root@tb.example.com';
const BASE_URL = process.env.TIMEBANK_BASE_URL ?? 'https://tb.example.com';
const DB_USERNAME = requireEnv('TIMEBANK_DB_USERNAME');
const DB_PASSWORD = requireEnv('TIMEBANK_DB_PASSWORD');

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

function runSqlOnServer(sql: string): string {
  const cnf = `printf '[client]\\nuser=%s\\npassword=%s\\nhost=127.0.0.1\\nport=3306\\n' ${shQuote(DB_USERNAME)} ${shQuote(DB_PASSWORD)}`;
  const remoteCommand = `mysql --defaults-extra-file=<(${cnf}) timebank`;
  const result = spawnSync('ssh', [SSH_TARGET, remoteCommand], { input: sql, encoding: 'utf8' });
  if (result.status !== 0) {
    throw new Error(`SQL phase failed (exit ${result.status}):\n${result.stderr}`);
  }
  return result.stdout;
}

function sqlString(s: string | undefined | null): string {
  if (s === undefined || s === null) return 'NULL';
  return `'${s.replace(/\\/g, '\\\\').replace(/'/g, "\\'")}'`;
}

// ── bcrypt hashing on the server (Python's bcrypt module, already installed there) ──

function bcryptHashOnServer(passwordsByUsername: Record<string, string>): Record<string, string> {
  const py = `
import bcrypt
passwords = ${JSON.stringify(passwordsByUsername)}
for u, p in passwords.items():
    salt = bcrypt.gensalt(12, prefix=b'2a')
    h = bcrypt.hashpw(p.encode(), salt).decode()
    print(f'{u}:{h}')
`;
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

  async request(method: string, path: string, body?: unknown, idempotencyKey?: string): Promise<any> {
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
      throw new Error(`${method} ${path} -> ${res.status}: ${json?.error?.message ?? text}`);
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

async function uploadEvidence(session: Session): Promise<string> {
  const content = Buffer.from('%PDF-1.4\n% integration-test seed evidence file, not a structurally valid PDF beyond its signature\n');
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

// ── Main ────────────────────────────────────────────────────────────────

async function main() {
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

  const studentIdByNumber = new Map<string, string>();
  const studentInserts: string[] = [];
  for (const s of students) {
    const id = genId('std');
    studentIdByNumber.set(s.studentNumber, id);
    studentInserts.push(
      `INSERT INTO student (id, student_number, display_name, email, grade_id, class_id, enrollment_academic_year_id, graduation_year, status, guardian_name, guardian_contact, joined_at, version, created_at, updated_at) VALUES ` +
      `(${sqlString(id)}, ${sqlString(s.studentNumber)}, ${sqlString(s.displayName)}, ${sqlString(s.email)}, ${sqlString(s.gradeId)}, ${sqlString(s.classId)}, 'ay_2025_2026', ${s.graduationYear}, 'ACTIVE', ${sqlString(s.guardianName)}, ${sqlString(s.guardianContact)}, ${sqlString(dateFromNow(s.joinedAtOffsetDays))}, 1, ${now}, ${now});`
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
      `INSERT INTO user (id, username, password_hash, display_name, email, student_id, status, preferred_language, failed_login_attempts, version, created_at, updated_at) VALUES ` +
      `(${sqlString(id)}, ${sqlString(u.username)}, ${sqlString(hashByUsername[u.username])}, ${sqlString(u.displayName)}, ${sqlString(u.email)}, ${sqlString(studentId)}, 'ACTIVE', 'zh-CN', 0, 1, ${now}, ${now});`
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

  runSqlOnServer(['START TRANSACTION;', ...orgInserts, ...studentInserts, ...userInserts, ...roleInserts, ...scopeInserts, 'COMMIT;'].join('\n'));
  console.log(`Inserted ${organizations.length} organizations, ${students.length} students, ${users.length} users.`);

  console.log('== Phase 2: API — logging in as each persona ==');
  const sessions = new Map<string, Session>();
  for (const u of users) {
    sessions.set(u.username, await login(BASE_URL, u.username, passwordByUsername[u.username]));
    console.log(`  logged in as ${u.username} (${u.displayName})`);
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
    if (p.targetStatus === 'closed') {
      await owner.request('POST', `/api/v1/projects/${created.id}/close`, { version });
    }
    console.log(`  project "${p.title}" -> ${p.targetStatus}`);
  }

  console.log('== Phase 4: API — service records ==');
  for (const r of serviceRecords as SeedServiceRecord[]) {
    const student = session(r.studentUsername);
    const evidenceFileIds = r.targetStatus === 'draft' ? [] : [await uploadEvidence(student)];
    const created = await student.request('POST', '/api/v1/service-records', {
      sourceType: r.sourceType,
      projectId: r.sourceType === 'PROJECT' && r.projectTitle ? projectIdByTitle.get(r.projectTitle) : undefined,
      organizationName: r.organizationName, serviceCategoryId: r.serviceCategoryId,
      serviceDate: dateFromNow(r.serviceDateOffsetDays),
      startAt: daysFromNow(r.serviceDateOffsetDays, r.startHour), endAt: daysFromNow(r.serviceDateOffsetDays, r.endHour),
      location: r.location, description: r.description, supervisorName: r.supervisorName,
      supervisorContact: r.supervisorContact, evidenceFileIds, includesTravelTime: false,
    });
    if (r.targetStatus === 'draft') {
      console.log(`  service record for ${r.studentUsername} -> draft`);
      continue;
    }
    const submitted = await student.request('POST', `/api/v1/service-records/${created.id}/submit`, undefined, randomUUID());
    if (r.targetStatus === 'submitted') {
      console.log(`  service record for ${r.studentUsername} -> submitted`);
      continue;
    }
    const reviewer = session(r.reviewerUsername!);
    const decision = r.targetStatus === 'approved' ? 'approve' : 'returnForChanges';
    await reviewer.request('POST', `/api/v1/service-records/${created.id}/reviews`, {
      decision, reasonCode: r.reasonCode, comment: r.comment, recordVersion: submitted.version,
    }, randomUUID());
    console.log(`  service record for ${r.studentUsername} -> ${r.targetStatus} (by ${r.reviewerUsername})`);
  }

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
    const student = session(c.studentUsername);
    const created = await filer.request('POST', '/api/v1/cases', {
      studentId: c.filedByUsername ? student.userId : undefined,
      type: c.type, subjectType: c.subjectType, subjectId: c.subjectId,
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

  console.log('\n== Done ==');
  console.log('Accounts created (see Docs/TestAccounts.md for the committed record):');
  for (const u of users) console.log(`  ${u.username} / ${passwordByUsername[u.username]}  (${u.displayName})`);
}

main().catch(err => {
  console.error('\nSeed failed:', err);
  process.exit(1);
});
