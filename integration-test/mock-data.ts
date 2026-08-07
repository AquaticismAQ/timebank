// The canonical seed dataset for the TimeBank shared test server. Read
// integration-test/MOCK_DATA_GUIDE.md before editing this file — it
// explains why the shapes here are close to the real backend's
// `Create*Request` DTOs (not the frontend's view-model shapes in
// frontend/src/app/mock/data.ts, which is a separate, independently
// maintained fixture set for the app's own offline mock mode) and what a
// valid gradeId/categoryId/status target looks like.
//
// Date fields are all *offsets in days relative to whenever seed.ts
// actually runs* (see `daysFromNow` in seed.ts), never absolute dates —
// several backend statuses (a project's registrationOpen/inProgress/
// completed) are computed from "is this timestamp before/after now", so a
// hardcoded past date would silently go stale the day after this file was
// written.

// ── Reference data already seeded by Flyway on every fresh schema ─────────
// (R__seed_base_data.sql) — not created here, just the ids this file uses:
//   grade:    grade_6 .. grade_11
//   class:    class_6A/6B .. class_11A/11B
//   academic_year: ay_2025_2026
//   service_category: cat_campus, cat_community, cat_welfare, cat_education, cat_environment, cat_other

export interface SeedOrganization {
  name: string;
  type: 'SCHOOL' | 'NONPROFIT' | 'COMMUNITY' | 'GOVERNMENT' | 'OTHER';
  contactName?: string;
  contactEmail?: string;
  contactPhone?: string;
  address?: string;
}

export const organizations: SeedOrganization[] = [
  { name: 'Example Township Middle School', type: 'SCHOOL', contactName: 'Teacher Wei Chen', contactEmail: 'wei.chen@example-school.cn', contactPhone: '0000-88888888', address: 'No. 1 Campus Road, Example District A, Example City' },
  { name: 'Example City First Welfare Home', type: 'NONPROFIT', contactName: 'Deputy Director Chen', contactEmail: 'welfare@example.org', contactPhone: '0000-87654321', address: 'No. 88 Example Road, Example District B, Example City' },
  { name: 'Example District C Environmental Protection Association', type: 'NONPROFIT', contactName: 'President Li', contactEmail: 'enviro@example.org', contactPhone: '0000-87123456', address: 'Example Town, Example District C, Example City' },
  { name: 'Example County Charity Education Association', type: 'NONPROFIT', contactName: 'Director Zhang', contactEmail: 'charity@example.org', contactPhone: '0000-63456789', address: 'Example Town Two, Example County' },
  { name: 'Example District D Community Service Center', type: 'COMMUNITY', contactName: 'Director Wu', contactEmail: 'community@example.org', contactPhone: '0000-87001122', address: 'Example District D, Example City' },
];

// ── Login accounts ─────────────────────────────────────────────────────────
// Usernames match frontend/src/app/mock/data.ts's existing MOCK_USERS —
// same personas, real backend-issued passwords (see Docs/TestAccounts.md).
// `linkedStudentNumber` sets `user.student_id` after both the user and the
// student row exist — there is no API surface for this link (confirmed
// this session), so it's a documented direct-SQL step in seed.ts, not a
// silently-dropped field.

export interface SeedUser {
  username: string;
  displayName: string;
  email?: string;
  roleCodes: string[];
  linkedStudentNumber?: string;
  // A fresh teacher/admin user has NO teacher_scope_assignment rows, which
  // DataScopeResolverImpl resolves to ScopeDescriptor.Self — i.e. a teacher
  // reviewing a service record or proposal that isn't their own gets a 404
  // ("not found", B1.7's deliberate no-existence-leak behavior), not a 403.
  // Admins bypass scope entirely via their permission grant (`grantAll`),
  // so this only matters for a *teacher* persona this seed asks to review
  // someone else's record/proposal — set the grades they need real GRADE
  // scope over (resourceTypes 'serviceRecords'/'eventProposals', per
  // ServiceRecordServiceImpl/ProposalServiceImpl's RESOURCE_TYPE). There is
  // no admin UI for this yet — seed.ts inserts it directly via SQL.
  scopeGrades?: string[];
}

export const users: SeedUser[] = [
  { username: 'student001', displayName: 'Jia Ming', email: 'jiaming@example-school.cn', roleCodes: ['student'], linkedStudentNumber: '2024001' },
  { username: 'student002', displayName: 'Yun Li', email: 'yunli@example-school.cn', roleCodes: ['student'], linkedStudentNumber: '2024002' },
  { username: 'teacher001', displayName: 'Alex Lin', email: 'alex.lin@example-school.cn', roleCodes: ['teacher'], scopeGrades: ['grade_10'] },
  { username: 'admin001', displayName: 'Teacher Wei Chen', email: 'wei.chen@example-school.cn', roleCodes: ['admin'] },
  { username: 'both001', displayName: 'Jordan', email: 'jordan@example-school.cn', roleCodes: ['student', 'teacher'], linkedStudentNumber: '2024003' },
];

// ── Student roster ──────────────────────────────────────────────────────────
// 10 students across every grade — only 3 (student001/002/both001 above)
// have a login; the rest exercise the admin Student Management screen with
// realistic variety but no portal access, matching how most real schools
// only issue accounts to a subset of students initially.

export interface SeedStudent {
  studentNumber: string;
  displayName: string;
  gradeId: string;
  classId: string;
  graduationYear: number;
  joinedAtOffsetDays: number;
  email?: string;
  guardianName?: string;
  guardianContact?: string;
}

export const students: SeedStudent[] = [
  { studentNumber: '2024001', displayName: 'Jia Ming', gradeId: 'grade_10', classId: 'class_10A', graduationYear: 2028, joinedAtOffsetDays: -700, email: 'jiaming@example-school.cn', guardianName: 'Zhang Daqiang', guardianContact: '139xxxx1234' },
  { studentNumber: '2024002', displayName: 'Yun Li', gradeId: 'grade_10', classId: 'class_10A', graduationYear: 2028, joinedAtOffsetDays: -700, email: 'yunli@example-school.cn', guardianName: 'Li Guoqiang', guardianContact: '138xxxx5678' },
  { studentNumber: '2024003', displayName: 'Jordan', gradeId: 'grade_10', classId: 'class_10B', graduationYear: 2028, joinedAtOffsetDays: -700, email: 'jordan@example-school.cn' },
  { studentNumber: '2024004', displayName: 'Chen Meiling', gradeId: 'grade_9', classId: 'class_9A', graduationYear: 2029, joinedAtOffsetDays: -335, email: 'chenmei@example-school.cn' },
  { studentNumber: '2024005', displayName: 'Liu Yufei', gradeId: 'grade_9', classId: 'class_9B', graduationYear: 2029, joinedAtOffsetDays: -335, email: 'liuyufei@example-school.cn' },
  { studentNumber: '2023001', displayName: 'Huang Zihan', gradeId: 'grade_11', classId: 'class_11A', graduationYear: 2027, joinedAtOffsetDays: -1065, email: 'huangzihan@example-school.cn' },
  { studentNumber: '2023002', displayName: 'Zhao Siyuan', gradeId: 'grade_11', classId: 'class_11B', graduationYear: 2027, joinedAtOffsetDays: -1065, email: 'zhaosy@example-school.cn' },
  { studentNumber: '2025001', displayName: 'Sun Wenting', gradeId: 'grade_7', classId: 'class_7A', graduationYear: 2031, joinedAtOffsetDays: -30, email: 'sunwenting@example-school.cn' },
  { studentNumber: '2025002', displayName: 'Zhou Junjie', gradeId: 'grade_7', classId: 'class_7A', graduationYear: 2031, joinedAtOffsetDays: -30, email: 'zhoujunjie@example-school.cn' },
  { studentNumber: '2025003', displayName: 'Wu Yaqi', gradeId: 'grade_8', classId: 'class_8A', graduationYear: 2030, joinedAtOffsetDays: -335, email: 'wuyaqi@example-school.cn' },
];

// ── Projects ─────────────────────────────────────────────────────────────
// `targetStatus` drives which real endpoints seed.ts calls (create, then
// publish/close as needed) — registrationOpen/inProgress/completed are
// never set directly, they fall out of the date offsets relative to
// whenever the script runs (ProjectServiceImpl's lazy time-based status
// sync), exactly like the real app computes them.

export type ProjectStatusTarget = 'draft' | 'published' | 'registrationOpen' | 'inProgress' | 'completed' | 'closed';

export interface SeedProject {
  title: string;
  summary?: string;
  description?: string;
  categoryId: string;
  organizationName?: string;
  ownerUsername: string;
  capacity: number;
  recognizedMinutes: number;
  eligibleGradeIds: string[];
  evidenceRequirements?: string;
  location?: string;
  startOffsetDays: number;
  endOffsetDays: number;
  registrationOpenOffsetDays: number;
  registrationCloseOffsetDays: number;
  targetStatus: ProjectStatusTarget;
}

export const projects: SeedProject[] = [
  {
    title: 'Campus Open Day Volunteers', summary: 'Assist the school in organizing the fall campus open day, serving as guides and exhibition assistants.',
    description: 'Volunteers help maintain order, guide visiting families through subject exhibition areas, and answer basic questions.',
    categoryId: 'cat_campus', organizationName: 'Example Township Middle School', ownerUsername: 'admin001',
    capacity: 30, recognizedMinutes: 240, eligibleGradeIds: ['grade_9', 'grade_10', 'grade_11'],
    evidenceRequirements: 'On-site photos and signed confirmation form from supervising teacher', location: 'Example Township Middle School Exhibition Areas',
    startOffsetDays: 30, endOffsetDays: 30, registrationOpenOffsetDays: -10, registrationCloseOffsetDays: 20,
    targetStatus: 'registrationOpen',
  },
  {
    title: 'Rural Teaching Support Team (Summer)', summary: 'Week-long teaching support program at a rural elementary school.',
    description: 'Participants provide tutoring in Chinese, Math, and English at a rural elementary school. Room and board arranged by the school.',
    categoryId: 'cat_education', organizationName: 'Example County Charity Education Association', ownerUsername: 'admin001',
    capacity: 12, recognizedMinutes: 1800, eligibleGradeIds: ['grade_10', 'grade_11'],
    evidenceRequirements: 'Hours certificate from the teaching institution and personal work journal', location: 'Example County Example Town Two Central Elementary School',
    startOffsetDays: -2, endOffsetDays: 3, registrationOpenOffsetDays: -30, registrationCloseOffsetDays: -10,
    targetStatus: 'inProgress',
  },
  {
    title: 'School Library Organization and Classification', summary: 'Assist the librarian with organizing books and maintaining the reading environment.',
    description: 'Volunteers classify books, shelve them, update labels, and collect reader feedback under the librarian.',
    categoryId: 'cat_campus', organizationName: 'Example Township Middle School', ownerUsername: 'admin001',
    capacity: 8, recognizedMinutes: 120, eligibleGradeIds: ['grade_6', 'grade_7', 'grade_8', 'grade_9', 'grade_10', 'grade_11'],
    evidenceRequirements: 'Signed confirmation form from the librarian', location: 'Library (H504)',
    startOffsetDays: -60, endOffsetDays: -30, registrationOpenOffsetDays: -90, registrationCloseOffsetDays: -65,
    targetStatus: 'completed',
  },
  {
    title: 'Example District C Community Environmental Cleanup', summary: 'Clean community parks and streets, raise environmental awareness.',
    description: 'In collaboration with the Example District C Environmental Protection Association, students clean streets and distribute educational materials.',
    categoryId: 'cat_environment', organizationName: 'Example District C Environmental Protection Association', ownerUsername: 'teacher001',
    capacity: 20, recognizedMinutes: 180, eligibleGradeIds: ['grade_6', 'grade_7', 'grade_8', 'grade_9', 'grade_10'],
    evidenceRequirements: 'Before-and-after photos and stamped certificate from the organizer', location: 'Example District C Zhongshan Park',
    startOffsetDays: 25, endOffsetDays: 25, registrationOpenOffsetDays: 5, registrationCloseOffsetDays: 20,
    targetStatus: 'published',
  },
  {
    title: 'Nursing Home Visit and Care Activity', summary: 'Visit the welfare home to spend time with elderly residents.',
    description: 'Volunteers chat with elderly residents, assist with crafts, and perform short shows. A brief training session is required beforehand.',
    categoryId: 'cat_welfare', organizationName: 'Example City First Welfare Home', ownerUsername: 'admin001',
    capacity: 15, recognizedMinutes: 120, eligibleGradeIds: ['grade_9', 'grade_10', 'grade_11'],
    evidenceRequirements: 'Stamped confirmation letter from the welfare home and activity photos', location: 'Example City First Welfare Home',
    startOffsetDays: -5, endOffsetDays: 10, registrationOpenOffsetDays: -20, registrationCloseOffsetDays: -8,
    targetStatus: 'closed',
  },
  {
    title: 'Student Council Welcome Party Assistance', summary: 'Assist the student council with the welcome party — check-in, stage setup, crowd control.',
    description: 'Tasks include venue setup before the event, serving as guides/check-in staff during the party, and cleanup afterward.',
    categoryId: 'cat_campus', organizationName: 'Example Township Middle School', ownerUsername: 'teacher001',
    capacity: 25, recognizedMinutes: 180, eligibleGradeIds: ['grade_7', 'grade_8', 'grade_9', 'grade_10'],
    evidenceRequirements: 'Signature from the student council president or supervising teacher', location: 'School Auditorium',
    startOffsetDays: 45, endOffsetDays: 45, registrationOpenOffsetDays: 35, registrationCloseOffsetDays: 43,
    targetStatus: 'draft',
  },
];

// ── Service records ─────────────────────────────────────────────────────
// `targetStatus`: draft (never submitted), submitted (submitted, lands in
// the real review queue, left undecided), approved/returned (submitted
// then decided by `reviewerUsername`, which credits the real ledger /
// sends a real notification). All non-draft records get one real PDF
// evidence file uploaded through the actual 3-step file flow — a service
// record cannot submit without it (EVIDENCE_REQUIRED), so this isn't
// optional realism, it's required to reach any status past draft.

export type RecordStatusTarget = 'draft' | 'submitted' | 'approved' | 'returned';

export interface SeedServiceRecord {
  studentUsername: string;
  sourceType: 'PROJECT' | 'EXTERNAL' | 'SELF';
  projectTitle?: string;
  organizationName?: string;
  serviceCategoryId: string;
  serviceDateOffsetDays: number;
  startHour: number;
  endHour: number;
  location?: string;
  description?: string;
  supervisorName?: string;
  supervisorContact?: string;
  targetStatus: RecordStatusTarget;
  reviewerUsername?: string;
  reasonCode?: string;
  comment?: string;
}

export const serviceRecords: SeedServiceRecord[] = [
  {
    studentUsername: 'student001', sourceType: 'PROJECT', projectTitle: 'School Library Organization and Classification',
    serviceCategoryId: 'cat_campus', serviceDateOffsetDays: -45, startHour: 14, endHour: 16.5, location: 'Library (H504)',
    description: 'Assisted the librarian with reclassifying and shelving humanities and social science books.',
    supervisorName: 'Librarian Wang', supervisorContact: 'library@example-school.cn',
    targetStatus: 'approved', reviewerUsername: 'admin001', reasonCode: 'DOCUMENTATION_COMPLETE', comment: 'Documentation complete, service record verified, approved.',
  },
  {
    studentUsername: 'student001', sourceType: 'EXTERNAL', organizationName: 'Example City City Library',
    serviceCategoryId: 'cat_community', serviceDateOffsetDays: -18, startHour: 9, endHour: 13, location: 'Example City City Main Library',
    description: 'Participated in the summer reading festival, assisted with organizing children reading activities.',
    supervisorName: 'Director Zhao', supervisorContact: 'zhao@nblib.cn',
    targetStatus: 'submitted',
  },
  {
    studentUsername: 'student001', sourceType: 'SELF', organizationName: 'Example District A Community',
    serviceCategoryId: 'cat_community', serviceDateOffsetDays: -28, startHour: 15, endHour: 17, location: 'Community Volunteer Station',
    description: 'Participated in the community summer drowning prevention campaign, distributed educational materials.',
    supervisorName: 'Community Director Li', supervisorContact: '138xxxx8888',
    targetStatus: 'returned', reviewerUsername: 'teacher001', reasonCode: 'INSUFFICIENT_EVIDENCE', comment: 'Documentation incomplete, please submit a stamped confirmation letter from the community.',
  },
  {
    studentUsername: 'student001', sourceType: 'EXTERNAL', organizationName: 'Example District A Cultural Center',
    serviceCategoryId: 'cat_education', serviceDateOffsetDays: -10, startHour: 14, endHour: 16, location: 'Community Reading Corner',
    description: 'Served as a reading tutor volunteer at the community reading corner.',
    targetStatus: 'draft',
  },
  {
    studentUsername: 'student002', sourceType: 'PROJECT', projectTitle: 'Nursing Home Visit and Care Activity',
    serviceCategoryId: 'cat_welfare', serviceDateOffsetDays: -8, startHour: 10, endHour: 12.5, location: 'Example City First Welfare Home',
    description: 'Chatted with elderly residents, assisted with origami crafts, performed songs.',
    supervisorName: 'Deputy Director Chen', supervisorContact: '0000-87654321',
    targetStatus: 'approved', reviewerUsername: 'admin001', reasonCode: 'DOCUMENTATION_COMPLETE', comment: 'Welfare home documentation complete, approved.',
  },
  {
    studentUsername: 'student002', sourceType: 'EXTERNAL', organizationName: 'Example District C Environmental Protection Association',
    serviceCategoryId: 'cat_environment', serviceDateOffsetDays: -3, startHour: 9, endHour: 12, location: 'Zhongshan Park',
    description: 'Helped with pre-event street cleanup and educational material distribution.',
    supervisorName: 'President Li', supervisorContact: 'enviro@example.org',
    targetStatus: 'submitted',
  },
  {
    studentUsername: 'both001', sourceType: 'EXTERNAL', organizationName: 'Example City First Welfare Home',
    serviceCategoryId: 'cat_welfare', serviceDateOffsetDays: -40, startHour: 10, endHour: 12, location: 'Example City First Welfare Home',
    description: 'Assisted with a nursing home visit, helped set up activity materials.',
    supervisorName: 'Deputy Director Chen', supervisorContact: '0000-87654321',
    targetStatus: 'approved', reviewerUsername: 'admin001', reasonCode: 'DOCUMENTATION_COMPLETE', comment: 'Approved.',
  },
  {
    studentUsername: 'both001', sourceType: 'SELF', organizationName: 'Example District A Community',
    serviceCategoryId: 'cat_community', serviceDateOffsetDays: -5, startHour: 15, endHour: 17, location: 'Community Volunteer Station',
    description: 'Draft — not yet submitted.',
    targetStatus: 'draft',
  },
];

// ── Event proposals ─────────────────────────────────────────────────────
// `supervisorUsername` must be a real teacher (proposedSupervisorId is
// checked at create/update time, not re-validated at approve — see
// MOCK_DATA_GUIDE.md). The approved one produces a real generated Project
// as a side effect of ProposalApprovalTransaction.

export type ProposalStatusTarget = 'draft' | 'submitted' | 'changesRequested' | 'approved';

export interface SeedEventProposal {
  studentUsername: string;
  title: string;
  objective: string;
  description: string;
  expectedOutcome: string;
  categoryId: string;
  plannedStartOffsetDays: number;
  plannedEndOffsetDays: number;
  location?: string;
  format?: string;
  targetParticipants?: string;
  capacity: number;
  volunteerTasks?: string;
  supervisorUsername: string;
  organizationName?: string;
  newOrganizationName?: string;
  externalContact?: string;
  safetyPlan: string;
  resourceNeeds?: string;
  recruitmentPlan?: string;
  proposedRecognizedMinutes: number;
  evidencePlan: string;
  targetStatus: ProposalStatusTarget;
  reviewerUsername?: string;
  reasonCode?: string;
  comment?: string;
}

export const eventProposals: SeedEventProposal[] = [
  {
    studentUsername: 'student001', title: 'Campus Reading Promotion — Book Charity Sale and Picture Book Donation',
    objective: 'Promote reading culture through used book charity sales and picture book donations.',
    description: 'Indoor book charity sale area plus an outdoor picture book reading area for younger students. Proceeds fund new books for partner mountain-area schools.',
    expectedOutcome: 'Collect at least 200 books and engage at least 50 children in reading activities.',
    categoryId: 'cat_education', plannedStartOffsetDays: 20, plannedEndOffsetDays: 20,
    location: 'Example Township Middle School Library and Playground', format: 'Charity sale + reading corner',
    targetParticipants: 'All school students and younger grade students', capacity: 30,
    volunteerTasks: 'Book organization and pricing, booth setup, reading guide, cashier, venue cleanup',
    supervisorUsername: 'teacher001', newOrganizationName: 'Example City City Library Volunteer Association',
    externalContact: 'jiaming@example-school.cn',
    safetyPlan: 'Dedicated personnel maintain order during peak hours; charity sale funds jointly managed by two volunteers.',
    resourceNeeds: 'Bookshelves/display tables (borrowed from library), price tags, change fund, picture books',
    recruitmentPlan: 'Class WeChat group announcement + school bulletin board posting',
    proposedRecognizedMinutes: 240, evidencePlan: 'Activity sign-in sheet + supervising teacher confirmation letter + on-site photos',
    targetStatus: 'approved', reviewerUsername: 'teacher001', reasonCode: 'PLAN_COMPLETE', comment: 'Activity plan is complete with clear objectives, approved.',
  },
  {
    studentUsername: 'student001', title: 'Community Elderly Digital Skills Support Workshop',
    objective: 'Help elderly community residents learn basic smartphone operations.',
    description: 'Small group tutoring, 2 volunteers per group for 3-4 elderly participants, covering video calls, QR payments, appointment booking.',
    expectedOutcome: 'Participating elderly master at least 2 digital skills.',
    categoryId: 'cat_welfare', plannedStartOffsetDays: 60, plannedEndOffsetDays: 60,
    location: 'Example District D Community Service Center', format: 'Small group tutoring',
    targetParticipants: 'Community residents aged 60 and above', capacity: 15,
    volunteerTasks: 'On-site tutoring, check-in reception, post-class questionnaire collection',
    supervisorUsername: 'teacher001', organizationName: 'Example District D Community Service Center',
    externalContact: 'jiaming@example-school.cn',
    safetyPlan: 'No screen recording or screenshots of phone content; contact community staff immediately if health issues are noticed.',
    resourceNeeds: 'Projector (provided by community), printed operation guides',
    recruitmentPlan: 'Class WeChat group recruitment',
    proposedRecognizedMinutes: 180, evidencePlan: 'Community staff signed confirmation letter + activity photos',
    targetStatus: 'changesRequested', reviewerUsername: 'teacher001', reasonCode: 'NEEDS_REVISION',
    comment: 'Please clarify how the community confirms elderly participation interest and how privacy protection is implemented operationally.',
  },
  {
    studentUsername: 'student002', title: 'Campus Waste Sorting Awareness and Monitoring Campaign',
    objective: 'Raise awareness of waste sorting among all students and staff.',
    description: 'Volunteers stationed at waste disposal points on each floor for outreach and on-site guidance.',
    expectedOutcome: 'Cover all waste disposal points, distribute at least 300 educational brochures.',
    categoryId: 'cat_environment', plannedStartOffsetDays: 15, plannedEndOffsetDays: 15,
    location: 'Example Township Middle School Campus', format: 'Outreach + station monitoring',
    targetParticipants: 'All students and staff', capacity: 20,
    volunteerTasks: 'Stationed outreach, poster design and posting, brochure distribution',
    supervisorUsername: 'teacher001',
    externalContact: 'yunli@example-school.cn',
    safetyPlan: 'Volunteer rotations during hot weather, each shift no more than 30 minutes; heat-prevention drinks prepared in advance.',
    resourceNeeds: 'Outreach posters, brochures, waste sorting label stickers',
    recruitmentPlan: 'Student council channels + class group announcements',
    proposedRecognizedMinutes: 120, evidencePlan: 'Student council officer signature + photo documentation',
    targetStatus: 'submitted',
  },
  {
    studentUsername: 'both001', title: 'Cross-Grade Subject Tutoring Peer Support Program',
    objective: 'Upper grade students provide after-school subject tutoring for lower grade students.',
    description: '', expectedOutcome: '',
    categoryId: 'cat_education', plannedStartOffsetDays: 40, plannedEndOffsetDays: 40,
    location: 'TBD', format: 'One-on-one tutoring',
    targetParticipants: 'Grade 7-8 students', capacity: 25,
    volunteerTasks: 'Subject tutoring, study plan development assistance',
    supervisorUsername: 'teacher001',
    externalContact: 'jordan@example-school.cn',
    safetyPlan: '', resourceNeeds: '', recruitmentPlan: '',
    proposedRecognizedMinutes: 120, evidencePlan: '',
    targetStatus: 'draft',
  },
];
