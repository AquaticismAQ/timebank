import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ChevronDown, ChevronUp, Send, Plus, Pencil, Trash2, Undo2, Upload } from 'lucide-react';
import { toast } from 'sonner';
import { api } from '../../api/client';
import { ApiError, mapApiError, newIdempotencyKey } from '../../api/http';
import { usePagedApi } from '../../api/useApi';
import { PageControls } from '../PageControls';
import { ListStateBanner } from '../ListStateBanner';
import { CASE_STATUS_LABELS, STATUS_COLOR_CLASSES } from '../../lib/statusLabels';
import { type Case, type CaseStatus, type TimelineEntry } from '../../api/types';
import { formatLocalInstant, isoToLocalDate } from '../../lib/datetime';
import { CaseFilingModal } from './CaseFilingModal';

const TYPE_LABEL: Record<Case['type'], string> = {
  appeal: 'admin.cases.typeAppeal',
  supplement: 'admin.cases.typeSupplement',
  manualCorrection: 'admin.cases.typeManualCorrection',
};

const STATUS_FILTERS: (CaseStatus | 'all')[] =
  ['all', 'draft', 'submitted', 'inReview', 'needsInformation', 'resolved', 'rejected'];

/** Statuses that still want something from the student or the reviewer. */
const OPEN_STATUSES: CaseStatus[] = ['draft', 'submitted', 'inReview', 'needsInformation'];

/**
 * The student's own cases, as a page of their own (issue 081). It used to be a
 * tab inside Service Records — reachable only from there, and nearly read-only.
 *
 * A case now has the same lifecycle actions a service record has: save a draft,
 * edit it, submit it, withdraw it while review has not started, and delete it
 * while it is still a draft. The one action that predates this is Respond &
 * Resubmit on a `needsInformation` case (issue 076), without which a reviewer's
 * question could be read but never answered.
 *
 * `GET /cases` is scoped server-side to the caller's own studentId
 * (`CaseScopeFields.ownerIdPath`), so no owner filter is needed here; this
 * stays a slimmed-down `CaseReview.tsx` with no *decision* actions, which are
 * admin-only by design (see issue 076's closing note).
 */
export function StudentCases() {
  const { t } = useTranslation();
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [timeline, setTimeline] = useState<TimelineEntry[]>([]);
  const [timelineLoading, setTimelineLoading] = useState(false);
  const { items, page, loading, error, setPage, setFilter, refetch } =
    usePagedApi(api.cases.list, { sort: '-submittedAt' });
  const [statusFilter, setStatusFilter] = useState<CaseStatus | 'all'>('all');
  const [responding, setResponding] = useState<Case | null>(null);
  const [reply, setReply] = useState('');
  const [saving, setSaving] = useState(false);
  const [filing, setFiling] = useState(false);
  const [editing, setEditing] = useState<Case | null>(null);
  const [deleting, setDeleting] = useState<Case | null>(null);

  const changeStatus = (value: CaseStatus | 'all') => {
    setStatusFilter(value);
    setFilter('status', value === 'all' ? undefined : value);
  };

  /** The reviewer's most recent question, quoted in the modal so the student answers *that*. */
  const latestRequest = (entries: TimelineEntry[]) =>
    [...entries].reverse().find(e => /information|补充|information_requested/i.test(e.type ?? ''))?.description
      ?? [...entries].reverse()[0]?.description;

  const runAction = async (fn: () => Promise<unknown>, successKey: string) => {
    setSaving(true);
    try {
      await fn();
      toast.success(t(successKey));
      setExpandedId(null);
      refetch();
    } catch (err) {
      if (err instanceof ApiError) toast.error(mapApiError(err).message);
      else toast.error(t('common.unknownError'));
    } finally {
      setSaving(false);
    }
  };

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

  const ACTION_CLASS = 'text-xs px-3 py-1.5 rounded-lg border transition-colors flex items-center gap-1 disabled:opacity-50';

  return (
    <div className="p-4 lg:p-6 space-y-4">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h2 className="text-foreground">{t('student.cases.title')}</h2>
          <p className="text-sm text-muted-foreground">{t('student.cases.subtitle')}</p>
        </div>
        <button onClick={() => setFiling(true)}
          className="flex items-center gap-1.5 px-3 py-2 bg-[oklch(0.488_0.243_264.376)] text-white rounded-xl text-sm hover:opacity-90 transition-opacity shrink-0">
          <Plus className="w-4 h-4" /> {t('student.records.fileRequest')}
        </button>
      </div>

      <div className="flex gap-2 overflow-x-auto pb-1">
        {STATUS_FILTERS.map(s => (
          <button key={s} onClick={() => changeStatus(s)}
            className={`px-3 py-1.5 rounded-lg text-xs whitespace-nowrap transition-colors ${
              statusFilter === s ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground hover:text-foreground'
            }`}>
            {s === 'all' ? t('student.cases.allStatuses') : t(CASE_STATUS_LABELS[s].label)}
          </button>
        ))}
      </div>

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

                  {/* Lifecycle actions — which ones exist is decided by the state
                      machine, not by this list: draft edits/submits/deletes,
                      submitted withdraws, needsInformation answers. */}
                  <div className="flex flex-wrap gap-2 pt-1">
                    {c.status === 'draft' && (
                      <>
                        <button type="button" disabled={saving} onClick={() => setEditing(c)}
                          className={`${ACTION_CLASS} border-border text-foreground hover:bg-accent`}>
                          <Pencil className="w-3 h-3" /> {t('common.edit')}
                        </button>
                        <button type="button" disabled={saving}
                          onClick={() => runAction(() => api.cases.submit(c.id, newIdempotencyKey()), 'student.cases.submitted')}
                          className={`${ACTION_CLASS} border-primary/40 text-primary hover:bg-primary/10`}>
                          <Upload className="w-3 h-3" /> {t('common.submit')}
                        </button>
                        <button type="button" disabled={saving} onClick={() => setDeleting(c)}
                          className={`${ACTION_CLASS} border-red-200 dark:border-red-800 text-red-600 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-900/20`}>
                          <Trash2 className="w-3 h-3" /> {t('common.delete')}
                        </button>
                      </>
                    )}
                    {c.status === 'submitted' && (
                      <button type="button" disabled={saving}
                        onClick={() => runAction(() => api.cases.withdraw(c.id), 'student.cases.withdrawn')}
                        className={`${ACTION_CLASS} border-border text-foreground hover:bg-accent`}>
                        <Undo2 className="w-3 h-3" /> {t('student.cases.withdraw')}
                      </button>
                    )}
                    {c.status === 'needsInformation' && (
                      <button type="button" onClick={() => { setResponding(c); setReply(''); }}
                        className={`${ACTION_CLASS} border-green-200 dark:border-green-800 text-green-600 dark:text-green-400 hover:bg-green-50 dark:hover:bg-green-900/20`}>
                        <Send className="w-3 h-3" /> {t('student.cases.respond')}
                      </button>
                    )}
                  </div>
                </div>
              )}
            </div>
          );
        })}
      </div>
      <PageControls page={page} onPageChange={setPage} />

      {(filing || editing) && (
        <CaseFilingModal
          type={editing?.type === 'appeal' ? 'appeal' : 'supplement'}
          record={null}
          draft={editing}
          onClose={() => { setFiling(false); setEditing(null); }}
          onFiled={() => { setFiling(false); setEditing(null); refetch(); }}
        />
      )}

      {deleting && (
        <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4">
          <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" onClick={() => !saving && setDeleting(null)} />
          <div className="relative bg-card w-full sm:max-w-sm sm:rounded-2xl rounded-t-2xl shadow-xl p-5 space-y-4">
            <h3 className="text-foreground font-medium">{t('student.cases.deleteTitle')}</h3>
            <p className="text-sm text-muted-foreground">{t('student.cases.deleteWarning')}</p>
            <p className="text-sm text-foreground break-words">{deleting.title}</p>
            <div className="flex gap-2">
              <button onClick={() => setDeleting(null)} disabled={saving}
                className="flex-1 py-2.5 rounded-xl border border-border text-foreground hover:bg-accent text-sm disabled:opacity-50">{t('common.cancel')}</button>
              <button disabled={saving}
                onClick={() => { const target = deleting; setDeleting(null); runAction(() => api.cases.remove(target.id), 'student.cases.deleted'); }}
                className="flex-1 py-2.5 rounded-xl bg-red-600 text-white hover:opacity-90 text-sm disabled:opacity-50">{t('common.delete')}</button>
            </div>
          </div>
        </div>
      )}

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

export { OPEN_STATUSES };
