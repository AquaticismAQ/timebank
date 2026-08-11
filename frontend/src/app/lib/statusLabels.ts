// Pure presentational lookup tables — an i18n key + a UI color per status
// value, or a display tier. Not backend data (unlike grades/classes/service
// categories, which come from real dictionary endpoints — see
// api/useDictionaries.ts); these are UI concerns with no server
// counterpart, so they stay hardcoded here rather than being fetched.
import type { RecordStatus, ProjectStatus, ProposalStatus, Case } from '../api/types';

// Shared color-name -> Tailwind-class map, same values `ProjectManagement.tsx`'s
// own local `colorMap` already used — lifted here once Case/Proposal Review
// needed the identical mapping a second and third time (04-DataViewOptimization.md
// §2.2, D2). `ProjectManagement.tsx` itself is left on its own copy; not part of
// this round's scope (D9) to touch a screen that already works.
export const STATUS_COLOR_CLASSES: Record<string, string> = {
  gray: 'bg-muted text-muted-foreground',
  blue: 'bg-blue-100 dark:bg-blue-900/30 text-blue-700 dark:text-blue-400',
  yellow: 'bg-yellow-100 dark:bg-yellow-900/30 text-yellow-700 dark:text-yellow-400',
  green: 'bg-green-100 dark:bg-green-900/30 text-green-700 dark:text-green-400',
  orange: 'bg-orange-100 dark:bg-orange-900/30 text-orange-700 dark:text-orange-400',
  red: 'bg-red-100 dark:bg-red-900/30 text-red-700 dark:text-red-400',
  purple: 'bg-purple-100 dark:bg-purple-900/30 text-purple-700 dark:text-purple-400',
};

export const RECORD_STATUS_LABELS: Record<RecordStatus, { label: string; color: string }> = {
  draft: { label: 'labels.recordStatus.draft', color: 'gray' },
  submitted: { label: 'labels.recordStatus.submitted', color: 'blue' },
  underReview: { label: 'labels.recordStatus.underReview', color: 'yellow' },
  approved: { label: 'labels.recordStatus.approved', color: 'green' },
  returned: { label: 'labels.recordStatus.returned', color: 'orange' },
  rejected: { label: 'labels.recordStatus.rejected', color: 'red' },
  corrected: { label: 'labels.recordStatus.corrected', color: 'purple' },
};

// Order matches the normal flow (02-PorjectOrganization.md §2): Draft ->
// Published -> Registration Open -> Registration Closed (registration window
// elapsed, fully automatic, never a manager action) -> In Progress ->
// Completed -> Archived. `closed` is deliberately not colored red anymore —
// it's an ordinary automatic waypoint now, not a cancellation; a project is
// only ever cancelled via the separate "Cancel and archive" action, which
// goes straight to `archived`.
export const PROJECT_STATUS_LABELS: Record<ProjectStatus, { label: string; color: string }> = {
  draft: { label: 'labels.projectStatus.draft', color: 'gray' },
  published: { label: 'labels.projectStatus.published', color: 'blue' },
  registrationOpen: { label: 'labels.projectStatus.registrationOpen', color: 'green' },
  closed: { label: 'labels.projectStatus.closed', color: 'orange' },
  inProgress: { label: 'labels.projectStatus.inProgress', color: 'yellow' },
  completed: { label: 'labels.projectStatus.completed', color: 'purple' },
  archived: { label: 'labels.projectStatus.archived', color: 'gray' },
};

export const PROPOSAL_STATUS_LABELS: Record<ProposalStatus, { label: string; color: string }> = {
  draft: { label: 'labels.proposalStatus.draft', color: 'gray' },
  submitted: { label: 'labels.proposalStatus.submitted', color: 'blue' },
  underReview: { label: 'labels.proposalStatus.underReview', color: 'blue' },
  changesRequested: { label: 'labels.proposalStatus.changesRequested', color: 'orange' },
  approved: { label: 'labels.proposalStatus.approved', color: 'green' },
  rejected: { label: 'labels.proposalStatus.rejected', color: 'red' },
  withdrawn: { label: 'labels.proposalStatus.withdrawn', color: 'gray' },
};

// 04-DataViewOptimization.md §2.2 (D2): the previously-missing table — Cases had no
// shared-table equivalent at all before, unlike records/projects/proposals.
export const CASE_STATUS_LABELS: Record<Case['status'], { label: string; color: string }> = {
  draft: { label: 'labels.caseStatus.draft', color: 'gray' },
  submitted: { label: 'labels.caseStatus.submitted', color: 'blue' },
  inReview: { label: 'labels.caseStatus.inReview', color: 'yellow' },
  needsInformation: { label: 'labels.caseStatus.needsInformation', color: 'orange' },
  resolved: { label: 'labels.caseStatus.resolved', color: 'green' },
  rejected: { label: 'labels.caseStatus.rejected', color: 'red' },
};

export const TRANSACTION_TYPE_LABELS = {
  serviceCredit: { label: 'labels.txType.serviceCredit', color: 'green', sign: '+' },
  correction: { label: 'labels.txType.correction', color: 'orange', sign: '±' },
  freeze: { label: 'labels.txType.freeze', color: 'blue', sign: '' },
  unfreeze: { label: 'labels.txType.unfreeze', color: 'blue', sign: '' },
  redemption: { label: 'labels.txType.redemption', color: 'purple', sign: '-' },
  borrow: { label: 'labels.txType.borrow', color: 'yellow', sign: '+' },
  repayment: { label: 'labels.txType.repayment', color: 'red', sign: '-' },
  bonus: { label: 'labels.txType.bonus', color: 'green', sign: '+' },
};

// Hour thresholds are a fixed display tier system, matching the seeded
// rule defaults (RuleVersion.honourBronzeMinutes/honourSilverMinutes/
// honourGoldMinutes) at the time this was written — not fetched live. A
// student's *actual current* level always comes from the real backend
// (StudentProgress.recognitionLevel), never computed from these hours
// client-side; this table only drives the label/color/icon and the
// "distance to next tier" progress display. If rule thresholds become
// admin-editable in a way that matters here, source these from
// `api.rules` instead of hardcoding them.
export const RECOGNITION_LEVELS = [
  { level: 'none', label: 'labels.recognition.none', minHours: 0, maxHours: 24, color: 'gray', icon: '—' },
  { level: 'bronze', label: 'labels.recognition.bronze', minHours: 25, maxHours: 74, color: 'orange', icon: '🥉' },
  { level: 'silver', label: 'labels.recognition.silver', minHours: 75, maxHours: 149, color: 'slate', icon: '🥈' },
  { level: 'gold', label: 'labels.recognition.gold', minHours: 150, maxHours: 299, color: 'yellow', icon: '🥇' },
  { level: 'excellent', label: 'labels.recognition.excellent', minHours: 300, maxHours: Infinity, color: 'purple', icon: '🏆' },
];
