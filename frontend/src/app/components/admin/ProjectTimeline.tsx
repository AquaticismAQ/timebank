import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { History } from 'lucide-react';
import { api } from '../../api/client';
import { ApiError, mapApiError, type ErrorUiMapping } from '../../api/http';
import { ListStateBanner } from '../ListStateBanner';
import { type ProjectTimelineEntry } from '../../api/types';
import { useEscapeToClose } from '../../lib/useEscapeToClose';
import { formatLocalInstant } from '../../lib/datetime';

/** `enrollment.bulk.confirm` → `Enrollment bulk confirm`. */
function humanize(action: string): string {
  const words = action.replace(/\./g, ' ').replace(/([A-Z])/g, ' $1').trim().toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

/**
 * Groups the row colour by what kind of event it is, so a scan finds the shape
 * before the words. Order matters: the refusals are checked before the family
 * they belong to, since a rejected registration reads as a refusal first.
 */
function actionColor(action: string): string {
  if (action.includes('cancel') || action.includes('reject') || action.includes('withdraw')
      || action.includes('delete')) return 'bg-red-500';
  if (action.includes('publish')) return 'bg-green-500';
  if (action.includes('archive')) return 'bg-muted-foreground';
  if (action.startsWith('enrollment.')) return 'bg-blue-500';
  if (action.startsWith('serviceRecord.')) return 'bg-amber-500';
  return 'bg-[oklch(0.488_0.243_264.376)]';
}

/**
 * A project's whole life, in one compact table (issue 158).
 *
 * `projects.audit.read` and `GET /projects/{id}/timeline` both existed with
 * nothing using them, and the endpoint answered with a stub that knew only about
 * creation and field edits — never who published, who registered, who cancelled.
 * This is the screen the permission was named for.
 *
 * **Three columns, deliberately.** When, what, who. A timeline is read to
 * reconstruct a sequence, and the moment it also carries field diffs it stops
 * being scannable; the full before/after of any one change is in System Admin →
 * Audit Logs, which is the screen for that question.
 *
 * Unpaged, because the endpoint is: a project has tens of events, not thousands,
 * and a history with page 2 missing is not a history.
 */
export function ProjectTimeline({ projectId, projectTitle, onClose }: {
  projectId: string;
  projectTitle: string;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  useEscapeToClose(onClose);

  /**
   * Words for the action codes the server sends raw.
   *
   * Falls back to the humanized code rather than showing nothing, so an action
   * added to the backend later appears in the table — legibly enough to act on —
   * instead of rendering as an empty cell. Same bargain `humanizeAuditAction`
   * makes on the Audit Logs screen, and the reason neither place hardcodes a
   * closed list.
   */
  const actionLabel = (action: string) =>
    t(`admin.projects.timelineActions.${action}`, { defaultValue: humanize(action) });

  const [entries, setEntries] = useState<ProjectTimelineEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<ErrorUiMapping | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    api.projects.timeline(projectId)
      .then(result => { if (!cancelled) { setEntries(result); setError(null); } })
      .catch(err => {
        if (cancelled) return;
        setError(err instanceof ApiError ? mapApiError(err) : { kind: 'unknown', message: 'Request failed.' });
        setEntries([]);
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [projectId]);

  return (
    <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center">
      <div className="absolute inset-0 bg-foreground/20 backdrop-blur-sm" />
      {/* A column, not a scrolling box: the header and the way out stay put
          while only the table moves. A long history used to scroll Close off
          the bottom, so the only exit from a 60-row timeline was the Escape
          key — and the panel that has no visible exit is the one people get
          stuck in (issue 102's rule). */}
      <div className="relative bg-card border border-border rounded-2xl p-6 w-full max-w-2xl mx-4 mb-4 sm:mb-0 z-50 max-h-[85vh] flex flex-col"
        onClick={e => e.stopPropagation()}>
        <div className="flex items-center gap-3 mb-4 shrink-0">
          <History className="w-5 h-5 text-muted-foreground shrink-0" />
          <div className="min-w-0">
            <h3 className="text-foreground">{t('admin.projects.timeline')}</h3>
            <p className="text-xs text-muted-foreground truncate">{projectTitle}</p>
          </div>
        </div>

        <ListStateBanner error={error} empty={!loading && entries.length === 0}
          emptyLabel={t('admin.projects.timelineEmpty')} />

        {entries.length > 0 && (
          // `min-h-0` is load-bearing: a flex child defaults to min-height:auto,
          // which refuses to shrink below its content, so the scroll never
          // engages and the dialog grows past its own max height instead.
          <div className="rounded-xl border border-border overflow-y-auto overscroll-contain min-h-0 flex-1">
            <table className="w-full text-sm">
              {/* Sticky, so you can still tell which column is which forty rows down. */}
              <thead className="bg-muted sticky top-0 z-10">
                <tr className="text-left text-xs text-muted-foreground">
                  <th className="px-3 py-2 font-normal whitespace-nowrap">{t('admin.projects.timelineWhen')}</th>
                  <th className="px-3 py-2 font-normal">{t('admin.projects.timelineAction')}</th>
                  <th className="px-3 py-2 font-normal">{t('admin.projects.timelineSubject')}</th>
                  <th className="px-3 py-2 font-normal">{t('admin.projects.timelineWho')}</th>
                </tr>
              </thead>
              <tbody>
                {entries.map((entry, index) => (
                  // No id on the wire — an audit row's id is not part of the
                  // contract here — and the list never reorders, so the index is
                  // a stable key rather than a lazy one.
                  <tr key={`${entry.occurredAt}-${index}`} className="border-t border-border align-top">
                    <td className="px-3 py-2 text-xs text-muted-foreground whitespace-nowrap">
                      {formatLocalInstant(entry.occurredAt)}
                    </td>
                    <td className="px-3 py-2 text-foreground">
                      <span className="flex items-center gap-2">
                        <span className={`w-1.5 h-1.5 rounded-full shrink-0 ${actionColor(entry.action)}`} />
                        {actionLabel(entry.action)}
                      </span>
                      {entry.detail && (
                        <span className="block text-xs text-muted-foreground mt-0.5 break-words">{entry.detail}</span>
                      )}
                    </td>
                    {/* Empty for the project's own events: they are about the
                        project, not about a person, and an em dash says that
                        more clearly than a blank cell. */}
                    <td className="px-3 py-2 text-xs text-muted-foreground">{entry.subject ?? '—'}</td>
                    <td className="px-3 py-2 text-xs text-muted-foreground">
                      {/* No actor means no *person* is on record for this event:
                          a scheduled sweep, or a registration the audit log
                          never saw. An em dash says that; "System" would claim a
                          machine registered a student, and the action's own
                          label already says when something ran automatically. */}
                      {entry.actorName ?? entry.actorId ?? '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        <div className="mt-4 pt-3 border-t border-border flex justify-end shrink-0">
          <button type="button" onClick={onClose}
            className="px-4 py-2 rounded-xl border border-border text-foreground hover:bg-accent text-sm transition-colors">
            {t('common.close')}
          </button>
        </div>
      </div>
    </div>
  );
}
