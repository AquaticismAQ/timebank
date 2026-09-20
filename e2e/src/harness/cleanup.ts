import type { Api } from './api.js';

/**
 * The prefix every `[AUTO]` artefact carries, and the only thing cleanup will
 * act on.
 *
 * This is the whole safety mechanism, so it is deliberately not configurable:
 * cleanup runs against a shared server holding a curated demonstration dataset
 * (`integration-test/STATUS.md`), and a cleanup that could be pointed at
 * anything else is a foot-gun with a flag on it.
 */
const AUTO_PREFIX = '[AUTO]';

export interface CleanupReport {
  projectsCancelled: number;
  projectsSkipped: number;
  draftsDeleted: number;
  /** Records a run left in the review queue. Cleanup cannot touch these — see below. */
  stuckSubmitted: number;
  approvedOrReturned: number;
}

interface ProjectRow { id: string; title: string; status: string; version: number }
interface RecordRow { id: string; recordNumber: string; status: string; projectTitle: string }

/**
 * Tidies what earlier runs left behind, and claims nothing more than it does.
 *
 * Three things, in order of how much they matter:
 *
 * - **Cancels `[AUTO]` projects.** They can never auto-archive, because archive
 *   requires every confirmed student's record to be terminal and a returned
 *   record never is. Cancel is the only close available.
 * - **Deletes stray drafts.** A run that died mid-flight leaves one, and a draft
 *   is the only record that may ever be physically deleted (Invariant 4).
 * - **Counts what it cannot fix**, rather than pretending. Approved and returned
 *   records hold their time windows for good, and submitted ones are sitting in
 *   a teacher's queue awaiting a decision this suite has no business inventing.
 *
 * Driven through the API rather than the browser, unlike everything else here:
 * cleanup is maintenance, not a workflow anyone is being shown, and clicking
 * through dozens of cancel dialogs would be slower without proving anything.
 */
export async function cleanupPreviousRuns(
  adminApi: Api, studentApi: Api, exceptRunId?: string,
): Promise<CleanupReport> {
  const report: CleanupReport = {
    projectsCancelled: 0, projectsSkipped: 0, draftsDeleted: 0,
    stuckSubmitted: 0, approvedOrReturned: 0,
  };

  const projects = await adminApi.get<ProjectRow[]>(
    `/projects?page%5Bsize%5D=200&search=${encodeURIComponent(AUTO_PREFIX)}`,
  );
  for (const project of projects) {
    // Belt and braces: the server-side search is what narrows the list, and this
    // is what guarantees nothing else is ever cancelled.
    if (!project.title.startsWith(AUTO_PREFIX)) continue;
    if (exceptRunId && project.title.includes(exceptRunId)) continue;
    if (project.status === 'ARCHIVED' || project.status === 'CANCELLED') {
      report.projectsSkipped++;
      continue;
    }
    await adminApi.post(`/projects/${project.id}/cancel`, {
      reason: 'Automated end-to-end run cleanup (e2e --cleanup).',
      version: project.version,
    });
    report.projectsCancelled++;
  }

  const records = await studentApi.get<RecordRow[]>('/service-records?page%5Bsize%5D=200');
  for (const record of records) {
    if (!record.projectTitle?.startsWith(AUTO_PREFIX)) continue;
    if (exceptRunId && record.projectTitle.includes(exceptRunId)) continue;
    const status = record.status.toUpperCase();
    if (status === 'DRAFT') {
      await studentApi.delete(`/service-records/${record.id}`);
      report.draftsDeleted++;
    } else if (status === 'SUBMITTED' || status === 'UNDER_REVIEW') {
      report.stuckSubmitted++;
    } else if (status === 'APPROVED' || status === 'RETURNED' || status === 'CORRECTED') {
      report.approvedOrReturned++;
    }
  }

  return report;
}

export function describeCleanup(report: CleanupReport): string[] {
  const lines = [
    `cancelled ${report.projectsCancelled} [AUTO] project(s), skipped ${report.projectsSkipped} already closed`,
    `deleted ${report.draftsDeleted} stray draft record(s)`,
  ];
  if (report.stuckSubmitted > 0) {
    lines.push(
      `left ${report.stuckSubmitted} record(s) awaiting review — a submitted record needs a real decision, `
      + 'and inventing one would put a review in the audit log that nobody made',
    );
  }
  if (report.approvedOrReturned > 0) {
    lines.push(
      `left ${report.approvedOrReturned} approved/returned record(s) — immutable by design, and each still holds `
      + 'its service time window (see README)',
    );
  }
  return lines;
}
