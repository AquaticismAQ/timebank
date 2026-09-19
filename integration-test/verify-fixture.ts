/**
 * Invariant suite for the seed fixture (issue 103).
 *
 * ── Why this file exists ─────────────────────────────────────────────────
 *
 * Every rule the fixture has to satisfy used to live in prose in
 * `MOCK_DATA_GUIDE.md` and be enforced by nothing. A violation was therefore
 * only discoverable by opening the one screen that happened to expose it, which
 * turned a bounded problem into an unbounded sequence of them — issues 083, 100
 * and the 2026-09-18 Reports fix are all the same shape, and each one cost a
 * round of seed-deploy-look.
 *
 * So the rules are executable now. `npm run verify-fixture` runs them, and
 * `seed.ts` runs them before it touches a database, so a fixture that violates
 * one cannot reach a server at all.
 *
 * ── Three kinds of rule, and why they are worth separating ───────────────
 *
 *   BACKEND    things the real API would reject. A violation does not produce
 *              bad data, it produces a seed run that dies halfway with a 422
 *              and a half-populated database.
 *   REPORTS    things that make a report answer nothing, or answer wrongly,
 *              while looking like it worked. These are the expensive ones: the
 *              screen shows a number, so nobody doubts it.
 *   CREDIBLE   things that make the data implausible to a school. No test
 *              anywhere else in this project has an opinion about these, and
 *              they are the reason 99 hours of volunteering inside 17 days
 *              survived a full verification pass.
 *
 * This file must not import from `seed.ts`: it has to be runnable, and worth
 * running, without a server anywhere near it.
 */
import {
  cases, eventProposals, generatedStudents, namedClasses, organizations, projects,
  reviewerForGrade, serviceRecords, students, users,
  CURRENT_ACADEMIC_YEAR_START, academicYearWindow, enrollmentYearStartFor,
  entryLevelFor, gradeLevelOf, stageExitLevelFor,
  type SeedProject, type SeedServiceRecord,
} from './mock-data';

type Severity = 'BACKEND' | 'REPORTS' | 'CREDIBLE';

interface Failure {
  severity: Severity;
  rule: string;
  detail: string;
}

const failures: Failure[] = [];
const notes: string[] = [];

function check(severity: Severity, rule: string, ok: boolean, detail: string): void {
  if (!ok) failures.push({ severity, rule, detail });
}

function note(line: string): void {
  notes.push(line);
}

// ── Shared derivations ────────────────────────────────────────────────────

const priorYear = academicYearWindow(CURRENT_ACADEMIC_YEAR_START - 1);
const currentYear = academicYearWindow(CURRENT_ACADEMIC_YEAR_START);

const MS_PER_DAY = 86_400_000;
const TODAY = (() => {
  const n = new Date();
  return Date.UTC(n.getUTCFullYear(), n.getUTCMonth(), n.getUTCDate());
})();
const dateOf = (offset: number) => new Date(TODAY + offset * MS_PER_DAY);
const ymOf = (offset: number) => dateOf(offset).toISOString().slice(0, 7);
const isWeekendDay = (offset: number) => [0, 6].includes(dateOf(offset).getUTCDay());

const hoursOf = (r: SeedServiceRecord) => r.endHour - r.startHour;
const approved = serviceRecords.filter(r => r.targetStatus === 'approved');

// Keyed by the fixture handle (`student001`), which is what every record's
// `studentUsername` holds — the real login is 8 random digits and means nothing
// to a cross-reference.
const studentByRef = new Map(generatedStudents.filter(s => s.ref).map(s => [s.ref!, s] as const));
const userByRef = new Map(users.map(u => [u.ref ?? u.username, u] as const));
const projectByTitle = new Map(projects.map(p => [p.title, p] as const));
const orgNames = new Set([...organizations.map(o => o.name), 'Example City HD']);

const CATEGORY_IDS = new Set(['cat_campus', 'cat_community', 'cat_welfare', 'cat_education', 'cat_environment', 'cat_other']);
const GRADE_IDS = ['grade_6', 'grade_7', 'grade_8', 'grade_9', 'grade_10', 'grade_11', 'grade_12'];
/** The two stage entry grades: both are fresh intakes with no prior-year history (13-AnnualRollover.md §2). */
const INTAKE_GRADE_IDS = ['grade_6', 'grade_9'];
const CLASS_IDS = new Set([
  ...GRADE_IDS.flatMap(g => { const l = g.replace('grade_', ''); return [`class_${l}A`, `class_${l}B`]; }),
  ...namedClasses.map(c => c.id), 'class_10_luna',
]);

/** Grades 6-8 are middle school, 9-11 high — `EffectiveRule.MIDDLE_SCHOOL_MAX_GRADE_LEVEL`. */
const isMiddle = (gradeId: string) => Number(gradeId.replace('grade_', '')) <= 8;
/** Hours per year and cumulative, from `rule_default_2025` in `R__seed_base_data.sql`. */
const annualRequiredHours = (gradeId: string) => (isMiddle(gradeId) ? 25 : 75);
const stageRequiredHours = (gradeId: string) => (isMiddle(gradeId) ? 75 : 300);
/** Honour tiers, same source. */
const HONOUR_TIERS: Array<[string, number]> = [['excellent', 300], ['gold', 150], ['silver', 75], ['bronze', 25]];

const hoursByStudent = new Map<string, number>();
for (const r of approved) hoursByStudent.set(r.studentUsername, (hoursByStudent.get(r.studentUsername) ?? 0) + hoursOf(r));

const inYear = (offset: number, y: { first: number; last: number }) => offset >= y.first && offset <= y.last;

// ══════════════════════════════════════════════════════════════════════════
// BACKEND — what the real API would reject
// ══════════════════════════════════════════════════════════════════════════

for (const r of serviceRecords) {
  const who = `${r.studentUsername} @${r.serviceDateOffsetDays}d ${r.startHour}-${r.endHour}`;
  const hours = hoursOf(r);

  // The active rule rounds half-up to 30 minutes, so anything off a half-hour
  // boundary credits more coins than the hours it shows — which reads as an
  // arithmetic bug on screen rather than as a fixture choice.
  check('BACKEND', '30-minute durations', hours > 0 && Math.abs(hours * 2 - Math.round(hours * 2)) < 1e-9,
    `${who}: duration ${hours}h is not a positive multiple of 0.5h`);
  check('BACKEND', 'no future service dates', r.serviceDateOffsetDays <= 0,
    `${who}: service date is in the future (FUTURE_DATE)`);
  check('CREDIBLE', 'session length is plausible', hours <= 8,
    `${who}: ${hours}h in one session`);
  check('CREDIBLE', 'sessions end at a sane hour', r.endHour <= 22,
    `${who}: ends at ${r.endHour}:00`);

  const student = studentByRef.get(r.studentUsername);
  check('BACKEND', 'record belongs to a login student', !!student,
    `${r.studentUsername}: has a record but no generated student with that handle`);
  check('BACKEND', 'record author has a linked student row', !!userByRef.get(r.studentUsername)?.linkedStudentNumber,
    `${r.studentUsername}: user has no linkedStudentNumber, so the backend cannot resolve the student`);

  check('BACKEND', 'known service category', CATEGORY_IDS.has(r.serviceCategoryId),
    `${who}: unknown serviceCategoryId ${r.serviceCategoryId}`);
  if (r.organizationName) {
    check('BACKEND', 'known organisation', orgNames.has(r.organizationName),
      `${who}: organizationName ${JSON.stringify(r.organizationName)} is not a seeded organisation`);
  }
  if (r.sourceType === 'SELF') {
    check('BACKEND', 'SELF records name a supervisor', !!r.supervisorKind,
      `${who}: SELF record without supervisorKind (REQUIRED at submit)`);
  }

  if (r.targetStatus !== 'draft' && r.targetStatus !== 'submitted') {
    const reviewer = r.reviewerUsername;
    check('BACKEND', 'decided records have a reviewer', !!reviewer, `${who}: ${r.targetStatus} with no reviewerUsername`);
    if (reviewer && student) {
      check('BACKEND', 'reviewer is not the student', reviewer !== r.studentUsername, `${who}: reviewed by the student`);
      // A teacher without scope over the student's grade gets a 404, not a 403
      // (B1.7's no-existence-leak), so a mis-routed decision fails as if the
      // record did not exist — the least legible failure the seeder can hit.
      const expected = reviewerForGrade[student.gradeId];
      const isAdmin = userByRef.get(reviewer)?.roleCodes.includes('admin');
      check('BACKEND', 'reviewer holds scope over the grade', reviewer === expected || !!isAdmin,
        `${who}: reviewed by ${reviewer}, but ${student.gradeId} is ${expected}'s scope`);
    }
  }

  if (r.sourceType === 'PROJECT') {
    const project = r.projectTitle ? projectByTitle.get(r.projectTitle) : undefined;
    check('BACKEND', 'project-sourced records name a real project', !!project,
      `${who}: projectTitle ${JSON.stringify(r.projectTitle)} matches no seeded project`);
    if (project && student) {
      check('BACKEND', 'project record author is enrolled', (project.registrantStudentNumbers ?? []).includes(student.studentNumber),
        `${who}: not a confirmed registrant of ${project.title} (NOT_CONFIRMED at submit)`);
      check('REPORTS', 'project hours sit inside the project window',
        r.serviceDateOffsetDays >= project.startOffsetDays && r.serviceDateOffsetDays <= project.endOffsetDays,
        `${who}: outside ${project.title}'s window ${project.startOffsetDays}..${project.endOffsetDays}`);
    }
  }
}

// Overlap is checked across *all* statuses: submission blocks a clash with any
// of the student's other records, not only approved ones (issues 064-074).
{
  const byStudentDay = new Map<string, SeedServiceRecord[]>();
  for (const r of serviceRecords) {
    const k = `${r.studentUsername}|${r.serviceDateOffsetDays}`;
    byStudentDay.set(k, [...(byStudentDay.get(k) ?? []), r]);
  }
  for (const [k, rs] of byStudentDay) {
    for (let i = 0; i < rs.length; i += 1) {
      for (let j = i + 1; j < rs.length; j += 1) {
        check('BACKEND', 'no overlapping records for one student',
          !(rs[i].startHour < rs[j].endHour && rs[j].startHour < rs[i].endHour),
          `${k}: ${rs[i].startHour}-${rs[i].endHour} overlaps ${rs[j].startHour}-${rs[j].endHour} (OVERLAPPING_RECORD)`);
      }
    }
    check('CREDIBLE', 'at most one session per student per day', rs.length <= 1,
      `${k}: ${rs.length} records on one day`);
    const dayHours = rs.reduce((a, r) => a + hoursOf(r), 0);
    check('CREDIBLE', 'a student day is not implausibly long', dayHours <= 8,
      `${k}: ${dayHours}h of service in one day`);
  }
}

// ── Projects ──────────────────────────────────────────────────────────────

for (const p of projects) {
  const at = `project ${JSON.stringify(p.title)}`;
  check('BACKEND', 'project dates are ordered',
    p.registrationOpenOffsetDays <= p.registrationCloseOffsetDays
    && p.registrationCloseOffsetDays <= p.startOffsetDays && p.startOffsetDays <= p.endOffsetDays,
    `${at}: reg ${p.registrationOpenOffsetDays}..${p.registrationCloseOffsetDays}, run ${p.startOffsetDays}..${p.endOffsetDays}`);
  check('BACKEND', 'known service category', CATEGORY_IDS.has(p.categoryId), `${at}: unknown categoryId ${p.categoryId}`);
  check('BACKEND', 'project recognizedMinutes is a half-hour multiple', p.recognizedMinutes % 30 === 0,
    `${at}: recognizedMinutes ${p.recognizedMinutes}`);
  check('BACKEND', 'project owner can create projects',
    ['teacher', 'admin'].some(role => userByRef.get(p.ownerUsername)?.roleCodes.includes(role)),
    `${at}: owner ${p.ownerUsername} does not hold projects.create`);

  // `ProjectServiceImpl.syncTimeBasedStatus` sends a project whose registration
  // window closed with zero confirmed registrants straight to ARCHIVED, with no
  // error — so the seed "succeeds" with the project in a status nobody asked for.
  if (p.registrationCloseOffsetDays < 0 && p.targetStatus !== 'draft') {
    check('BACKEND', 'a closed registration window has registrants', (p.registrantStudentNumbers ?? []).length > 0,
      `${at}: registration closed at ${p.registrationCloseOffsetDays}d with no confirmed registrants — auto-archives`);
  }

  for (const number of p.registrantStudentNumbers ?? []) {
    const student = students.find(s => s.studentNumber === number);
    check('BACKEND', 'registrant exists', !!student, `${at}: registrant ${number} is not on the roster`);
    if (student) {
      check('BACKEND', 'registrant is in an eligible grade', p.eligibleGradeIds.includes(student.gradeId),
        `${at}: registrant ${number} is ${student.gradeId}, eligible are ${p.eligibleGradeIds.join('/')}`);
    }
  }

  // Status is derived from the dates by the backend, never set directly, so a
  // mismatch here means the seeded project silently lands somewhere else.
  const derived = ((): string => {
    if (p.startOffsetDays <= 0 && p.endOffsetDays < 0) return 'completed';
    if (p.startOffsetDays <= 0 && p.endOffsetDays >= 0) return 'inProgress';
    if (p.registrationCloseOffsetDays < 0) return 'closed';
    if (p.registrationOpenOffsetDays <= 0) return 'registrationOpen';
    return 'published';
  })();
  if (p.targetStatus !== 'draft') {
    check('BACKEND', 'project status matches its dates', p.targetStatus === derived,
      `${at}: declared ${p.targetStatus}, but its dates derive ${derived}`);
  }
}

// ── Proposals, cases, users ───────────────────────────────────────────────

for (const p of eventProposals) {
  const at = `proposal ${JSON.stringify(p.title)}`;
  check('BACKEND', 'proposal is in the future', p.plannedStartOffsetDays > 0,
    `${at}: planned start ${p.plannedStartOffsetDays}d is not in the future`);
  check('BACKEND', 'proposal ends after it starts', p.plannedEndOffsetDays >= p.plannedStartOffsetDays,
    `${at}: ${p.plannedStartOffsetDays}..${p.plannedEndOffsetDays}`);
  check('BACKEND', 'proposal supervisor is a teacher',
    !!userByRef.get(p.supervisorUsername)?.roleCodes.includes('teacher'),
    `${at}: supervisor ${p.supervisorUsername} is not a teacher (checked at create, not approve)`);
  check('BACKEND', 'proposal names at most one organisation', !(p.organizationName && p.newOrganizationName),
    `${at}: organizationName and newOrganizationName are mutually exclusive`);
  check('BACKEND', 'proposal author is a student', !!userByRef.get(p.studentUsername)?.linkedStudentNumber,
    `${at}: ${p.studentUsername} has no linked student row`);
  if (p.proposedRecognizedMinutes !== undefined) {
    check('BACKEND', 'proposal minutes are a half-hour multiple', p.proposedRecognizedMinutes % 30 === 0,
      `${at}: proposedRecognizedMinutes ${p.proposedRecognizedMinutes}`);
  }
}

{
  const recordKeys = new Set(serviceRecords.map(r => r.key).filter(Boolean));
  for (const c of cases) {
    const at = `case ${JSON.stringify(c.title)}`;
    if (c.subjectType === 'serviceRecord') {
      check('BACKEND', 'case subject resolves to a record', !!c.subjectId && recordKeys.has(c.subjectId),
        `${at}: subjectId ${JSON.stringify(c.subjectId)} matches no SeedServiceRecord.key`);
    }
    check('BACKEND', 'case author is a student', !!userByRef.get(c.studentUsername)?.linkedStudentNumber,
      `${at}: ${c.studentUsername} has no linked student row`);
  }
}

// A student's login is 8 random digits — `09-StudentActivationFlow.md` §3, and
// `StudentUsernameGenerator` in the backend. The fixture used `student001`…
// `student030`, which breaks both halves of the rule at once: wrong format, and
// sequential, which is what §2.1 rejected because it leaks enrolment order and
// cohort size. Staff are exempt by design — an administrator names those.
{
  const studentLogins = users.filter(u => u.roleCodes.includes('student')).map(u => u.username);
  for (const login of studentLogins) {
    check('BACKEND', 'student logins are 8 digits', /^[1-9][0-9]{7}$/.test(login),
      `student login ${JSON.stringify(login)} is not 8 digits with no leading zero`);
  }
  check('BACKEND', 'student logins are unique', new Set(studentLogins).size === studentLogins.length,
    'two students share a login');
  // Sorted-and-consecutive is the signature of a sequential allocator; a random
  // draw over 9x10^7 will not produce it for 30 accounts.
  const numeric = studentLogins.map(Number).filter(n => !Number.isNaN(n)).sort((a, b) => a - b);
  const consecutive = numeric.length > 1 && numeric.every((n, i) => i === 0 || n === numeric[i - 1] + 1);
  check('BACKEND', 'student logins are random, not sequential', !consecutive,
    'student logins form a consecutive run, which leaks enrolment order and cohort size');
}

for (const u of users) {
  const at = `user ${u.username}`;
  // Every account holds exactly one role since 07-RoleFinetune §2.5.
  check('BACKEND', 'exactly one role per user', u.roleCodes.length === 1,
    `${at}: ${u.roleCodes.length} roles (${u.roleCodes.join(', ')})`);
  if (!u.roleCodes.includes('student')) {
    // Without a staffId the account logs in fine but cannot be edited at all:
    // `UserController.requireRoleFields` rejects every PATCH (issue 100).
    check('BACKEND', 'staff accounts carry a staffId', !!u.staffId, `${at}: non-student account with no staffId`);
  } else {
    check('BACKEND', 'student accounts link a roster row', !!u.linkedStudentNumber, `${at}: student account with no linkedStudentNumber`);
  }
}

// `13-AnnualRollover.md` §5: a student is always exactly 6 + n or 9 + n years
// past their entry year, and the rollover's own consistency check counts the
// rows where that fails. Asserting it on the fixture means the seeded roster
// cannot be the thing that makes that check fire.
for (const s of students) {
  const at = `student ${s.studentNumber} (${s.gradeId})`;
  const entry = entryLevelFor(s.gradeId);
  const derived = entry + (CURRENT_ACADEMIC_YEAR_START - enrollmentYearStartFor(s.gradeId));
  check('REPORTS', 'stored grade matches the enrolment cohort', derived === gradeLevelOf(s.gradeId),
    `${at}: enrolled ${enrollmentYearStartFor(s.gradeId)} at grade ${entry} derives grade ${derived}`);
  check('REPORTS', 'graduation year is the stage exit',
    s.graduationYear === enrollmentYearStartFor(s.gradeId) + (stageExitLevelFor(s.gradeId) - entry) + 1,
    `${at}: graduationYear ${s.graduationYear} does not match leaving grade ${stageExitLevelFor(s.gradeId)}`);
}

for (const s of students) {
  const at = `student ${s.studentNumber}`;
  check('BACKEND', 'known grade', GRADE_IDS.includes(s.gradeId), `${at}: unknown gradeId ${s.gradeId}`);
  check('BACKEND', 'known class', CLASS_IDS.has(s.classId), `${at}: unknown classId ${s.classId}`);
  check('BACKEND', 'class belongs to the grade',
    s.classId.startsWith(`class_${s.gradeId.replace('grade_', '')}`),
    `${at}: ${s.classId} is not a class of ${s.gradeId}`);
}

// ══════════════════════════════════════════════════════════════════════════
// REPORTS — things that make a report answer nothing while looking fine
// ══════════════════════════════════════════════════════════════════════════

for (const r of serviceRecords) {
  const where = `${r.studentUsername} @${r.serviceDateOffsetDays}d`;
  // Annual progress and every report sum `service_date between :yearStart and
  // :yearEnd`. A record outside both seeded years counts for nothing anywhere,
  // while still showing up in the student's own list — which is how a student
  // came to hold 89 coins against 0 hours of progress (issue 100).
  check('REPORTS', 'every record falls in a seeded academic year',
    inYear(r.serviceDateOffsetDays, priorYear) || inYear(r.serviceDateOffsetDays, currentYear),
    `${where}: outside both ${priorYear.first}..${priorYear.last} and ${currentYear.first}..${currentYear.last}`);

  const student = studentByRef.get(r.studentUsername);
  if (student) {
    const joined = academicYearWindow(enrollmentYearStartFor(student.gradeId)).first;
    check('REPORTS', 'no service before the student enrolled', r.serviceDateOffsetDays >= joined,
      `${where}: ${student.gradeId} enrolled at ${joined}d`);
  }
}

for (const [label, year] of [['prior', priorYear], ['current', currentYear]] as const) {
  const inThisYear = approved.filter(r => inYear(r.serviceDateOffsetDays, year));
  check('REPORTS', 'both years carry approved service', inThisYear.length > 0, `${label} year has no approved records`);

  // `top_projects` scopes projects by `project.start_at` inside the year, and
  // sums only records carrying that project_id. Both halves have to land in the
  // same year or the report shows participants beside 0h (issue 103 §2.4).
  const projectsStarting = projects.filter(p => inYear(p.startOffsetDays, year) && (p.registrantStudentNumbers ?? []).length > 0);
  const linkedHours = inThisYear.filter(r => r.sourceType === 'PROJECT');
  check('REPORTS', 'both years can populate Top Projects', projectsStarting.length > 0,
    `${label} year has no project with confirmed registrants starting in it`);
  check('REPORTS', 'both years have project-linked hours', linkedHours.length > 0,
    `${label} year has no approved PROJECT-sourced record, so every Top Projects row reads 0h`);

  const months = new Set(inThisYear.map(r => ymOf(r.serviceDateOffsetDays)));
  const wanted = label === 'prior' ? 8 : 1;
  check('REPORTS', 'the monthly trend has enough points', months.size >= wanted,
    `${label} year spans ${months.size} month(s), wanted at least ${wanted}`);
  note(`${label} year: ${inThisYear.length} approved records, ${sum(inThisYear.map(hoursOf))}h, ${months.size} months, ${projectsStarting.length} reportable projects`);
}

// Both intakes entered this September, so prior-year history for either would
// contradict their own roster row — and it is exactly the population rule
// `participationByGrade` encodes. Grade 9 joins grade 6 here because finishing
// grade 8 is a graduation, not a promotion: a grade 9 student is a new record.
for (const gradeId of INTAKE_GRADE_IDS) {
  const offenders = approved.filter(r => {
    const s = studentByRef.get(r.studentUsername);
    return s?.gradeId === gradeId && inYear(r.serviceDateOffsetDays, priorYear);
  });
  check('REPORTS', 'neither intake has prior-year history', offenders.length === 0,
    `${offenders.length} ${gradeId} records dated before this September`);
}

// ══════════════════════════════════════════════════════════════════════════
// CREDIBLE — would a school believe this?
// ══════════════════════════════════════════════════════════════════════════

{
  const weekendRecords = approved.filter(r => isWeekendDay(r.serviceDateOffsetDays)).length;
  const share = weekendRecords / Math.max(1, approved.length);
  check('CREDIBLE', 'volunteering is weekend-weighted', share >= 0.45,
    `only ${(share * 100).toFixed(0)}% of sessions fall at a weekend`);
  note(`weekend share: ${(share * 100).toFixed(0)}% of ${approved.length} approved sessions`);
}

// Achieved hours must equal the target the generator was asked for; a silent
// shortfall would mean the placement ran out of days, which is invisible
// otherwise because the balance assertion checks the ledger against the records
// rather than against the intent.
for (const s of generatedStudents) {
  if (!s.ref) continue;
  const got = hoursByStudent.get(s.ref) ?? 0;
  check('CREDIBLE', 'each student reaches their target', Math.abs(got - s.targetCoins) < 1e-9,
    `${s.ref}: ${got}h placed against a target of ${s.targetCoins}h`);
}

{
  const tierCounts = new Map(HONOUR_TIERS.map(([name]) => [name, 0]));
  for (const s of generatedStudents) {
    if (!s.ref) continue;
    const h = hoursByStudent.get(s.ref) ?? 0;
    const tier = HONOUR_TIERS.find(([, min]) => h >= min);
    if (tier) tierCounts.set(tier[0], tierCounts.get(tier[0])! + 1);
  }
  for (const [name] of HONOUR_TIERS) {
    check('CREDIBLE', 'every honour tier is represented', tierCounts.get(name)! > 0,
      `no student reaches the ${name} tier, so it cannot be demonstrated`);
  }
  note(`honour tiers: ${[...tierCounts].map(([n, c]) => `${n} ${c}`).join(', ')}`);
}

{
  const meets = (band: 'middle' | 'high') => generatedStudents.filter(s =>
    s.ref && (band === 'middle' ? isMiddle(s.gradeId) : !isMiddle(s.gradeId))
    && (hoursByStudent.get(s.ref) ?? 0) >= stageRequiredHours(s.gradeId)).length;
  check('CREDIBLE', 'the cumulative stage requirement is reachable', meets('middle') > 0,
    'no middle-school student meets the 75h cumulative requirement');
  check('CREDIBLE', 'the cumulative stage requirement is reachable', meets('high') > 0,
    'no high-school student meets the 300h cumulative requirement');
  note(`stage requirement met: ${meets('middle')} middle-school, ${meets('high')} high-school`);
}

for (const gradeId of GRADE_IDS) {
  check('CREDIBLE', 'every grade is represented on the roster',
    students.some(s => s.gradeId === gradeId), `no student is in ${gradeId}`);
}

{
  const zero = generatedStudents.filter(s => s.ref && (hoursByStudent.get(s.ref) ?? 0) === 0).length;
  check('CREDIBLE', 'empty-state students are preserved', zero >= 5,
    `only ${zero} students have no service at all; the empty-state screens need some`);
  note(`students with no service: ${zero}`);
}

// Each grade should show both sides of its annual bar, or the completion report
// is a column of identical answers.
for (const gradeId of GRADE_IDS) {
  const cohort = generatedStudents.filter(s => s.ref && s.gradeId === gradeId);
  const priorHours = (username: string) => sum(approved
    .filter(r => r.studentUsername === username && inYear(r.serviceDateOffsetDays, priorYear)).map(hoursOf));
  const req = annualRequiredHours(gradeId);
  const met = cohort.filter(s => priorHours(s.ref!) >= req).length;
  if (INTAKE_GRADE_IDS.includes(gradeId)) continue; // an intake has no prior year to have met anything in
  check('CREDIBLE', 'each grade shows both sides of its annual bar', met > 0 && met < cohort.length,
    `${gradeId}: ${met} of ${cohort.length} met last year's ${req}h — wanted a mix`);
}

// Each student's records must be listed in date order. `seed.ts` creates,
// submits and decides them in this order, and the backend stamps
// `account_transaction.balance_after` as it credits — so approving them out of
// order gives the account page a running balance that contradicts its own date
// column, while every individual figure remains correct.
{
  const seen = new Map<string, SeedServiceRecord>();
  for (const r of serviceRecords) {
    const prev = seen.get(r.studentUsername);
    if (prev) {
      const ordered = prev.serviceDateOffsetDays < r.serviceDateOffsetDays
        || (prev.serviceDateOffsetDays === r.serviceDateOffsetDays && prev.startHour <= r.startHour);
      check('REPORTS', 'a student\'s records are in chronological order', ordered,
        `${r.studentUsername}: @${r.serviceDateOffsetDays}d ${r.startHour}h follows @${prev.serviceDateOffsetDays}d ${prev.startHour}h`);
    }
    seen.set(r.studentUsername, r);
  }
}

// ── Report ────────────────────────────────────────────────────────────────

function sum(xs: number[]): number {
  return xs.reduce((a, b) => a + b, 0);
}

const bySeverity = (s: Severity) => failures.filter(f => f.severity === s);

console.log('Fixture invariants (issue 103)\n');
console.log(`  academic years : prior ${priorYear.first}..${priorYear.last}d, current ${currentYear.first}..${currentYear.last}d`);
console.log(`  records        : ${serviceRecords.length} (${approved.length} approved)`);
console.log(`  students       : ${students.length} (${generatedStudents.filter(s => s.ref).length} with a login)`);
console.log(`  projects       : ${projects.length}   proposals: ${eventProposals.length}   cases: ${cases.length}`);
console.log(`  total hours    : ${sum(approved.map(hoursOf))}h\n`);
for (const line of notes) console.log(`  · ${line}`);

if (failures.length === 0) {
  console.log('\nAll invariants hold.');
  process.exit(0);
}

console.log('');
for (const severity of ['BACKEND', 'REPORTS', 'CREDIBLE'] as Severity[]) {
  const group = bySeverity(severity);
  if (group.length === 0) continue;
  console.log(`${severity} — ${group.length} failure(s)`);
  const byRule = new Map<string, Failure[]>();
  for (const f of group) byRule.set(f.rule, [...(byRule.get(f.rule) ?? []), f]);
  for (const [rule, fs] of byRule) {
    console.log(`  ${rule} (${fs.length})`);
    for (const f of fs.slice(0, 5)) console.log(`      ${f.detail}`);
    if (fs.length > 5) console.log(`      … and ${fs.length - 5} more`);
  }
  console.log('');
}
console.log(`${failures.length} invariant failure(s).`);
process.exit(1);
