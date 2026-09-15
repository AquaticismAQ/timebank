import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ChevronDown, ChevronUp, Send } from 'lucide-react';
import { toast } from 'sonner';
import { api } from '../../api/client';
import { ApiError, mapApiError, newIdempotencyKey } from '../../api/http';
import { usePagedApi } from '../../api/useApi';
import { PageControls } from '../PageControls';
import { ListStateBanner } from '../ListStateBanner';
import { CASE_STATUS_LABELS, STATUS_COLOR_CLASSES } from '../../lib/statusLabels';
import { type Case, type TimelineEntry } from '../../api/types';
import { formatLocalInstant, isoToLocalDate } from '../../lib/datetime';

const TYPE_LABEL: Record<Case['type'], string> = {
  appeal: 'admin.cases.typeAppeal',
  supplement: 'admin.cases.typeSupplement',
  manualCorrection: 'admin.cases.typeManualCorrection',
};

/**
 * 06-StudentCaseFiling.md §2.4/§3 step 4 — the student's own filed cases.
 * `GET /cases` is already scoped server-side to the caller's own studentId
 * (`CaseScopeFields.ownerIdPath`), so no filter is needed here; this is a
 * slimmed-down `CaseReview.tsx` (no *decision* actions — those stay admin-only).
 *
 * It is not purely read-only: a case in `needsInformation` gets a Respond &
 * Resubmit action. Without it the reviewer's question could be read but never
 * answered, so the case sat in that status forever even though the state
 * machine, the service layer and the API client all support the owner moving it
 * back to `submitted` (issue 076).
 */
export function StudentCases() {
  const { t } = useTranslation();
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [timeline, setTimeline] = useState<TimelineEntry[]>([]);
  const [timelineLoading, setTimelineLoading] = useState(false);
  const { items, page, loading, error, setPage, refetch } = usePagedApi(api.cases.list, { sort: '-submittedAt' });
  const [responding, setResponding] = useState<Case | null>(null);
  const [reply, setReply] = useState('');
  const [saving, setSaving] = useState(false);

  /** The reviewer's most recent question, quoted in the modal so the student answers *that*. */
  const latestRequest = (entries: TimelineEntry[]) =>
    [...entries].reverse().find(e => /information|补充|information_requested/i.test(e.type ?? ''))?.description
      ?? [...entries].reverse()[0]?.description;

  const submitReply = async () => {
    if (!responding) return;
    if (!reply.trim()) { toast.error(t('student.cases.respondRequired')); return; }
    setSaving(true);
    try {
      const appended = [responding.description, `\n\n${t('student.cases.respondPrefix')}: ${reply.trim()}`]
        .filter(Boolean).join('');
      await api.cases.update(responding.id, { description: appended, version: responding.version });
      await api.cases.submit(responding.id, newIdempotencyKey());
      toast.success(t('student.cases.respondSuccess'));
      setResponding(null);
      setReply('');
      refetch();
    } catch (err) {
      if (err instanceof ApiError) toast.error(mapApiError(err).message);
      else toast.error(t('common.unknownError'));
    } finally {
      setSaving(false);
    }
  };

  const toggleExpand = (c: Case) => {
    if (expandedId === c.id) { setExpandedId(null); return; }
    setExpandedId(c.id);
    setTimeline([]);
    setTimelineLoading(true);
    api.cases.timeline(c.id)
      .then(entries => setTimeline(entries as TimelineEntry[]))
      .catch(() => setTimeline([]))
      .finally(() => setTimelineLoading(false));
  };

  return (
    <div className="space-y-2">
      <ListStateBanner error={error} empty={!loading && items.length === 0} emptyLabel={t('student.records.noCases')} />
      {items.map(c => {
        const isExpanded = expandedId === c.id;
        const status = CASE_STATUS_LABELS[c.status];
        return (
          <div key={c.id} className="bg-card border border-border rounded-xl overflow-hidden">
            <button className="w-full p-3 text-left" onClick={() => toggleExpand(c)}>
              <div className="flex items-start gap-3">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 flex-wrap mb-1">
                    <span className={`text-xs px-2 py-0.5 rounded-full whitespace-nowrap ${STATUS_COLOR_CLASSES[status.color]}`}>{t(status.label)}</span>
                    <span className="text-xs text-muted-foreground">{t(TYPE_LABEL[c.type])}</span>
                    {c.subjectLabel && <span className="text-xs text-muted-foreground">{c.subjectLabel}</span>}
                  </div>
                  <div className="text-sm text-foreground font-medium truncate">{c.title}</div>
                  {c.submittedAt && <div className="text-xs text-muted-foreground">{isoToLocalDate(c.submittedAt)}</div>}
                </div>
                {isExpanded ? <ChevronUp className="w-4 h-4 text-muted-foreground shrink-0" /> : <ChevronDown className="w-4 h-4 text-muted-foreground shrink-0" />}
              </div>
            </button>
            {isExpanded && (
              <div className="px-3 pb-3 border-t border-border pt-2 space-y-2 text-xs">
                {c.description && <p className="text-foreground whitespace-pre-wrap">{c.description}</p>}
                <div className="space-y-1.5 pt-1">
                  {timelineLoading ? (
                    <div className="text-muted-foreground">{t('common.loading')}</div>
                  ) : timeline.length === 0 ? (
                    <div className="text-muted-foreground">{t('student.records.noHistory')}</div>
                  ) : (
                    timeline.map((entry, i) => (
                      <div key={i} className="flex gap-2">
                        <div className="w-1.5 h-1.5 rounded-full bg-border mt-1.5 shrink-0" />
                        <div className="flex-1 min-w-0">
                          <div className="text-muted-foreground">{formatLocalInstant(entry.occurredAt)}</div>
                          <div className="text-foreground">{entry.description}</div>
                        </div>
                      </div>
                    ))
                  )}
                </div>
                {c.status === 'needsInformation' && (
                  <button type="button"
                    onClick={() => { setResponding(c); setReply(''); }}
                    className="mt-1 text-xs px-3 py-1.5 rounded-lg border border-green-200 dark:border-green-800 text-green-600 dark:text-green-400 hover:bg-green-50 dark:hover:bg-green-900/20 transition-colors flex items-center gap-1">
                    <Send className="w-3 h-3" /> {t('student.cases.respond')}
                  </button>
                )}
              </div>
            )}
          </div>
        );
      })}
      <PageControls page={page} onPageChange={setPage} />

      {responding && (
        <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4">
          <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" onClick={() => setResponding(null)} />
          <div className="relative bg-card w-full sm:max-w-md sm:rounded-2xl rounded-t-2xl shadow-xl p-5 space-y-4">
            <h3 className="text-sm font-medium text-foreground">{t('student.cases.respondTitle')}</h3>
            {latestRequest(timeline) && (
              <div className="rounded-xl border border-border bg-muted/40 p-3">
                <div className="text-xs text-muted-foreground mb-1">{t('student.cases.respondQuestion')}</div>
                <p className="text-xs text-foreground whitespace-pre-wrap">{latestRequest(timeline)}</p>
              </div>
            )}
            <textarea value={reply} onChange={e => setReply(e.target.value)} rows={4}
              placeholder={t('student.cases.respondPlaceholder')}
              className="w-full px-4 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-primary/40" />
            <div className="flex gap-3">
              <button type="button" onClick={() => setResponding(null)}
                className="flex-1 py-2.5 rounded-xl border border-border text-sm text-foreground hover:bg-accent">
                {t('common.cancel')}
              </button>
              <button type="button" onClick={submitReply} disabled={saving}
                className="flex-1 py-2.5 rounded-xl bg-[oklch(0.488_0.243_264.376)] text-white text-sm hover:opacity-90 disabled:opacity-50">
                {t('common.submit')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
