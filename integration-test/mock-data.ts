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
  username: string;
  displayName: string;
  email?: string;
  roleCodes: string[];
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
  { username: 'admin001', displayName: 'Teacher Wei Chen', email: 'wei.chen@example-township-school.edu', roleCodes: ['admin'] },
  // `sysadmin` holds the same permission grants as `admin` after V21's merge,
  // but a few surfaces are gated by role code rather than permission (school
  // info editing, for one), so this account exists to make that distinction
  // exercisable against real data rather than only provable in a test.
  { username: 'sysadmin001', displayName: 'System Administrator', email: 'sysadmin@example-township-school.edu', roleCodes: ['sysadmin'] },
  { username: 'teacher001', displayName: 'Alex L.', email: 'teacher001@example-township-school.edu', roleCodes: ['teacher'], scopeGrades: ['grade_6'] },
  { username: 'teacher002', displayName: 'Jordan W.', email: 'teacher002@example-township-school.edu', roleCodes: ['teacher'], scopeGrades: ['grade_7'] },
  { username: 'teacher003', displayName: 'Casey Z.', email: 'teacher003@example-township-school.edu', roleCodes: ['teacher'], scopeGrades: ['grade_8'] },
  { username: 'teacher004', displayName: 'Riley H.', email: 'teacher004@example-township-school.edu', roleCodes: ['teacher'], scopeGrades: ['grade_9'] },
  { username: 'teacher005', displayName: 'Morgan Y.', email: 'teacher005@example-township-school.edu', roleCodes: ['teacher'], scopeGrades: ['grade_10', 'grade_11'] },
];

/** Which teacher may review a given grade — mirrors `scopeGrades` above. Reviews are routed through this so every decision comes from someone who actually holds scope; routing one to the wrong teacher would 404, not 403. */
export const reviewerForGrade: Record<string, string> = {
  grade_6: 'teacher001',
  grade_7: 'teacher002',
  grade_8: 'teacher003',
  grade_9: 'teacher004',
  grade_10: 'teacher005',
  grade_11: 'teacher005',
};

export interface SeedStudent {
  studentNumber: string;
  displayName: string;
  gradeId: string;
  classId: string;
  graduationYear: number;
  joinedAtOffsetDays: number;
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

const gradeIds = ['grade_6', 'grade_7', 'grade_8', 'grade_9', 'grade_10', 'grade_11'];

/** Classes available per grade, including the two named ones seed.ts creates. */
function classesForGrade(gradeId: string): string[] {
  const level = gradeId.replace('grade_', '');
  const base = [`class_${level}A`, `class_${level}B`];
  if (gradeId === 'grade_9') base.push('class_9_mars');
  if (gradeId === 'grade_10') base.push('class_10_luna');
  return base;
}

/** Graduation year for a grade, given a 6-11 secondary span ending at grade 11. */
function graduationYearFor(gradeId: string): number {
  const level = Number(gradeId.replace('grade_', ''));
  return 2026 + (11 - level) + 1;
}

const STUDENT_COUNT = 50;
/** Students with a login account. The rest are roster-only, which is realistic — most schools issue portal accounts to a subset first — and keeps the paced-login cost of a re-seed down, since nginx rate-limits /auth/login to 10/min per IP. */
const STUDENTS_WITH_LOGIN = 30;

export interface GeneratedStudent extends SeedStudent {
  username?: string;
  /** Coins the generator will compose approved records to reach. 1 coin = 1 hour. Never written to the ledger directly. */
  targetCoins: number;
}

function buildStudents(): GeneratedStudent[] {
  const rnd = makeRandom(20260916);
  const out: GeneratedStudent[] = [];
  const usedNumbers = new Set<string>();

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

    // Balance distribution: realistic rather than uniform — most students have
    // modest hours, a few have a lot. Five of the login students deliberately
    // have zero so empty-state screens are covered.
    let targetCoins = 0;
    if (hasLogin) {
      if (i < 5) targetCoins = 0;
      else if (i < 18) targetCoins = 1 + Math.floor(rnd() * 14);      //  1-14
      else if (i < 26) targetCoins = 15 + Math.floor(rnd() * 25);     // 15-39
      else targetCoins = 40 + Math.floor(rnd() * 61);                 // 40-100
    }

    out.push({
      studentNumber, displayName, gradeId, classId,
      graduationYear: graduationYearFor(gradeId),
      joinedAtOffsetDays: -(300 + Math.floor(rnd() * 900)),
      username: hasLogin ? `student${String(i + 1).padStart(3, '0')}` : undefined,
      targetCoins,
    });
  }
  return out;
}

export const generatedStudents: GeneratedStudent[] = buildStudents();

export const students: SeedStudent[] = generatedStudents.map(
  ({ studentNumber, displayName, gradeId, classId, graduationYear, joinedAtOffsetDays }) =>
    ({ studentNumber, displayName, gradeId, classId, graduationYear, joinedAtOffsetDays }));

export const users: SeedUser[] = [
  ...staffUsers,
  ...generatedStudents
    .filter(s => s.username)
    .map(s => ({
      username: s.username!,
      displayName: s.displayName,
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

/** Split a coin total into per-record hour chunks of 1-4 hours (whole/half hours only, so rounding is always a no-op). */
function splitIntoSessions(totalCoins: number, rnd: () => number): number[] {
  const out: number[] = [];
  let left = totalCoins;
  while (left > 0) {
    // 1 to 4 hours, capped at what remains.
    let chunk = Math.min(left, 1 + Math.floor(rnd() * 4));
    // Occasionally use a half hour so the .5 display path is exercised.
    if (left - chunk >= 1 && rnd() < 0.2) chunk += 0.5;
    if (chunk > left) chunk = left;
    out.push(chunk);
    left = Math.round((left - chunk) * 2) / 2;
  }
  return out;
}

function buildServiceRecords(): SeedServiceRecord[] {
  const rnd = makeRandom(776611);
  const out: SeedServiceRecord[] = [];

  for (const s of generatedStudents) {
    if (!s.username) continue;
    const reviewer = reviewerForGrade[s.gradeId];

    // Distinct day per record keeps records from overlapping in time, which
    // submission hard-blocks (issues 064-074).
    let dayCursor = -20 - Math.floor(rnd() * 40);

    for (const hours of splitIntoSessions(s.targetCoins, rnd)) {
      const a = pick(rnd, selfActivities);
      const startHour = 9 + Math.floor(rnd() * 5); // 09:00-13:00 starts
      out.push({
        studentUsername: s.username,
        sourceType: 'SELF',
        organizationName: a.organizationName,
        serviceCategoryId: a.categoryId,
        serviceDateOffsetDays: dayCursor,
        startHour,
        endHour: startHour + hours,
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
      dayCursor -= 3 + Math.floor(rnd() * 5);
    }
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
    studentUsername: 'student005', sourceType: 'PROJECT', projectTitle: 'Community Reading Companions',
    organizationName: 'Example Community Library',
    serviceCategoryId: 'cat_education', serviceDateOffsetDays: -50, startHour: 9, endHour: 11, location: 'Example Community Library',
    description: 'Read aloud with younger children in the community library reading corner.',
    supervisorKind: 'EXTERNAL_MANUAL', supervisorName: 'Coordinator Xie', supervisorContact: 'reading@example.org',
    targetStatus: 'approved', reviewerUsername: 'teacher005', reasonCode: 'DOCUMENTATION_COMPLETE', comment: 'Attendance sheet received.',
    key: 'student005_project_approved',
  },
  {
    studentUsername: 'student002', sourceType: 'PROJECT', projectTitle: 'Neighbourhood Digital Help Desk',
    organizationName: 'Example Community Service Centre',
    serviceCategoryId: 'cat_community', serviceDateOffsetDays: -65, startHour: 13, endHour: 15, location: 'Example Community Service Centre',
    description: 'Helped older residents set up health-code and messaging apps on their phones.',
    supervisorKind: 'EXTERNAL_MANUAL', supervisorName: 'Director Wu', supervisorContact: 'community@example.org',
    targetStatus: 'approved', reviewerUsername: 'teacher002', reasonCode: 'DOCUMENTATION_COMPLETE', comment: 'Help-desk log confirms the session.',
    key: 'student002_project_approved',
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
    const eligible = generatedStudents.filter(s => p.eligibleGradeIds.includes(s.gradeId));
    if (eligible.length === 0) {
      throw new Error(`Project "${p.title}" has no students in its eligible grades — it would auto-archive.`);
    }
    const wanted = Math.min(eligible.length, p.capacity, 2 + Math.floor(rnd() * 4));
    const picked: string[] = [];
    for (let i = 0; i < wanted; i++) {
      picked.push(eligible[(rotation + i) % eligible.length].studentNumber);
    }
    rotation += wanted;
    return { ...p, registrantStudentNumbers: picked };
  });
}

export const projects: SeedProject[] = assignRegistrants(rawProjects);

const gradeByUsername = new Map(
  generatedStudents.filter(s => s.username).map(s => [s.username!, s.gradeId] as const));

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
  const gradeId = gradeByUsername.get(r.studentUsername);
  if (!gradeId) throw new Error(`Service record references unknown student username "${r.studentUsername}".`);
  return { ...r, reviewerUsername: reviewerForGrade[gradeId] };
}

export const serviceRecords: SeedServiceRecord[] =
  [...buildServiceRecords(), ...narrativeRecords].map(withScopedReviewer);

/** Sum of approved hours per student — seed.ts asserts the live balance matches this after seeding, so a mismatch fails the run instead of leaving a baffling number on screen. */
export const expectedCoinsByUsername: Record<string, number> = serviceRecords
  .filter(r => r.targetStatus === 'approved')
  .reduce((acc, r) => {
    acc[r.studentUsername] = (acc[r.studentUsername] ?? 0) + (r.endHour - r.startHour);
    return acc;
  }, {} as Record<string, number>);
