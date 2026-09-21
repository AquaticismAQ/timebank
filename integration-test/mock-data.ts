// The canonical seed dataset for the TimeBank test server. Read
// integration-test/MOCK_DATA_GUIDE.md before editing this file.
//
// Two kinds of data live here:
//
//   * CURATED fixtures (organizations, staff accounts, projects, proposals,
//     cases) are written out by hand, because each one carries a specific
//     narrative some screen or workflow needs.
//   * GENERATED fixtures (50 students and their service records) come from
//     the deterministic builders at the bottom. Hand-writing 50 students and
//     ~140 records would make this file unreviewable; generating them from a
//     fixed seed keeps it short AND makes every run produce byte-identical
//     data, so a re-seed is reproducible and diffable.
//
// Date fields are all *offsets in days relative to whenever seed.ts runs*
// (see `daysFromNow` in seed.ts), never absolute dates — several backend
// statuses are computed from "is this timestamp before/after now", so a
// hardcoded date would silently go stale the day after it was written.
//
// ── Two consistency rules this file must never break ────────────────────
//
// 1. DURATIONS ARE WHOLE MULTIPLES OF 30 MINUTES. The active rule
//    (`rule_default_2025`) rounds half-up to 30 minutes, converts 60 minutes
//    to one unit, and pays 100 centi-coins per unit — so one hour of approved
//    service is exactly 1.00 coin. Keeping every duration on a 30-minute
//    boundary makes rounding a no-op, so the hours a student sees served and
//    the coins they see credited always agree. A record of 1h47m would round
//    to 2h and credit 2.00 coins, which looks like a bug to anyone reading
//    the screen.
// 2. BALANCES ARE NEVER WRITTEN DIRECTLY. Coins exist only because an
//    approved record credited the ledger. `targetCoins` below is the amount
//    the generator *composes approved records to reach*, not a number written
//    anywhere. Do not add a "balance" field.

// ── Reference data already seeded by Flyway on every fresh schema ─────────
// (R__seed_base_data.sql) — not created here, just the ids this file uses:
//   grade:    grade_6 .. grade_11
//   class:    class_6A/6B .. class_11A/11B
//   academic_year: ay_2025_2026
//   service_category: cat_campus, cat_community, cat_welfare, cat_education, cat_environment, cat_other
//
// ── The school calendar this fixture is dated against ─────────────────────
//
// Everything below is derived from *today*, never from a literal. A literal
// school year is correct on the day it is typed and wrong from the next
// September (issue 100), and `R__seed_base_data.sql` derives its own
// `academic_year` rows the same way — September starts the year — so the
// fixture and the schema agree about where the boundaries are without either
// importing the other.

const MS_PER_DAY = 86_400_000;

/** Midnight UTC today, the zero point every `*OffsetDays` in this file counts from. */
const TODAY_UTC = (() => {
  const now = new Date();
  return Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate());
})();

/** Calendar year the current school year started in — 2026 for any date from 2026-09-01 to 2027-08-31. */
export const CURRENT_ACADEMIC_YEAR_START = (() => {
  const now = new Date(TODAY_UTC);
  return now.getUTCMonth() >= 8 ? now.getUTCFullYear() : now.getUTCFullYear() - 1; // month 8 === September
})();

/** Day offset (negative = in the past) of 1 September of the school year starting in `startYear`. */
function academicYearStartOffset(startYear: number): number {
  return Math.floor((Date.UTC(startYear, 8, 1) - TODAY_UTC) / MS_PER_DAY);
}

/**
 * Inclusive day-offset bounds of a school year, clipped so the end is never in
 * the future — a service date after today is rejected outright
 * (`ServiceRecordServiceImpl`'s `FUTURE_DATE`), so the current year's usable
 * window ends yesterday however much of it remains on the calendar.
 */
export function academicYearWindow(startYear: number): { first: number; last: number } {
  return {
    first: academicYearStartOffset(startYear),
    last: Math.min(-1, academicYearStartOffset(startYear + 1) - 1),
  };
}

/** Days elapsed since the current school year began — 17 on 18 September, 300 in June. */
export function daysSinceAcademicYearStart(): number {
  return -academicYearStartOffset(CURRENT_ACADEMIC_YEAR_START);
}

/** Saturday or Sunday. Volunteering is weekend-weighted, and a fixture that isn't reads as invented. */
function isWeekend(offsetDays: number): boolean {
  const day = new Date(TODAY_UTC + offsetDays * MS_PER_DAY).getUTCDay();
  return day === 0 || day === 6;
}

/**
 * The school runs two stages, not one continuous six-year line
 * (`Design/*\u002f14-AnnualRollover.md` §2): grades 6-7-8, then grades
 * 9-10-11-12. A student enters at grade 6 **or** at grade 9, and finishing
 * grade 8 is a graduation followed by a fresh intake with a new student number
 * — not a promotion.
 *
 * So "the grade a student entered at" is not a constant, and the year they
 * joined follows from their current grade and their stage.
 */
const JUNIOR_ENTRY_LEVEL = 6;
const SENIOR_ENTRY_LEVEL = 9;

export function gradeLevelOf(gradeId: string): number {
  return Number(gradeId.replace('grade_', ''));
}

/** 6 for the junior stage, 9 for the senior one. */
export function entryLevelFor(gradeId: string): number {
  return gradeLevelOf(gradeId) <= 8 ? JUNIOR_ENTRY_LEVEL : SENIOR_ENTRY_LEVEL;
}

/** The last grade of the student's stage — finishing it graduates them. */
export function stageExitLevelFor(gradeId: string): number {
  return gradeLevelOf(gradeId) <= 8 ? 8 : 12;
}

/**
 * The year this student's cohort entered their stage. `seed.ts` wrote the
 * *current* year for every student, which made every senior a first-year
 * entrant and — because `ReportQueryRepository` joins `ay.start_date <= :end` —
 * emptied the population of every report scoped to an earlier year
 * (issue 103 §2.2).
 *
 * Grades 6 and 9 both come out as the current year, which is correct: they are
 * the two intakes, and neither has any history yet.
 */
export function enrollmentYearStartFor(gradeId: string): number {
  return CURRENT_ACADEMIC_YEAR_START - (gradeLevelOf(gradeId) - entryLevelFor(gradeId));
}

/** `academic_year.id` as `R__seed_base_data.sql` composes it, for the year a grade's cohort joined. */
export function enrollmentAcademicYearIdFor(gradeId: string): string {
  const start = enrollmentYearStartFor(gradeId);
  return `ay_${start}_${start + 1}`;
}

/**
 * Every cohort year the roster references, oldest first — `seed.ts` inserts the
 * ones Flyway's base data does not already ship (it ships the current year and
 * the one before it). They carry no data of their own, which is deliberate:
 * issue 093 wanted a year the reports return nothing for, and an unused
 * enrolment cohort is a truthful one, unlike an invented empty year.
 */
export const cohortAcademicYearStarts: number[] = Array.from(
  new Set(gradeIdsForCohorts().map(enrollmentYearStartFor))).sort((a, b) => a - b);

function gradeIdsForCohorts(): string[] {
  return ['grade_6', 'grade_7', 'grade_8', 'grade_9', 'grade_10', 'grade_11', 'grade_12'];
}

/**
 * Coin targets by grade and by the student's slot within it.
 *
 * Hand-written per grade rather than drawn from one distribution, because the
 * dataset has to *cover* the institutional design rather than merely look
 * random (issue 103 §2.3, where it covered almost none of it). Reading down a
 * column gives the five states every grade should be able to show:
 *
 *   slot 0  no service at all                 — the empty-state screens
 *   slot 1  short of the annual requirement   — the "gap to target" state
 *   slot 2  annual requirement met
 *   slot 3  cumulative stage requirement met
 *   slot 4  a sustained volunteer, high honour tier
 *
 * The magnitudes rise with the grade because an older student has had more
 * years to accumulate, and because the bar itself rises: grades 6-8 need 25h a
 * year and 75h cumulative, grades 9-11 need 75h and 300h
 * (`R__seed_base_data.sql`'s `rule_default_2025`).
 *
 * Grade 6 is deliberately tiny. Its cohort entered this September, so the only
 * window it can have served in is the handful of days since — a Grade 6 student
 * with a year of history would be a fixture contradicting its own roster row.
 */
const COIN_TARGETS_BY_GRADE: Record<string, number[]> = {
  // Grades 6 and 9 are the two intakes: their cohort entered this September, so
  // the only window they can have served in is the days since term started.
  grade_6: [0, 2, 4, 6, 9],
  grade_7: [0, 14, 30, 78, 96],
  grade_8: [0, 16, 36, 80, 104],
  grade_9: [0, 2, 5, 8, 11],
  grade_10: [0, 44, 82, 118, 152],
  grade_11: [0, 52, 88, 126, 168],
  grade_12: [0, 58, 92, 156, 304],
};

/**
 * Which of the five slots above a generated student occupies.
 *
 * Grades are assigned round-robin, so every sixth student shares a grade and a
 * plain `floor(index / 6)` would give slot 0 — the no-service slot — to the
 * first six students. Those are exactly the ones the hand-pinned narrative
 * records and every case attach to, so the students meant to demonstrate the
 * empty state were the students carrying the richest history.
 *
 * Offsetting by the position within the grade rotates the slots instead, which
 * still gives each grade all five exactly once.
 */
function gradeSlot(index: number): number {
  const perGrade = gradeIdsForCohorts().length;
  return (Math.floor(index / perGrade) + (index % perGrade)) % 5;
}

function targetCoinsFor(gradeId: string, slot: number, rnd: () => number): number {
  const targets = COIN_TARGETS_BY_GRADE[gradeId];
  const base = targets[Math.min(slot, targets.length - 1)];
  if (base === 0) return 0;
  // A little jitter so the roster does not read as six copies of one table,
  // kept on a half-hour boundary and never enough to cross a threshold the
  // slot exists to sit on the far side of.
  return toHalfHours(base + (rnd() < 0.5 ? 0 : 0.5));
}

/** Nearest half hour — every duration in this file must land on one, or the rule's half-up rounding stops being a no-op. */
function toHalfHours(hours: number): number {
  return Math.round(hours * 2) / 2;
}


// seed.ts additionally creates two NAMED classes (Mars, Luna) via SQL rather
// than adding them to the Flyway base data — base data ships to every real
// deployment, and test fixtures do not belong there.
// `code` is the full class code ("9-Mars"), `name` is the raw short name
// ("Mars") — matching the shape R__seed_base_data.sql uses for class_10A
// ("10A"/"A"). Short word names on purpose: they prove class handling isn't
// hardcoded to single letters.
// Only Mars is created here. `class_10_luna` ships in R__seed_base_data.sql,
// where LocalizationIT needs a multi-character class name to assert against —
// inserting it here too would collide on the primary key.
export const namedClasses = [
  { id: 'class_9_mars', code: '9-Mars', name: 'Mars', gradeId: 'grade_9' },
];

export interface SeedOrganization {
  name: string;
  type: 'SCHOOL' | 'NONPROFIT' | 'COMMUNITY' | 'GOVERNMENT' | 'OTHER';
  contactName?: string;
  contactEmail?: string;
  contactPhone?: string;
  address?: string;
}

// Every name here is deliberately an obvious placeholder (`Docs/README.md`
// §2.8's established convention). No real city, district, school, park or
// institution name appears anywhere in this file.
export const organizations: SeedOrganization[] = [
  { name: 'Example Township Middle School', type: 'SCHOOL', contactName: 'Teacher Wei Chen', contactEmail: 'wei.chen@example-township-school.edu', contactPhone: '0000-88888888', address: 'No. 1 Campus Road, Example District' },
  { name: 'Example Welfare Home', type: 'NONPROFIT', contactName: 'Director Chen', contactEmail: 'welfare@example.org', contactPhone: '0000-87654321', address: 'No. 88 Example Road, Example District' },
  { name: 'Example Environmental Association', type: 'NONPROFIT', contactName: 'President Li', contactEmail: 'enviro@example.org', contactPhone: '0000-87123456', address: 'Example Town, Example District' },
  { name: 'Example Charity Education Association', type: 'NONPROFIT', contactName: 'Director Zhang', contactEmail: 'charity@example.org', contactPhone: '0000-63456789', address: 'Example Town Two, Example County' },
  { name: 'Example Community Service Centre', type: 'COMMUNITY', contactName: 'Director Wu', contactEmail: 'community@example.org', contactPhone: '0000-87001122', address: 'Example District' },
  { name: 'Example Community Library', type: 'GOVERNMENT', contactName: 'Curator Zhao', contactEmail: 'library@example.org', contactPhone: '0000-87005566', address: 'No. 12 Example Avenue, Example District' },
];

export interface SeedUser {
  /** The real login. 8 random digits for a student, an administrator-chosen name for staff. */
  username: string;
  /**
   * Stable handle other fixtures point at (`student001`). Absent for staff,
   * whose own username is readable enough to reference directly.
   *
   * Student logins became random digits so they match what a real roster import
   * produces, and a random number is useless as a cross-reference in a
   * hand-written fixture — so the two are separate values, and `seed.ts` keys
   * everything on this one.
   */
  ref?: string;
  displayName: string;
  email?: string;
  roleCodes: string[];
  /**
   * Required for every non-student account, and not decorative: since issue
   * 003 `UserController.requireRoleFields` rejects a `PATCH /users/{id}` whose
   * *effective* staffId is null — and "effective" falls back to the stored
   * value, so an account seeded without one cannot be edited through the admin
   * UI at all. It fails with "A staff ID is required for non-student accounts"
   * on a form that never showed the field as empty, which is a confusing first
   * hour for anyone who has just seeded a server (issue 100).
   */
  staffId?: string;
  linkedStudentNumber?: string;
  // A teacher with no teacher_scope_assignment rows resolves to
  // ScopeDescriptor.Self, so reviewing anyone else's record 404s (B1.7's
  // deliberate no-existence-leak), not 403s. These five teachers between them
  // cover all six grades with no overlap, which is what makes cross-scope
  // isolation actually testable: a grade_6 record must be invisible to the
  // grade_11 teacher. Admins bypass scope entirely via `grantAll`.
  scopeGrades?: string[];
}

// ── Staff accounts ────────────────────────────────────────────────────────
// One admin, one sysadmin, five teachers. Student accounts are appended by
// the generator below.
const staffUsers: SeedUser[] = [
  { username: 'admin001', displayName: 'Teacher Wei Chen', email: 'wei.chen@example-township-school.edu', roleCodes: ['admin'], staffId: 'STAFF-0001' },
  // `sysadmin` holds the same permission grants as `admin` after V21's merge,
  // but a few surfaces are gated by role code rather than permission (school
  // info editing, for one), so this account exists to make that distinction
  // exercisable against real data rather than only provable in a test.
  { username: 'sysadmin001', displayName: 'System Administrator', email: 'sysadmin@example-township-school.edu', roleCodes: ['sysadmin'], staffId: 'STAFF-0002' },
  { username: 'teacher001', displayName: 'Alex L.', email: 'teacher001@example-township-school.edu', roleCodes: ['teacher'], scopeGrades: ['grade_6'], staffId: 'STAFF-0101' },
  { username: 'teacher002', displayName: 'Jordan W.', email: 'teacher002@example-township-school.edu', roleCodes: ['teacher'], scopeGrades: ['grade_7'], staffId: 'STAFF-0102' },
  { username: 'teacher003', displayName: 'Casey Z.', email: 'teacher003@example-township-school.edu', roleCodes: ['teacher'], scopeGrades: ['grade_8'], staffId: 'STAFF-0103' },
  { username: 'teacher004', displayName: 'Riley H.', email: 'teacher004@example-township-school.edu', roleCodes: ['teacher'], scopeGrades: ['grade_9'], staffId: 'STAFF-0104' },
  { username: 'teacher005', displayName: 'Morgan Y.', email: 'teacher005@example-township-school.edu', roleCodes: ['teacher'], scopeGrades: ['grade_10', 'grade_11', 'grade_12'], staffId: 'STAFF-0105' },
];

/** Which teacher may review a given grade — mirrors `scopeGrades` above. Reviews are routed through this so every decision comes from someone who actually holds scope; routing one to the wrong teacher would 404, not 403. */
export const reviewerForGrade: Record<string, string> = {
  grade_6: 'teacher001',
  grade_7: 'teacher002',
  grade_8: 'teacher003',
  grade_9: 'teacher004',
  grade_10: 'teacher005',
  grade_11: 'teacher005',
  grade_12: 'teacher005',
};

export interface SeedStudent {
  studentNumber: string;
  displayName: string;
  gradeId: string;
  classId: string;
  graduationYear: number;
  joinedAtOffsetDays: number;
  /**
   * The year this student's cohort entered the school, derived from their
   * grade. `seed.ts` wrote the *current* year for all 50, which made every
   * Grade 11 student a first-year enrollee and — because the participation and
   * completion reports join `ay.start_date <= :end` — left every earlier year
   * with an empty population, so those reports could only ever answer nothing
   * (issue 103 §2.2).
   */
  enrollmentAcademicYearId: string;
}

export type RecordStatusTarget = 'draft' | 'submitted' | 'underReview' | 'approved' | 'returned' | 'rejected';

export interface SeedServiceRecord {
  studentUsername: string;
  // No 'EXTERNAL': it is historical/resubmit-only and POST /service-records
  // rejects it outright (issue 03 §3/§7.3), so it is uncreatable from seed.
  sourceType: 'PROJECT' | 'SELF';
  projectTitle?: string;
  organizationName?: string;
  serviceCategoryId: string;
  serviceDateOffsetDays: number;
  /** Whole or half hours only — see the 30-minute rule in this file's header. */
  startHour: number;
  endHour: number;
  location?: string;
  description?: string;
  /** Required for SELF at submission (`INTERNAL_USER`/`STUDENT`/`EXTERNAL_MANUAL`); `EXTERNAL_MANUAL` is the only kind seed data can express. Ignored for PROJECT (derived). */
  supervisorKind?: string;
  supervisorName?: string;
  supervisorContact?: string;
  targetStatus: RecordStatusTarget;
  reviewerUsername?: string;
  reasonCode?: string;
  comment?: string;
  /** Stable lookup key for `SeedCase.subjectId` — real record ids don't exist until seed.ts creates them. Only needed on records a case points at. */
  key?: string;
}

export type ProjectStatusTarget = 'draft' | 'published' | 'registrationOpen' | 'closed' | 'inProgress' | 'completed';

export interface SeedProject {
  title: string;
  summary?: string;
  description?: string;
  categoryId: string;
  organizationName?: string;
  ownerUsername: string;
  deliveryScope: 'ON_CAMPUS' | 'OFF_CAMPUS' | 'HYBRID';
  capacity: number;
  /** Multiple of 30 minutes, same reason as service records. */
  recognizedMinutes: number;
  eligibleGradeIds: string[];
  evidenceRequirements?: string;
  location?: string;
  startOffsetDays: number;
  endOffsetDays: number;
  registrationOpenOffsetDays: number;
  registrationCloseOffsetDays: number;
  targetStatus: ProjectStatusTarget;
  /** Inserted as CONFIRMED enrollments via SQL — a PROJECT-sourced record requires one, and the API's own registration path can't reach CONFIRMED without an admin acting per student. */
  registrantStudentNumbers?: string[];
}

// ── Projects ──────────────────────────────────────────────────────────────
// Ten projects covering all six reachable target statuses. `archived` is
// deliberately absent: archiving is meant to be exercised by hand against one
// of the completed projects during testing, and pre-archiving would leave
// nothing to test the action against.
// Raw definitions; `assignRegistrants` below derives the CONFIRMED registrants
// each one needs and exports the final `projects`.
const rawProjects: SeedProject[] = [
  {
    title: 'Campus Open Day Volunteers', summary: 'Assist with the autumn campus open day as guides and exhibition assistants.',
    description: 'Volunteers maintain order, guide visiting families through subject exhibition areas, and answer basic questions.',
    categoryId: 'cat_campus', organizationName: 'Example Township Middle School', ownerUsername: 'admin001', deliveryScope: 'ON_CAMPUS',
    capacity: 30, recognizedMinutes: 240, eligibleGradeIds: ['grade_9', 'grade_10', 'grade_11'],
    evidenceRequirements: 'On-site photos and a signed confirmation form from the supervising teacher', location: 'School Exhibition Hall',
    startOffsetDays: 30, endOffsetDays: 30, registrationOpenOffsetDays: -10, registrationCloseOffsetDays: 20,
    targetStatus: 'registrationOpen',
  },
  {
    title: 'Rural Teaching Support Team', summary: 'A week-long teaching support programme at a rural primary school.',
    description: 'Participants tutor Chinese, Maths and English at a rural primary school. Room and board arranged by the school.',
    categoryId: 'cat_education', organizationName: 'Example Charity Education Association', ownerUsername: 'admin001', deliveryScope: 'OFF_CAMPUS',
    capacity: 12, recognizedMinutes: 1800, eligibleGradeIds: ['grade_10', 'grade_11'],
    evidenceRequirements: 'Hours certificate from the teaching institution and a personal work journal', location: 'Example Town Two Central Primary School',
    startOffsetDays: -2, endOffsetDays: 3, registrationOpenOffsetDays: -30, registrationCloseOffsetDays: -10,
    targetStatus: 'inProgress',
  },
  {
    title: 'School Library Organisation', summary: 'Assist the librarian with organising books and maintaining the reading environment.',
    description: 'Volunteers classify and shelve books, update labels, and collect reader feedback under the librarian.',
    categoryId: 'cat_campus', organizationName: 'Example Township Middle School', ownerUsername: 'admin001', deliveryScope: 'ON_CAMPUS',
    capacity: 8, recognizedMinutes: 120, eligibleGradeIds: ['grade_6', 'grade_7', 'grade_8', 'grade_9', 'grade_10', 'grade_11'],
    evidenceRequirements: 'Signed confirmation form from the librarian', location: 'School Library',
    startOffsetDays: -60, endOffsetDays: -30, registrationOpenOffsetDays: -90, registrationCloseOffsetDays: -65,
    targetStatus: 'completed',
  },
  {
    title: 'Community Environmental Cleanup', summary: 'Clean community parks and streets and raise environmental awareness.',
    description: 'With the Example Environmental Association, students clean public areas and hand out educational materials.',
    categoryId: 'cat_environment', organizationName: 'Example Environmental Association', ownerUsername: 'teacher005', deliveryScope: 'OFF_CAMPUS',
    capacity: 20, recognizedMinutes: 180, eligibleGradeIds: ['grade_6', 'grade_7', 'grade_8', 'grade_9', 'grade_10'],
    evidenceRequirements: 'Before-and-after photos and a stamped certificate from the organiser', location: 'Example District Central Park',
    startOffsetDays: 25, endOffsetDays: 25, registrationOpenOffsetDays: 5, registrationCloseOffsetDays: 20,
    targetStatus: 'published',
  },
  {
    title: 'Welfare Home Visit and Care', summary: 'Visit the welfare home to spend time with elderly residents.',
    description: 'Volunteers chat with residents, assist with crafts and perform short shows. A briefing on campus is required beforehand.',
    categoryId: 'cat_welfare', organizationName: 'Example Welfare Home', ownerUsername: 'admin001', deliveryScope: 'HYBRID',
    capacity: 15, recognizedMinutes: 120, eligibleGradeIds: ['grade_9', 'grade_10', 'grade_11'],
    evidenceRequirements: 'Stamped confirmation letter from the welfare home and activity photos', location: 'Example Welfare Home',
    // Registration has elapsed but the activity has not started — the only
    // window in which a project rests at `closed`, since a past start date
    // would carry it straight on to `inProgress`.
    startOffsetDays: 6, endOffsetDays: 13, registrationOpenOffsetDays: -20, registrationCloseOffsetDays: -8,
    targetStatus: 'closed',
  },
  {
    title: 'Student Council Welcome Party Support', summary: 'Assist the student council with the welcome party.',
    description: 'Venue setup before the event, check-in and guiding during it, and cleanup afterwards.',
    categoryId: 'cat_campus', organizationName: 'Example Township Middle School', ownerUsername: 'teacher005', deliveryScope: 'ON_CAMPUS',
    capacity: 25, recognizedMinutes: 180, eligibleGradeIds: ['grade_7', 'grade_8', 'grade_9', 'grade_10'],
    evidenceRequirements: 'Signature from the student council president or supervising teacher', location: 'School Auditorium',
    startOffsetDays: 45, endOffsetDays: 45, registrationOpenOffsetDays: 35, registrationCloseOffsetDays: 43,
    targetStatus: 'draft',
  },
  {
    title: 'Community Reading Companions', summary: 'Read with younger children at the community library.',
    description: 'Volunteers read picture books with primary-age children and help the library run its weekend reading corner.',
    categoryId: 'cat_education', organizationName: 'Example Community Library', ownerUsername: 'teacher004', deliveryScope: 'OFF_CAMPUS',
    capacity: 16, recognizedMinutes: 150, eligibleGradeIds: ['grade_7', 'grade_8', 'grade_9'],
    evidenceRequirements: 'Attendance sheet signed by the library curator', location: 'Example Community Library',
    startOffsetDays: -75, endOffsetDays: -40, registrationOpenOffsetDays: -100, registrationCloseOffsetDays: -80,
    targetStatus: 'completed',
  },
  {
    title: 'Campus Recycling Station', summary: 'Run the campus recycling collection point during lunch breaks.',
    description: 'Students staff the recycling station, sort collected material and record weekly volumes.',
    categoryId: 'cat_environment', organizationName: 'Example Township Middle School', ownerUsername: 'teacher003', deliveryScope: 'ON_CAMPUS',
    capacity: 18, recognizedMinutes: 90, eligibleGradeIds: ['grade_6', 'grade_7', 'grade_8'],
    evidenceRequirements: 'Weekly log sheet countersigned by the supervising teacher', location: 'School Courtyard',
    startOffsetDays: 20, endOffsetDays: 40, registrationOpenOffsetDays: -5, registrationCloseOffsetDays: 15,
    targetStatus: 'registrationOpen',
  },
  {
    title: 'Neighbourhood Digital Help Desk', summary: 'Help older residents use smartphones and online services.',
    description: 'Volunteers run a help desk at the community centre, assisting residents with everyday smartphone tasks.',
    categoryId: 'cat_community', organizationName: 'Example Community Service Centre', ownerUsername: 'teacher005', deliveryScope: 'OFF_CAMPUS',
    capacity: 10, recognizedMinutes: 120, eligibleGradeIds: ['grade_9', 'grade_10', 'grade_11'],
    evidenceRequirements: 'Session log signed by the community centre coordinator', location: 'Example Community Service Centre',
    startOffsetDays: -90, endOffsetDays: -55, registrationOpenOffsetDays: -120, registrationCloseOffsetDays: -95,
    targetStatus: 'completed',
  },
  {
    title: 'Winter Care Package Assembly', summary: 'Assemble and label winter care packages for families in need.',
    description: 'Volunteers sort donated goods, assemble packages and prepare delivery labels at the community centre.',
    categoryId: 'cat_welfare', organizationName: 'Example Community Service Centre', ownerUsername: 'teacher004', deliveryScope: 'OFF_CAMPUS',
    capacity: 22, recognizedMinutes: 210, eligibleGradeIds: ['grade_8', 'grade_9', 'grade_10', 'grade_11'],
    evidenceRequirements: 'Group photo and a stamped receipt from the community centre', location: 'Example Community Service Centre',
    startOffsetDays: 12, endOffsetDays: 12, registrationOpenOffsetDays: 2, registrationCloseOffsetDays: 10,
    targetStatus: 'published',
  },
  // ── Two projects that ran early in the CURRENT school year ───────────────
  //
  // Every other completed project here started 60-90 days ago, which is the
  // *prior* year. `top_projects` scopes projects by `project.start_at` falling
  // inside the year being asked about, so without these the current year's
  // Top Projects report can only list projects that have not begun — each one
  // correctly showing its registrant count beside 0h, which reads as the
  // broken aggregate of issue 103 §2.4 rather than as an honest empty result.
  //
  // Short, early-term activities are exactly what a school runs in the first
  // fortnight of September, so these carry their own weight rather than being
  // filler for a report.
  {
    title: 'Autumn Term Orientation Helpers', summary: 'Help new Grade 6 students find their way during the first week of term.',
    description: 'Volunteers staff wayfinding points, walk new students to classrooms and answer questions during the opening week.',
    categoryId: 'cat_campus', organizationName: 'Example Township Middle School', ownerUsername: 'admin001', deliveryScope: 'ON_CAMPUS',
    capacity: 20, recognizedMinutes: 180, eligibleGradeIds: ['grade_9', 'grade_10', 'grade_11'],
    evidenceRequirements: 'Duty roster countersigned by the year-group lead', location: 'School Main Entrance',
    startOffsetDays: -15, endOffsetDays: -11, registrationOpenOffsetDays: -25, registrationCloseOffsetDays: -17,
    targetStatus: 'completed',
  },
  {
    title: 'Neighbourhood Recycling Drive', summary: 'A weekend collection round for household recyclables.',
    description: 'Volunteers collect sorted recyclables from neighbouring streets and deliver them to the district collection point.',
    categoryId: 'cat_environment', organizationName: 'Example Environmental Association', ownerUsername: 'teacher003', deliveryScope: 'OFF_CAMPUS',
    capacity: 14, recognizedMinutes: 210, eligibleGradeIds: ['grade_7', 'grade_8', 'grade_9'],
    evidenceRequirements: 'Weight slip from the collection point and a group photo', location: 'Example District Collection Point',
    startOffsetDays: -9, endOffsetDays: -8, registrationOpenOffsetDays: -20, registrationCloseOffsetDays: -11,
    targetStatus: 'completed',
  },
];

export interface SeedEventProposal {
  studentUsername: string;
  title: string;
  objective?: string;
  description?: string;
  expectedOutcome?: string;
  categoryId: string;
  plannedStartOffsetDays: number;
  plannedEndOffsetDays: number;
  location?: string;
  format?: string;
  targetParticipants?: string;
  capacity?: number;
  volunteerTasks?: string;
  supervisorUsername: string;
  organizationName?: string;
  newOrganizationName?: string;
  externalContact?: string;
  safetyPlan?: string;
  resourceNeeds?: string;
  recruitmentPlan?: string;
  /** Multiple of 30 minutes. */
  proposedRecognizedMinutes?: number;
  evidencePlan?: string;
  targetStatus: 'draft' | 'submitted' | 'approved' | 'changesRequested';
  reviewerUsername?: string;
  reasonCode?: string;
  comment?: string;
}

export const eventProposals: SeedEventProposal[] = [
  {
    studentUsername: 'student001', title: 'Campus Reading Promotion and Book Donation',
    objective: 'Promote reading culture through a used-book sale and picture-book donation.',
    description: 'Students collect used books, run a charity sale on campus, and donate picture books to a rural school.',
    expectedOutcome: 'At least 300 books collected and 150 donated.',
    categoryId: 'cat_education', plannedStartOffsetDays: 20, plannedEndOffsetDays: 21,
    location: 'School Library and Playground', format: 'On campus', targetParticipants: 'All grades', capacity: 30,
    volunteerTasks: 'Book collection, sorting, sales desk, packing for donation',
    supervisorUsername: 'teacher005', organizationName: 'Example Township Middle School',
    safetyPlan: 'Two supervising teachers on site; cash handled by the treasurer only.',
    resourceNeeds: 'Six folding tables, signage, cash box',
    recruitmentPlan: 'Class announcements and the student council notice board',
    proposedRecognizedMinutes: 240, evidencePlan: 'Photos, collection log, donation receipt',
    targetStatus: 'approved', reviewerUsername: 'teacher005', reasonCode: 'PLAN_COMPLETE', comment: 'Well-planned, safety arrangements are adequate. Approved.',
  },
  {
    studentUsername: 'student002', title: 'Digital Skills Workshop for Older Residents',
    objective: 'Help older residents use everyday smartphone functions.',
    description: 'Weekend one-to-one sessions covering messaging, health codes, and online appointments.',
    expectedOutcome: 'At least 40 residents assisted.',
    categoryId: 'cat_community', plannedStartOffsetDays: 30, plannedEndOffsetDays: 31,
    location: 'Example Community Service Centre', format: 'Off campus', targetParticipants: 'Grades 9-11', capacity: 12,
    volunteerTasks: 'One-to-one tutoring, registration desk, printed guide handout',
    supervisorUsername: 'teacher005', organizationName: 'Example Community Service Centre',
    safetyPlan: 'Students travel in pairs; centre staff present throughout.',
    resourceNeeds: 'Printed guides, two spare handsets',
    recruitmentPlan: 'Sign-up form circulated to grades 9-11',
    proposedRecognizedMinutes: 180, evidencePlan: 'Attendance sheet and session photos',
    targetStatus: 'changesRequested', reviewerUsername: 'teacher005', reasonCode: 'INSUFFICIENT_DETAIL', comment: 'Please add the travel arrangements and a named contact at the centre.',
  },
  {
    studentUsername: 'student004', title: 'Campus Waste Sorting Awareness Campaign',
    objective: 'Improve waste sorting accuracy across the campus.',
    description: 'A week of lunchtime demonstrations plus monitoring at the main bins.',
    expectedOutcome: 'Measurable reduction in mis-sorted waste.',
    categoryId: 'cat_environment', plannedStartOffsetDays: 25, plannedEndOffsetDays: 29,
    location: 'School Courtyard', format: 'On campus', targetParticipants: 'Grades 7-9', capacity: 20,
    volunteerTasks: 'Demonstrations, bin monitoring, poster design',
    supervisorUsername: 'teacher003', organizationName: 'Example Township Middle School',
    safetyPlan: 'Gloves and tongs provided; no handling of sharp waste.',
    resourceNeeds: 'Gloves, tongs, posters',
    recruitmentPlan: 'Environmental club recruitment',
    proposedRecognizedMinutes: 150, evidencePlan: 'Daily monitoring log and photos',
    targetStatus: 'submitted',
  },
  {
    studentUsername: 'student007', title: 'Cross-Grade Subject Tutoring',
    objective: 'Pair senior students with juniors needing subject support.',
    categoryId: 'cat_education', plannedStartOffsetDays: 40, plannedEndOffsetDays: 60,
    location: 'School Classrooms', format: 'On campus', capacity: 16,
    supervisorUsername: 'teacher005',
    proposedRecognizedMinutes: 120,
    // Left deliberately incomplete: description, safetyPlan and evidencePlan
    // are empty, so submission is correctly refused. This is the fixture that
    // proves the incomplete-draft path — do not "finish" it.
    targetStatus: 'draft',
  },
];

export type CaseStatusTarget = 'draft' | 'submitted' | 'needsInformation' | 'resolved' | 'rejected';

export interface SeedCase {
  studentUsername: string;
  /** Staff filing on the student's behalf; omit for the student filing their own. */
  filedByUsername?: string;
  type: 'appeal' | 'supplement' | 'manualCorrection';
  subjectType?: string;
  /** When `subjectType` is `'serviceRecord'`, this is a `SeedServiceRecord.key`, resolved to the real id in seed.ts. */
  subjectId?: string;
  title: string;
  description?: string;
  requestedAmountCentiCoins?: number;
  reasonCode: string;
  targetStatus: CaseStatusTarget;
  reviewerUsername?: string;
  decisionReasonCode?: string;
  decisionComment?: string;
}

// Five cases covering every reachable case status. `inReview` was removed as
// unreachable (issue 082) and must not be reintroduced here.
export const cases: SeedCase[] = [
  {
    studentUsername: 'student001', type: 'appeal', subjectType: 'serviceRecord', subjectId: 'student001_rejected',
    title: 'Appeal: library service record rejected without a documented reason',
    description: 'The rejection notice gave no specific reason. The librarian can confirm the hours served.',
    reasonCode: 'DISPUTE_REJECTION', targetStatus: 'resolved', reviewerUsername: 'admin001',
    decisionReasonCode: 'EVIDENCE_ACCEPTED', decisionComment: 'Librarian confirmed attendance. Record reinstated for re-review.',
  },
  {
    studentUsername: 'student002', type: 'appeal', subjectType: 'serviceRecord', subjectId: 'student002_returned',
    title: 'Appeal: hours undercounted on the community record',
    description: 'The session ran three hours, not two — the sign-in sheet shows the start time.',
    reasonCode: 'DISPUTE_HOURS', targetStatus: 'rejected', reviewerUsername: 'admin001',
    decisionReasonCode: 'INSUFFICIENT_EVIDENCE', decisionComment: 'The submitted sheet does not show an end time. Please resubmit the record with complete documentation instead.',
  },
  {
    studentUsername: 'student003', type: 'supplement', subjectType: 'serviceRecord', subjectId: 'student003_approved',
    title: 'Supplement: additional photo evidence for an approved activity',
    description: 'Adding the group photo that was missing when the record was first submitted.',
    reasonCode: 'MISSING_EVIDENCE', targetStatus: 'needsInformation', reviewerUsername: 'admin001',
    decisionReasonCode: 'CLARIFICATION_REQUIRED', decisionComment: 'Please confirm the date the photo was taken.',
  },
  {
    studentUsername: 'student005', filedByUsername: 'teacher005', type: 'manualCorrection', subjectType: 'serviceRecord', subjectId: 'student005_approved',
    title: 'Manual correction: half hour missed on a shift',
    description: 'The shift log shows 3h30m but the record was entered as 3h. Filing on the student\'s behalf.',
    // 30 minutes = 0.5 coin = 50 centi-coins, consistent with the rule.
    requestedAmountCentiCoins: 50, reasonCode: 'LATE_SIGNOFF', targetStatus: 'submitted',
  },
  {
    studentUsername: 'student006', type: 'supplement', subjectType: 'serviceRecord', subjectId: 'student006_submitted',
    title: 'Supplement: adding context for a pending record',
    description: 'Draft only — still gathering the confirmation letter before submitting.',
    reasonCode: 'ADDITIONAL_CONTEXT', targetStatus: 'draft',
  },
];

// ══════════════════════════════════════════════════════════════════════════
// Deterministic generation
// ══════════════════════════════════════════════════════════════════════════

/** Tiny seeded PRNG (mulberry32). Fixed seed ⇒ every run produces identical data, so a re-seed is reproducible and any diff is real. */
function makeRandom(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const pick = <T,>(rnd: () => number, xs: T[]): T => xs[Math.floor(rnd() * xs.length)];

// Names follow the masking convention agreed in
// `Design/english/08-mlps-level1-security-design-guide.md` §4.1: the English
// name keeps the given name and surname initial only, and the Chinese name
// hides its middle character, so both halves carry the same masking strength.
// These are fictional fixtures — the point of masking them is to exercise the
// real format in the UI (CJK plus parentheses in column widths, search, sort),
// not to protect anyone.
const givenNames = ['Alex', 'Jordan', 'Casey', 'Riley', 'Morgan', 'Taylor', 'Quinn', 'Avery', 'Rowan', 'Sage',
  'Emery', 'Finley', 'Harper', 'Kendall', 'Logan', 'Parker', 'Reese', 'Skyler', 'Blake', 'Drew',
  'Ellis', 'Hayden', 'Jamie', 'Kai', 'Lane', 'Marlow', 'Noel', 'Oakley', 'Payton', 'Reagan'];
const maskedChineseNames = ['李*红', '王*明', '张*华', '陈*芳', '刘*强', '杨*丽', '黄*军', '赵*敏', '周*杰', '吴*燕',
  '徐*涛', '孙*静', '马*超', '朱*琳', '胡*斌', '郭*婷', '林*峰', '何*雪', '高*阳', '罗*妍'];
const surnameInitialFor: Record<string, string> = {
  '李': 'L.', '王': 'W.', '张': 'Z.', '陈': 'C.', '刘': 'L.', '杨': 'Y.', '黄': 'H.', '赵': 'Z.',
  '周': 'Z.', '吴': 'W.', '徐': 'X.', '孙': 'S.', '马': 'M.', '朱': 'Z.', '胡': 'H.', '郭': 'G.',
  '林': 'L.', '何': 'H.', '高': 'G.', '罗': 'L.',
};


/**
 * Crockford base32 — the alphabet deliberately omits I, L, O and U so that
 * nothing can be confused with 1 or 0, which matters for a value teachers read
 * off a screen, print on a card and type back in.
 *
 * Eight characters give 32^8 ≈ 1.1e12 — roughly 256x the space of the 8 hex
 * digits this replaced, where a 5,000-student roster carried a ~1-in-344
 * collision chance. Uppercase because the encoding specifies uppercase for
 * output, and because uppercase alphanumerics are easier to tell apart in a
 * random string.
 */
const CROCKFORD_ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

function crockfordBase32(rnd: () => number, length: number): string {
  let out = '';
  for (let i = 0; i < length; i++) {
    out += CROCKFORD_ALPHABET[Math.floor(rnd() * CROCKFORD_ALPHABET.length)];
  }
  return out;
}

const gradeIds = ['grade_6', 'grade_7', 'grade_8', 'grade_9', 'grade_10', 'grade_11', 'grade_12'];

/** Classes available per grade, including the two named ones seed.ts creates. */
function classesForGrade(gradeId: string): string[] {
  const level = gradeId.replace('grade_', '');
  const base = [`class_${level}A`, `class_${level}B`];
  if (gradeId === 'grade_9') base.push('class_9_mars');
  if (gradeId === 'grade_10') base.push('class_10_luna');
  return base;
}

/**
 * The calendar year this student leaves their *stage* — the year they finish
 * grade 8, or the year they finish grade 12. Not the year they leave the school
 * altogether: a junior graduate who returns as a grade 9 entrant does so as a
 * new student record with a new number.
 *
 * This read `2026 + (11 - level) + 1`, which was wrong twice over: a stale
 * literal of exactly the kind issue 100 removed from the schema, and a single
 * six-year span that does not exist here.
 */
function graduationYearFor(gradeId: string): number {
  return enrollmentYearStartFor(gradeId) + (stageExitLevelFor(gradeId) - entryLevelFor(gradeId)) + 1;
}

const STUDENT_COUNT = 56;
/**
 * Students with a login account; the rest are roster-only, which is realistic —
 * most schools issue portal accounts to a subset first — and keeps the
 * paced-login cost of a re-seed down, since nginx rate-limits /auth/login to
 * 10/min per IP.
 *
 * 56 and 35 are seven grades of eight, five of them with logins, so every grade
 * receives each of `COIN_TARGETS_BY_GRADE`'s five slots exactly once. With the
 * previous 50/30 against seven grades the split was 4-or-5 per grade and some
 * grades silently lost a slot — including the no-service one that the
 * empty-state screens depend on.
 */
const STUDENTS_WITH_LOGIN = 35;

export interface GeneratedStudent extends SeedStudent {
  /**
   * How the rest of this file points at a student — `student001`, `student002`.
   *
   * A fixture handle, never a login. Every `studentUsername`/`reviewerUsername`
   * field in this file holds one of these, because a hand-written record has to
   * name a student in a way a human can read and a generator has to be able to
   * resolve it; the actual login is a random number that means nothing to
   * either. `seed.ts` maps a handle to the real account at the point of login.
   */
  ref?: string;
  /**
   * The login the student actually types — 8 random digits.
   *
   * `Design/chinese/10-StudentActivationFlow.md` §3 (and its English twin): an imported student's account is
   * created with an "8 位随机数字用户名", and §4.1's slip shows `48215577`.
   * `StudentUsernameGenerator` is the real implementation and pins two
   * properties this has to match — **8 digits with no leading zero** (drawn
   * from 10000000-99999999, so a spreadsheet cannot eat the zero) and
   * **random rather than sequential**, because a sequential login leaks
   * enrolment order and cohort size, which §2.1 rejected outright.
   *
   * This fixture used `student001`…`student030` as the real usernames, which
   * breaks both properties — it is sequential by construction, and it is the
   * one thing on the credential slip the student has never seen before, so a
   * demo of the activation flow showed a login no real import would produce.
   *
   * Staff are unaffected: §2 has an administrator choose those by hand, and
   * `StudentUsernameGenerator`'s own Javadoc calls `user.username` "a VARCHAR
   * shared with teacher001-style staff names".
   */
  username?: string;
  /** Coins the generator will compose approved records to reach. 1 coin = 1 hour. Never written to the ledger directly. */
  targetCoins: number;
}

/**
 * Draws the 8-digit logins, mirroring `StudentUsernameGenerator`'s range and
 * uniqueness check but from this file's fixed PRNG, so a re-seed is still
 * byte-identical rather than different every run.
 */
function drawLoginUsername(rnd: () => number, taken: Set<string>): string {
  const LOWEST = 10_000_000;
  const HIGHEST = 99_999_999;
  for (let attempt = 0; attempt < 50; attempt += 1) {
    const candidate = String(LOWEST + Math.floor(rnd() * (HIGHEST - LOWEST + 1)));
    if (!taken.has(candidate)) {
      taken.add(candidate);
      return candidate;
    }
  }
  throw new Error('Could not allocate a free 8-digit student username after 50 attempts.');
}

function buildStudents(): GeneratedStudent[] {
  const rnd = makeRandom(20260916);
  const out: GeneratedStudent[] = [];
  const usedNumbers = new Set<string>();
  const usedLogins = new Set<string>();

  for (let i = 0; i < STUDENT_COUNT; i++) {
    const gradeId = gradeIds[i % gradeIds.length];
    const classId = pick(rnd, classesForGrade(gradeId));
    const cn = maskedChineseNames[i % maskedChineseNames.length];
    const given = givenNames[i % givenNames.length];
    const displayName = `${given} ${surnameInitialFor[cn[0]]}(${cn})`;

    // School-issued stable identifier, per the design doc §4.2 — opaque,
    // unique, constant for a student, and generated from the fixed PRNG so it
    // is stable across runs of this file.
    let studentNumber = '';
    do {
      studentNumber = crockfordBase32(rnd, 8);
    } while (usedNumbers.has(studentNumber));
    usedNumbers.add(studentNumber);

    const hasLogin = i < STUDENTS_WITH_LOGIN;

    out.push({
      studentNumber, displayName, gradeId, classId,
      graduationYear: graduationYearFor(gradeId),
      // Joined a few days into the September their cohort entered, instead of a
      // free-running 300-1169 days back that contradicted the enrolment year in
      // its own row (issue 103 §2.2).
      joinedAtOffsetDays: academicYearWindow(enrollmentYearStartFor(gradeId)).first + Math.floor(rnd() * 5),
      enrollmentAcademicYearId: enrollmentAcademicYearIdFor(gradeId),
      ref: hasLogin ? `student${String(i + 1).padStart(3, '0')}` : undefined,
      username: hasLogin ? drawLoginUsername(rnd, usedLogins) : undefined,
      targetCoins: hasLogin ? targetCoinsFor(gradeId, gradeSlot(i), rnd) : 0,
    });
  }
  return out;
}

export const generatedStudents: GeneratedStudent[] = buildStudents();

export const students: SeedStudent[] = generatedStudents.map(
  ({ studentNumber, displayName, gradeId, classId, graduationYear, joinedAtOffsetDays, enrollmentAcademicYearId }) =>
    ({ studentNumber, displayName, gradeId, classId, graduationYear, joinedAtOffsetDays, enrollmentAcademicYearId }));

export const users: SeedUser[] = [
  ...staffUsers,
  ...generatedStudents
    .filter(s => s.ref)
    .map(s => ({
      username: s.username!,
      ref: s.ref!,
      displayName: s.displayName,
      // Keyed off the real login, not the handle. Readability was the first
      // instinct here, and it was wrong: the admin Users list shows the email
      // and not the username, so a handle-derived address put `student001@…`
      // back on screen and left an administrator concluding the logins were
      // still sequential — undoing, visually, the whole point of issue 105.
      // A school mailbox named for the student number is also what schools
      // actually issue.
      email: `${s.username}@example-township-school.edu`,
      roleCodes: ['student'],
      linkedStudentNumber: s.studentNumber,
    })),
];

// ── Service record generation ─────────────────────────────────────────────
// Each login student gets approved records summing to exactly `targetCoins`
// hours, plus (for a subset) one extra record in a non-approved status so the
// review queue and the student's own history have something in every state.

const selfActivities = [
  { categoryId: 'cat_community', organizationName: 'Example Community Service Centre', location: 'Example Community Service Centre', description: 'Helped at the community weekend service desk.', supervisorName: 'Director Wu', supervisorContact: 'community@example.org' },
  { categoryId: 'cat_welfare', organizationName: 'Example Welfare Home', location: 'Example Welfare Home', description: 'Accompanied residents during afternoon activities.', supervisorName: 'Director Chen', supervisorContact: 'welfare@example.org' },
  { categoryId: 'cat_environment', organizationName: 'Example Environmental Association', location: 'Example District Central Park', description: 'Took part in a neighbourhood cleanup and litter survey.', supervisorName: 'President Li', supervisorContact: 'enviro@example.org' },
  { categoryId: 'cat_education', organizationName: 'Example Community Library', location: 'Example Community Library', description: 'Read with younger children at the library reading corner.', supervisorName: 'Curator Zhao', supervisorContact: 'library@example.org' },
  { categoryId: 'cat_campus', organizationName: 'Example Township Middle School', location: 'School Library', description: 'Assisted with shelving and labelling in the school library.', supervisorName: 'Librarian Wang', supervisorContact: 'library@example-township-school.edu' },
];

/**
 * Plausible session lengths, by the kind of day.
 *
 * A weekend session is a morning or an afternoon; a school-day one happens
 * after the last lesson and is necessarily short. Splitting them is what makes
 * the weekday/weekend mix believable rather than merely weighted — before this
 * the fixture put 55 records on a Thursday and 30 on a Saturday, every one of
 * them the same shape.
 */
const SESSION_HOURS = {
  weekend: [2, 2.5, 3, 3.5, 4, 4.5, 5, 6],
  weekday: [1, 1.5, 2, 2.5],
};

/** Start times to match. Half hours are deliberate: `daysFromNow` renders 15.5 as 15:30, and the fractional display path needs exercising. */
const START_HOURS = {
  weekend: [9, 9.5, 13, 14],
  weekday: [15.5, 16],
};

/**
 * Share of a student's hours that falls in the current school year; the rest is
 * history.
 *
 * Roughly the share of the year that has elapsed (17 days of 365 is 4.6%), with
 * a little on top because term-start activity is real. Much above this and the
 * month in progress carries more hours than any *complete* month of last year,
 * which is the kind of detail that makes a reader stop trusting the rest.
 */
const CURRENT_YEAR_SHARE = 0.06;

interface DaySlot {
  day: number;
  weekend: boolean;
}

/** Every day in `[first, last]` the student has not already used, tagged by weekend. */
function availableDays(first: number, last: number, used: Set<number>): DaySlot[] {
  const out: DaySlot[] = [];
  for (let day = first; day <= last; day += 1) {
    if (!used.has(day)) out.push({ day, weekend: isWeekend(day) });
  }
  return out;
}

/** Deterministic Fisher-Yates, so day choice varies per student without the file losing reproducibility. */
function shuffled<T>(rnd: () => number, xs: T[]): T[] {
  const out = [...xs];
  for (let i = out.length - 1; i > 0; i -= 1) {
    const j = Math.floor(rnd() * (i + 1));
    [out[i], out[j]] = [out[j], out[i]];
  }
  return out;
}

interface PlacedSession {
  day: number;
  startHour: number;
  hours: number;
}

/**
 * Spends exactly `totalHours` across distinct days drawn from `days`.
 *
 * **One session per student per day, always.** That single rule is what makes
 * the data readable as a school's: the previous generator stacked up to three
 * records a day onto a student and produced 99 hours inside 17 days, which is
 * self-consistent and unbelievable. Days are consumed, never revisited, so an
 * overlap is unrepresentable here rather than merely checked for afterwards.
 *
 * Weekends are preferred roughly two to one, and the length menu differs by day
 * kind, so the shape of the week falls out of the placement instead of being
 * imposed on it.
 */
function placeSessions(totalHours: number, days: DaySlot[], rnd: () => number): PlacedSession[] {
  const weekend = shuffled(rnd, days.filter(d => d.weekend));
  const weekday = shuffled(rnd, days.filter(d => !d.weekend));
  const out: PlacedSession[] = [];
  let left = toHalfHours(totalHours);

  while (left > 0 && (weekend.length > 0 || weekday.length > 0)) {
    const useWeekend = weekend.length > 0 && (weekday.length === 0 || rnd() < 0.65);
    const slot = (useWeekend ? weekend : weekday).shift()!;
    const menu = useWeekend ? SESSION_HOURS.weekend : SESSION_HOURS.weekday;

    let hours = Math.min(pick(rnd, menu), left);
    // Never strand a remainder too small to stand as its own session; fold it
    // into this one instead, which keeps the total exact.
    if (left - hours > 0 && left - hours < 1) hours = left;

    out.push({ day: slot.day, startHour: pick(rnd, useWeekend ? START_HOURS.weekend : START_HOURS.weekday), hours });
    left = toHalfHours(left - hours);
  }
  return out;
}

function buildServiceRecords(taken: SeedServiceRecord[]): SeedServiceRecord[] {
  const rnd = makeRandom(776611);
  const out: SeedServiceRecord[] = [];

  // Days each student already spends on a hand-pinned or project record. Those
  // are placed first, so the generator works around them rather than colliding
  // with them — submission hard-blocks an overlap for the whole student
  // whatever the other record's status (issues 064-074).
  const usedDays = new Map<string, Set<number>>();
  for (const r of taken) {
    if (!usedDays.has(r.studentUsername)) usedDays.set(r.studentUsername, new Set());
    usedDays.get(r.studentUsername)!.add(r.serviceDateOffsetDays);
  }

  const priorYear = academicYearWindow(CURRENT_ACADEMIC_YEAR_START - 1);
  const currentYear = academicYearWindow(CURRENT_ACADEMIC_YEAR_START);

  // Hours a student already holds from a hand-pinned or project record.
  // `targetCoins` is the student's *total* approved hours, so those count
  // toward it rather than stacking on top — otherwise the three students the
  // narrative fixtures give approved records to quietly exceed their slot, and
  // the slot is the whole reason the number was chosen.
  const alreadyApproved = new Map<string, number>();
  for (const r of taken) {
    if (r.targetStatus !== 'approved') continue;
    alreadyApproved.set(r.studentUsername,
      (alreadyApproved.get(r.studentUsername) ?? 0) + (r.endHour - r.startHour));
  }

  for (const s of generatedStudents) {
    if (!s.ref) continue;
    const outstanding = toHalfHours(s.targetCoins - (alreadyApproved.get(s.ref) ?? 0));
    if (outstanding <= 0) continue;
    const used = usedDays.get(s.ref) ?? new Set<number>();
    const reviewer = reviewerForGrade[s.gradeId];

    // A student cannot have served before they joined the school. For Grade 6
    // that is this September, which is the whole reason their targets are small.
    const joined = academicYearWindow(enrollmentYearStartFor(s.gradeId)).first;
    const priorDays = availableDays(Math.max(joined, priorYear.first), priorYear.last, used);
    const currentDays = availableDays(Math.max(joined, currentYear.first), currentYear.last, used);

    // Recent activity as well as history, so this year's progress is non-zero
    // and the monthly trend has a point for the month we are in.
    // The current year is only as wide as the term is old — 17 days in
    // mid-September — so a flat share of a large total puts an implausible
    // amount of service into a fortnight and, past a point, simply will not fit
    // one-session-per-day. Cap it at roughly a session every other day, which
    // is the ceiling a keen student could actually sustain.
    const currentCeiling = toHalfHours(currentDays.length / 2);
    const currentHours = priorDays.length === 0
      ? outstanding
      : Math.min(outstanding, currentCeiling, Math.max(1, toHalfHours(outstanding * CURRENT_YEAR_SHARE)));
    const priorHours = toHalfHours(outstanding - currentHours);

    const placed = [
      ...placeSessions(priorHours, priorDays, rnd),
      ...placeSessions(currentHours, currentDays, rnd),
    ];

    for (const session of placed) {
      used.add(session.day);
      const a = pick(rnd, selfActivities);
      out.push({
        studentUsername: s.ref,
        sourceType: 'SELF',
        organizationName: a.organizationName,
        serviceCategoryId: a.categoryId,
        serviceDateOffsetDays: session.day,
        startHour: session.startHour,
        endHour: session.startHour + session.hours,
        location: a.location,
        description: a.description,
        supervisorKind: 'EXTERNAL_MANUAL',
        supervisorName: a.supervisorName,
        supervisorContact: a.supervisorContact,
        targetStatus: 'approved',
        reviewerUsername: reviewer,
        reasonCode: 'DOCUMENTATION_COMPLETE',
        comment: 'Documentation complete, hours verified.',
      });
    }
    usedDays.set(s.ref, used);
  }
  return out;
}

/** Records in non-approved states, attached to named students so the cases above can reference them by key. These are hand-pinned rather than generated, because each one is referenced by a specific case or exists to fill a specific queue. */
const narrativeRecords: SeedServiceRecord[] = [
  {
    studentUsername: 'student001', sourceType: 'SELF', organizationName: 'Example Township Middle School',
    serviceCategoryId: 'cat_campus', serviceDateOffsetDays: -14, startHour: 14, endHour: 16, location: 'School Library',
    description: 'Library shelving session — evidence disputed.',
    supervisorKind: 'EXTERNAL_MANUAL', supervisorName: 'Librarian Wang', supervisorContact: 'library@example-township-school.edu',
    targetStatus: 'rejected', reviewerUsername: 'teacher001', reasonCode: 'INSUFFICIENT_EVIDENCE', comment: 'Submitted photo does not show the date.',
    key: 'student001_rejected',
  },
  {
    studentUsername: 'student002', sourceType: 'SELF', organizationName: 'Example Community Service Centre',
    serviceCategoryId: 'cat_community', serviceDateOffsetDays: -11, startHour: 9, endHour: 11, location: 'Example Community Service Centre',
    description: 'Community help desk — hours queried by the student.',
    supervisorKind: 'EXTERNAL_MANUAL', supervisorName: 'Director Wu', supervisorContact: 'community@example.org',
    targetStatus: 'returned', reviewerUsername: 'teacher002', reasonCode: 'INSUFFICIENT_EVIDENCE', comment: 'Please attach the sign-in sheet showing start and end times.',
    key: 'student002_returned',
  },
  {
    studentUsername: 'student003', sourceType: 'SELF', organizationName: 'Example Welfare Home',
    serviceCategoryId: 'cat_welfare', serviceDateOffsetDays: -9, startHour: 10, endHour: 12, location: 'Example Welfare Home',
    description: 'Afternoon visit and craft session with residents.',
    supervisorKind: 'EXTERNAL_MANUAL', supervisorName: 'Director Chen', supervisorContact: 'welfare@example.org',
    targetStatus: 'approved', reviewerUsername: 'teacher003', reasonCode: 'DOCUMENTATION_COMPLETE', comment: 'Confirmation letter received.',
    key: 'student003_approved',
  },
  // Issue 093 §4: the only PROJECT-sourced records in the dataset. Without at
  // least one, `top_projects` sums approved records joined on `project_id` over
  // an empty set and every row of the Top Projects report reads 0h, while the
  // pie chart beside it (which counts enrollments) looks healthy — so the
  // report appears broken when it is faithfully reporting nothing.
  // Each student here is enrolled in the named project by construction; see
  // seed.ts Phase 3.5. Project, date window, category, organisation and
  // location are all taken from the project itself, because a PROJECT-sourced
  // record inherits them rather than letting the student type their own.
  {
    studentUsername: 'student003', sourceType: 'PROJECT', projectTitle: 'School Library Organisation',
    organizationName: 'Example Township Middle School',
    serviceCategoryId: 'cat_campus', serviceDateOffsetDays: -45, startHour: 14, endHour: 16, location: 'School Library',
    description: 'Reshelved returned books and repaired damaged covers during the library reorganisation.',
    supervisorKind: 'EXTERNAL_MANUAL', supervisorName: 'Librarian Zhou', supervisorContact: 'library@example-township-school.edu',
    targetStatus: 'approved', reviewerUsername: 'teacher003', reasonCode: 'DOCUMENTATION_COMPLETE', comment: 'Supervisor confirmed the shift.',
    key: 'student003_project_approved',
  },
  {
    studentUsername: 'student002', sourceType: 'PROJECT', projectTitle: 'Community Reading Companions',
    organizationName: 'Example Community Library',
    serviceCategoryId: 'cat_education', serviceDateOffsetDays: -50, startHour: 9, endHour: 11, location: 'Example Community Library',
    description: 'Read aloud with younger children in the community library reading corner.',
    supervisorKind: 'EXTERNAL_MANUAL', supervisorName: 'Coordinator Xie', supervisorContact: 'reading@example.org',
    targetStatus: 'approved', reviewerUsername: 'teacher005', reasonCode: 'DOCUMENTATION_COMPLETE', comment: 'Attendance sheet received.',
    key: 'student002_project_approved',
  },
  {
    studentUsername: 'student005', sourceType: 'PROJECT', projectTitle: 'Neighbourhood Digital Help Desk',
    organizationName: 'Example Community Service Centre',
    serviceCategoryId: 'cat_community', serviceDateOffsetDays: -65, startHour: 13, endHour: 15, location: 'Example Community Service Centre',
    description: 'Helped older residents set up health-code and messaging apps on their phones.',
    supervisorKind: 'EXTERNAL_MANUAL', supervisorName: 'Director Wu', supervisorContact: 'community@example.org',
    targetStatus: 'approved', reviewerUsername: 'teacher002', reasonCode: 'DOCUMENTATION_COMPLETE', comment: 'Help-desk log confirms the session.',
    key: 'student005_project_approved',
  },
  {
    studentUsername: 'student005', sourceType: 'SELF', organizationName: 'Example Environmental Association',
    serviceCategoryId: 'cat_environment', serviceDateOffsetDays: -7, startHour: 9, endHour: 12, location: 'Example District Central Park',
    description: 'Park cleanup shift — sign-off arrived late.',
    supervisorKind: 'EXTERNAL_MANUAL', supervisorName: 'President Li', supervisorContact: 'enviro@example.org',
    targetStatus: 'approved', reviewerUsername: 'teacher005', reasonCode: 'DOCUMENTATION_COMPLETE', comment: 'Approved on the organiser\'s confirmation.',
    key: 'student005_approved',
  },
  {
    studentUsername: 'student006', sourceType: 'SELF', organizationName: 'Example Community Library',
    serviceCategoryId: 'cat_education', serviceDateOffsetDays: -5, startHour: 14, endHour: 16, location: 'Example Community Library',
    description: 'Reading corner session, awaiting review.',
    supervisorKind: 'EXTERNAL_MANUAL', supervisorName: 'Curator Zhao', supervisorContact: 'library@example.org',
    targetStatus: 'submitted',
    key: 'student006_submitted',
  },
  // Pending-review queue filler, spread across grades so each teacher sees
  // something in their own scope and the admin sees all of it.
  {
    studentUsername: 'student008', sourceType: 'SELF', organizationName: 'Example Community Service Centre',
    serviceCategoryId: 'cat_community', serviceDateOffsetDays: -6, startHour: 13, endHour: 15.5, location: 'Example Community Service Centre',
    description: 'Weekend service desk shift.',
    supervisorKind: 'EXTERNAL_MANUAL', supervisorName: 'Director Wu', supervisorContact: 'community@example.org',
    targetStatus: 'submitted',
  },
  {
    studentUsername: 'student012', sourceType: 'SELF', organizationName: 'Example Environmental Association',
    serviceCategoryId: 'cat_environment', serviceDateOffsetDays: -4, startHour: 9, endHour: 11, location: 'Example District Central Park',
    description: 'Litter survey along the riverside path.',
    supervisorKind: 'EXTERNAL_MANUAL', supervisorName: 'President Li', supervisorContact: 'enviro@example.org',
    targetStatus: 'submitted',
  },
  {
    studentUsername: 'student015', sourceType: 'SELF', organizationName: 'Example Welfare Home',
    serviceCategoryId: 'cat_welfare', serviceDateOffsetDays: -3, startHour: 10, endHour: 13, location: 'Example Welfare Home',
    description: 'Assisted with the residents\' music afternoon.',
    supervisorKind: 'EXTERNAL_MANUAL', supervisorName: 'Director Chen', supervisorContact: 'welfare@example.org',
    targetStatus: 'underReview', reviewerUsername: 'teacher005', reasonCode: 'NEEDS_SECOND_OPINION', comment: 'Escalated for a second look at the supervisor contact.',
  },
  {
    studentUsername: 'student018', sourceType: 'SELF', organizationName: 'Example Community Library',
    serviceCategoryId: 'cat_education', serviceDateOffsetDays: -2, startHour: 15, endHour: 16.5, location: 'Example Community Library',
    description: 'Shelving and reader assistance.',
    supervisorKind: 'EXTERNAL_MANUAL', supervisorName: 'Curator Zhao', supervisorContact: 'library@example.org',
    targetStatus: 'draft',
  },
  {
    studentUsername: 'student022', sourceType: 'SELF', organizationName: 'Example Township Middle School',
    serviceCategoryId: 'cat_campus', serviceDateOffsetDays: -2, startHour: 12, endHour: 13, location: 'School Courtyard',
    description: 'Recycling station lunchtime shift — not yet submitted.',
    supervisorKind: 'EXTERNAL_MANUAL', supervisorName: 'Teacher Wei Chen', supervisorContact: 'wei.chen@example-township-school.edu',
    targetStatus: 'draft',
  },
];


/**
 * Give every project whose registration window has already opened a set of
 * CONFIRMED registrants drawn from its own eligible grades.
 *
 * This is not cosmetic. `ProjectServiceImpl.syncTimeBasedStatus` sends a
 * project whose registration window has *closed* with **zero** confirmed
 * registrants straight to ARCHIVED instead of advancing it to
 * closed/inProgress/completed — and it does so silently, with no error, so a
 * seed run would "succeed" while five of the ten projects sat in a status
 * nobody asked for. Registrants are derived rather than hardcoded because
 * student numbers are generated hashes that change if the roster changes.
 */
function assignRegistrants(list: SeedProject[]): SeedProject[] {
  const rnd = makeRandom(31337);
  let rotation = 0;
  return list.map(p => {
    // Registration hasn't opened yet — enrolling anyone would be nonsense,
    // and the auto-archive rule cannot fire while the window is still ahead.
    if (p.registrationOpenOffsetDays >= 0) return p;

    const eligible = generatedStudents.filter(s =>
      p.eligibleGradeIds.includes(s.gradeId)
      // …and who had actually joined the school by the time registration
      // opened. Without this a Grade 6 student — whose cohort arrived this
      // September — is enrolled in a project that ran last spring, and the
      // project record generated from that enrolment is dated before the
      // student existed on the roster.
      && academicYearWindow(enrollmentYearStartFor(s.gradeId)).first <= p.registrationOpenOffsetDays);
    if (eligible.length === 0) {
      throw new Error(`Project "${p.title}" has no enrolled students in its eligible grades — it would auto-archive.`);
    }

    // Students with a login come first: only they can file a service record, so
    // a project whose registrants are all roster-only shows participants and no
    // hours on the Top Projects report however correct the rest of the data is.
    const ordered = [...eligible.filter(s => s.username), ...eligible.filter(s => !s.username)];
    const wanted = Math.min(ordered.length, p.capacity, 2 + Math.floor(rnd() * 4));
    const picked: string[] = [];
    for (let i = 0; i < wanted; i++) {
      picked.push(ordered[(rotation + i) % ordered.length].studentNumber);
    }
    rotation += wanted;
    return { ...p, registrantStudentNumbers: picked };
  });
}

/**
 * Enrols the students named by a hand-pinned `PROJECT` record in the project
 * that record names.
 *
 * A project-sourced record is only creatable by a student holding a CONFIRMED
 * enrolment (`NOT_CONFIRMED` at submit), and registrants are *derived* while
 * those records name a student by hand — so the two drift apart silently.
 * `seed.ts` used to reconcile them at run time by mutating this module's
 * exported `projects`, which meant the fixture as written was never actually
 * consistent and no offline check could see the difference. Doing it here makes
 * the exported data true on its own terms.
 */
function withPinnedRegistrants(list: SeedProject[]): SeedProject[] {
  const numberByRef = new Map(
    generatedStudents.filter(s => s.ref).map(s => [s.ref!, s.studentNumber] as const));
  return list.map(p => {
    const pinned = narrativeRecords
      .filter(r => r.sourceType === 'PROJECT' && r.projectTitle === p.title)
      .map(r => numberByRef.get(r.studentUsername))
      .filter((n): n is string => !!n);
    if (pinned.length === 0) return p;
    const merged = [...(p.registrantStudentNumbers ?? [])];
    for (const n of pinned) if (!merged.includes(n)) merged.push(n);
    return { ...p, registrantStudentNumbers: merged };
  });
}

export const projects: SeedProject[] = withPinnedRegistrants(assignRegistrants(rawProjects));

const gradeByRef = new Map(
  generatedStudents.filter(s => s.ref).map(s => [s.ref!, s.gradeId] as const));

/**
 * Force every review onto the teacher who actually holds scope over that
 * student's grade, overriding whatever a fixture wrote by hand.
 *
 * Hand-picking a reviewer is a trap: students are generated, so their grade
 * comes from the builder, and a fixture author guessing wrong gets
 * `404 Service record not found` — B1.7's deliberate no-existence-leak — which
 * reads like missing data rather than the permission error it is. Deriving the
 * reviewer makes that class of mistake unrepresentable.
 */
function withScopedReviewer(r: SeedServiceRecord): SeedServiceRecord {
  if (r.targetStatus === 'draft' || r.targetStatus === 'submitted') return r;
  const gradeId = gradeByRef.get(r.studentUsername);
  if (!gradeId) throw new Error(`Service record references unknown student username "${r.studentUsername}".`);
  return { ...r, reviewerUsername: reviewerForGrade[gradeId] };
}


/**
 * Approved service records logged *against the project a student enrolled in*.
 *
 * `GET /reports/top-projects` picks projects that have confirmed enrollments,
 * ranks them by participant count, and sums the approved records whose
 * `project_id` is that project. Those were disjoint sets in this fixture: the
 * enrollments existed, and the only project-linked records belonged to projects
 * nobody had enrolled in — so every row showed participants beside zero hours,
 * which reads as a broken aggregate rather than as a fixture that never joined
 * its two halves up.
 *
 * One session per enrolled student, on the project they actually joined, dated
 * inside that project's own window. Inside the *project's* window, not merely
 * inside an academic year: hours logged before a project started or after it
 * closed are not something a real submission would produce, and they read as
 * broken data on the project's own page.
 */
function buildProjectRecords(occupied: SeedServiceRecord[]): SeedServiceRecord[] {
  const rnd = makeRandom(20260918);
  const refByNumber = new Map(
    generatedStudents.filter(s => s.ref).map(s => [s.studentNumber, s.ref!] as const));
  const studentByRef = new Map(
    generatedStudents.filter(s => s.ref).map(s => [s.ref!, s] as const));

  const usedDays = new Map<string, Set<number>>();
  for (const r of occupied) {
    if (!usedDays.has(r.studentUsername)) usedDays.set(r.studentUsername, new Set());
    usedDays.get(r.studentUsername)!.add(r.serviceDateOffsetDays);
  }

  const out: SeedServiceRecord[] = [];
  for (const project of projects) {
    // Only projects whose work has actually happened. A record against a
    // project that has not started is rejected on submission, and one against a
    // project still running is fine but has no window to sit in yet here.
    if (!project.registrantStudentNumbers || project.startOffsetDays >= 0) continue;
    const first = project.startOffsetDays;
    const last = Math.min(-1, project.endOffsetDays);
    if (last < first) continue;

    for (const number of project.registrantStudentNumbers) {
      const ref = refByNumber.get(number);
      if (!ref) continue;
      // The deliberate no-service students stay at zero. They are enrolled and
      // simply never logged anything, which is both realistic and what keeps
      // the empty-state screens populated — crediting them here would quietly
      // take the empty state away.
      if ((studentByRef.get(ref)?.targetCoins ?? 0) === 0) continue;
      const used = usedDays.get(ref) ?? new Set<number>();
      usedDays.set(ref, used);

      const days = shuffled(rnd, availableDays(first, last, used));
      if (days.length === 0) continue;
      // Prefer a weekend day inside the project window, same as the rest of the
      // dataset, but take a weekday rather than drop the record if the window
      // has no weekend in it.
      const slot = days.find(d => d.weekend) ?? days[0];
      const hours = pick(rnd, slot.weekend ? SESSION_HOURS.weekend : SESSION_HOURS.weekday);
      const startHour = pick(rnd, slot.weekend ? START_HOURS.weekend : START_HOURS.weekday);
      used.add(slot.day);

      out.push({
        studentUsername: ref,
        sourceType: 'PROJECT',
        projectTitle: project.title,
        serviceCategoryId: project.categoryId,
        serviceDateOffsetDays: slot.day,
        startHour,
        endHour: startHour + hours,
        targetStatus: 'approved',
        reviewerUsername: reviewerForGrade[gradeByRef.get(ref) ?? 'grade_6'],
        reasonCode: 'DOCUMENTATION_COMPLETE',
        comment: 'Project attendance confirmed by the organiser.',
      });
    }
  }
  return out;
}

// Hand-pinned records keep the dates they were written with. They no longer
// need moving: the offsets that put them outside the single seeded year (-45,
// -50, -65) sit correctly inside the *prior* year now that both are populated,
// which is the arrangement they were written for in the first place.
const pinnedRecords: SeedServiceRecord[] = narrativeRecords;

const projectRecords: SeedServiceRecord[] = buildProjectRecords(pinnedRecords);

/**
 * Chronological order is load-bearing, not tidiness.
 *
 * `seed.ts` creates, submits and decides each record in one pass in this array's
 * order (preserving each student's own order through `interleaveByStudent`), and
 * the backend stamps `account_transaction.balance_after` as it credits. So the
 * ledger's running balance is only correct read top-to-bottom if a student's
 * records are *approved* in date order.
 *
 * They were not: the generator emits a student's prior-year sessions before
 * their current-year ones, the pinned and project records were appended after
 * both, and day choice within a year is shuffled. The account page then showed a
 * balance column that jumped around against its own dates — +4 landing on 76.5
 * one row and 196 the next. It only became visible once records spanned two
 * years and the timestamps were backdated to match; with everything created
 * today in one year, insertion order and date order were the same thing.
 */
export const serviceRecords: SeedServiceRecord[] =
  [...buildServiceRecords([...pinnedRecords, ...projectRecords]), ...pinnedRecords, ...projectRecords]
    .map(withScopedReviewer)
    .sort((a, b) => a.serviceDateOffsetDays - b.serviceDateOffsetDays || a.startHour - b.startHour);

/** Sum of approved hours per student — seed.ts asserts the live balance matches this after seeding, so a mismatch fails the run instead of leaving a baffling number on screen. */
export const expectedCoinsByUsername: Record<string, number> = serviceRecords
  .filter(r => r.targetStatus === 'approved')
  .reduce((acc, r) => {
    acc[r.studentUsername] = (acc[r.studentUsername] ?? 0) + (r.endHour - r.startHour);
    return acc;
  }, {} as Record<string, number>);
