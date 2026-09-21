import { useState, useRef, useEffect } from 'react';
import { useBodyScrollLock } from '../../lib/useBodyScrollLock';
import { useTranslation } from 'react-i18next';
import { useBadgeCounts } from '../layout/BadgeCounts';
import { CheckCircle, XCircle, RotateCcw, AlertTriangle, Clock, MoreHorizontal, Send } from 'lucide-react';
import { toast } from 'sonner';
import { api } from '../../api/client';
import { ApiError, mapApiError, newIdempotencyKey } from '../../api/http';
import { handleMutationError } from '../../api/useFieldErrors';
import { usePermissions, useAuth } from '../../App';
import { useUrlSyncedPagedApi } from '../../api/useApi';
import { useDictionaries } from '../../api/useDictionaries';
import { PageControls } from '../PageControls';
import { ListStateBanner } from '../ListStateBanner';
import { EvidencePreview } from '../shared/EvidencePreview';
import { DetailWorkbench, type DetailWorkbenchTab } from '../shared/DetailWorkbench';
import { type ServiceRecord, type TimelineEntry } from '../../api/types';
import { formatGradeClass, minutesToHours } from '../../lib/format';
import { formatLocalInstant } from '../../lib/datetime';
import { ListSearchInput } from '../ListSearchInput';

type SortKey = 'daysWaiting' | 'submittedAt' | 'durationMinutes';
const SORT_KEYS: SortKey[] = ['daysWaiting', 'submittedAt', 'durationMinutes'];

interface BulkFailure {
  id: string;
  name: string;
  message: string;
}

/** Small label/value pair for the detail workbench's Basic Info tab — not shared beyond this file since Case/Proposal Review's field sets differ enough that a shared component would just be a thin wrapper around a `<div>`. */
function Field({ label, value }: { label: string; value?: string | number | null }) {
  return (
    <div>
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="text-sm text-foreground whitespace-pre-wrap">{value || '—'}</div>
    </div>
  );
}

export function AuditCenter() {
  const { t } = useTranslation();
  const badges = useBadgeCounts();
  const { hasPermission } = usePermissions();
  const { user } = useAuth();
  const canDecide = hasPermission('reviews.decide');
  // 04-DataViewOptimization.md §3.2: unified detail workbench — replaces the old
  // mobile-only inline "expand for materials" row (I3.3's fix, superseded here).
  const [detailId, setDetailId] = useState<string | null>(null);
  const [detailTab, setDetailTab] = useState('basic');
  const [detailRecord, setDetailRecord] = useState<ServiceRecord | null>(null);
  const [detailRecordLoading, setDetailRecordLoading] = useState(false);
  const [detailTimeline, setDetailTimeline] = useState<TimelineEntry[]>([]);
  const [detailTimelineLoading, setDetailTimelineLoading] = useState(false);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [decisionModal, setDecisionModal] = useState<{ id: string; action: 'approve' | 'return' | 'reject' } | null>(null);
  // Inline JSX rather than a mounted component, and it does not register
  // with `useEscapeToClose`, so the page lock is driven off its own flag
  // (issue 126).
  useBodyScrollLock(Boolean(decisionModal));
  const [decisionComment, setDecisionComment] = useState('');
  const [decisionReason, setDecisionReason] = useState('');
  const [bulkFailures, setBulkFailures] = useState<BulkFailure[]>([]);

  const { items, page, loading, error, params, setPage, setPageSize, setSearch, setFilter, setSort, refetch } =
    useUrlSyncedPagedApi(api.reviewQueue.list, { sort: '-daysWaiting' });
  const { grades } = useDictionaries();
  // Same scoping AccountManagement.tsx applies to its own grade dropdown — a
  // teacher only ever has rows in their own grades, so the picker shouldn't
  // offer dead options even though the server would just return zero rows either way.
  const availableGrades = user && user.roles.includes('teacher') && !user.roles.includes('admin') && user.scopeGradeIds.length > 0
    ? grades.filter(g => user.scopeGradeIds.includes(g.id))
    : grades;

  // 04-DataViewOptimization.md §3.1 (D8/D9): server-side grade filter + sort now that
  // ReviewController.SORTABLE/filter[gradeId] are real — items already arrive filtered
  // and sorted, no client-side re-derivation.
  const sortKey = (params.sort?.replace(/^-/, '') as SortKey) ?? 'daysWaiting';
  const sortDesc = params.sort?.startsWith('-') ?? true;
  // 05-SearchFilterSortConsistency.md §2.1: one button per sort field (ProjectManagement's
  // handleSortClick pattern) — clicking the active field toggles direction, clicking a
  // different field switches to it at the default (descending) direction.
  const handleSortClick = (key: SortKey) => {
    const nextDesc = sortKey === key ? !sortDesc : true;
    setSort(nextDesc ? `-${key}` : key);
  };

  const openDetail = (id: string) => { setDetailId(id); setDetailTab('basic'); };

  useEffect(() => {
    if (!detailId) { setDetailRecord(null); setDetailTimeline([]); return; }
    const item = items.find(i => i.id === detailId);
    const recordId = item?.serviceRecordId ?? detailId;
    let cancelled = false;
    setDetailRecordLoading(true);
    api.serviceRecords.get(recordId)
      .then(record => { if (!cancelled) setDetailRecord(record); })
      .catch(() => { if (!cancelled) setDetailRecord(null); })
      .finally(() => { if (!cancelled) setDetailRecordLoading(false); });
    setDetailTimelineLoading(true);
    api.serviceRecords.timeline(recordId)
      .then(entries => { if (!cancelled) setDetailTimeline(entries); })
      .catch(() => { if (!cancelled) setDetailTimeline([]); })
      .finally(() => { if (!cancelled) setDetailTimelineLoading(false); });
    return () => { cancelled = true; };
  }, [detailId, items]);

  const toggleSelect = (id: string) => {
    setSelected(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const handleDecision = (id: string, action: 'approve' | 'return' | 'reject') => {
    setDecisionModal({ id, action });
    setDecisionComment('');
    setDecisionReason('');
  };

  const decisionKeyRef = useRef(newIdempotencyKey());

  const submitDecision = async () => {
    if (!decisionModal) return;
    const { id, action } = decisionModal;
    const item = items.find(i => i.id === id);
    try {
      const record = await api.reviewQueue.decide(
        item?.serviceRecordId ?? id,
        action,
        decisionComment || undefined,
        decisionReason || undefined,
        item?.recordVersion,
        decisionKeyRef.current,
      );
      decisionKeyRef.current = newIdempotencyKey();
      if (action === 'approve' && record.creditedUnits != null) {
        toast.success(t('admin.audit.approvedWithCredit', { units: record.creditedUnits }));
        badges.refresh(); // the queue this badge counts just got shorter (issue 154)
      } else {
        const actionLabels: Record<string, string> = { approve: t('admin.audit.approved'), return: t('admin.audit.returned'), reject: t('admin.audit.rejected') };
        toast.success(t('admin.audit.recordDecision', { action: actionLabels[action] }));
        badges.refresh(); // the queue this badge counts just got shorter (issue 154)
      }
      setDecisionModal(null);
      setDetailId(null);
      refetch();
    } catch (err) {
      handleMutationError(err, { refetch, fallbackMessage: t('common.unknownError') });
    }
  };

  const bulkApprove = async () => {
    let succeeded = 0;
    const failures: BulkFailure[] = [];
    for (const id of selected) {
      const item = items.find(i => i.id === id);
      try {
        const key = newIdempotencyKey();
        await api.reviewQueue.decide(item?.serviceRecordId ?? id, 'approve', undefined, 'BATCH_APPROVE', item?.recordVersion, key);
        succeeded++;
      } catch (err) {
        const message = err instanceof ApiError ? mapApiError(err).message : t('common.unknownError');
        failures.push({ id, name: item?.studentName ?? id, message });
      }
    }
    setBulkFailures(failures);
    if (failures.length === 0) {
      toast.success(t('admin.audit.batchApproved', { count: succeeded }));
      badges.refresh(); // the queue this badge counts just got shorter (issue 154)
    } else {
      toast.warning(t('admin.audit.batchApprovedWithFailures', { succeeded, failed: failures.length }));
    }
    setSelected(new Set());
    refetch();
  };

  const priorityColors = { high: 'bg-red-500', medium: 'bg-yellow-500', low: 'bg-green-500' };
  const priorityLabels = { high: t('admin.audit.priorityHigh'), medium: t('admin.audit.priorityNormal'), low: t('admin.audit.priorityLow') };

  return (
    <div className="p-4 lg:p-6 space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-foreground">{t('admin.audit.title')}</h1>
          <p className="text-sm text-muted-foreground">{t('admin.audit.pendingCount', { count: page?.totalItems ?? items.length })}</p>
        </div>
        {canDecide && selected.size > 0 && (
          <button onClick={bulkApprove}
            className="flex items-center gap-1.5 px-4 py-2 bg-green-600 text-white rounded-xl text-sm hover:bg-green-700 transition-colors">
            <CheckCircle className="w-4 h-4" />
            {t('admin.audit.batchApprove')} ({selected.size})
          </button>
        )}
      </div>

      {bulkFailures.length > 0 && (
        <div className="bg-orange-50 dark:bg-orange-900/20 border border-orange-200 dark:border-orange-800 rounded-xl p-3 text-sm">
          <p className="text-orange-800 dark:text-orange-200 font-medium mb-1">{t('admin.audit.batchFailedTitle', { defaultValue: 'Some approvals failed' })}</p>
          <ul className="text-xs text-orange-700 dark:text-orange-300 space-y-0.5">
            {bulkFailures.map(f => (
              <li key={f.id}>{f.name}: {f.message}</li>
            ))}
          </ul>
        </div>
      )}

      {/* Filters row — 05-SearchFilterSortConsistency.md §2.4: search alone below lg:, filters as an
          Nx1 grid alongside it, merged into one row at lg: and up. */}
      <div className="flex flex-col gap-2 lg:flex-row lg:flex-wrap">
        <div className="relative lg:flex-1 lg:min-w-0">
          <ListSearchInput
            value={params.search ?? ''}
            onCommit={setSearch}
            placeholder={t('admin.audit.searchPlaceholder')}
          />
        </div>
        <div className="grid grid-cols-2 gap-2 lg:contents">
          <select value={params.filter?.gradeId ?? 'all'} onChange={e => setFilter('gradeId', e.target.value === 'all' ? undefined : e.target.value)}
            className="px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none">
            <option value="all">{t('admin.audit.allGrades')}</option>
            {availableGrades.map(g => <option key={g.id} value={g.id}>{g.name}</option>)}
          </select>
          <button
            onClick={() => setFilter('hasAnomaly', params.filter?.hasAnomaly ? undefined : 'true')}
            className={`flex items-center justify-center gap-1.5 px-3 py-2.5 rounded-xl border text-sm transition-colors ${
              params.filter?.hasAnomaly ? 'bg-red-100 dark:bg-red-900/30 border-red-300 text-red-700 dark:text-red-400' : 'border-border text-muted-foreground hover:bg-accent'
            }`}
          >
            <AlertTriangle className="w-4 h-4" />
            {t('admin.audit.onlyAnomalies')}
          </button>
        </div>
      </div>

      {/* Sort controls: one button per field (§2.1), always an Nx1 grid (§2.5). */}
      <div className="grid grid-cols-3 gap-2">
        {SORT_KEYS.map(key => (
          <button key={key} onClick={() => handleSortClick(key)}
            className={`px-3 py-1.5 rounded-lg text-xs flex items-center justify-center gap-1 transition-colors ${
              sortKey === key ? 'bg-blue-100 text-blue-700 dark:bg-blue-500/20 dark:text-blue-300' : 'bg-muted text-muted-foreground hover:bg-accent'
            }`}>
            {key === 'daysWaiting' ? t('admin.audit.sortDaysWaiting') : key === 'submittedAt' ? t('admin.audit.sortSubmitted') : t('admin.audit.sortServiceHours')}
            {sortKey === key && (sortDesc ? ' ↓' : ' ↑')}
          </button>
        ))}
      </div>

      {/* Results */}
      <div className="text-xs text-muted-foreground">{t('admin.audit.recordsCount', { count: page?.totalItems ?? items.length })}</div>

      <ListStateBanner
        error={error}
        empty={!loading && items.length === 0}
        emptyLabel={t(params.search || params.filter?.gradeId || params.filter?.hasAnomaly ? 'common.list.emptyNoResults' : 'admin.audit.queueEmpty')}
        hasActiveFilters={!!(params.search || params.filter?.gradeId || params.filter?.hasAnomaly)}
        onClearFilters={() => { setSearch(''); setFilter('gradeId', undefined); setFilter('hasAnomaly', undefined); }}
      />

      {/* Review items */}
      {!error && !(!loading && items.length === 0) && (
        <>
          {/* Desktop: table view */}
          <div className="hidden lg:block bg-card border border-border rounded-2xl overflow-hidden">
            <table className="w-full text-sm">
              <thead className="bg-muted/50 border-b border-border">
                <tr>
                  <th className="px-4 py-3 w-8"></th>
                  <th className="text-left px-4 py-3 text-xs text-muted-foreground font-medium">{t('admin.audit.colStudent')}</th>
                  <th className="text-left px-4 py-3 text-xs text-muted-foreground font-medium">{t('admin.audit.colProjectOrg')}</th>
                  <th className="text-left px-4 py-3 text-xs text-muted-foreground font-medium">{t('admin.audit.colServiceDate')}</th>
                  <th className="text-left px-4 py-3 text-xs text-muted-foreground font-medium">{t('admin.audit.colHours')}</th>
                  <th className="text-left px-4 py-3 text-xs text-muted-foreground font-medium">{t('admin.audit.colWaiting')}</th>
                  <th className="text-right px-4 py-3 text-xs text-muted-foreground font-medium">{t('admin.audit.colActions')}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {items.map(item => {
                  const isSelected = selected.has(item.id);
                  return (
                    <tr key={item.id} onClick={() => openDetail(item.id)}
                      className={`hover:bg-muted/30 transition-colors cursor-pointer ${isSelected ? 'bg-primary/5' : ''}`}>
                      <td className="px-4 py-3">
                        <button onClick={e => { e.stopPropagation(); toggleSelect(item.id); }}
                          className={`w-5 h-5 rounded border-2 flex items-center justify-center transition-colors ${isSelected ? 'bg-primary border-primary' : 'border-border hover:border-primary'}`}>
                          {isSelected && <span className="text-primary-foreground text-xs">✓</span>}
                        </button>
                      </td>
                      <td className="px-4 py-3">
                        <div className="flex items-center gap-2">
                          <div className={`w-2 h-2 rounded-full shrink-0 ${priorityColors[item.priority]}`} />
                          <div>
                            <div className="text-sm text-foreground">{item.studentName}</div>
                            <div className="text-xs text-muted-foreground">{formatGradeClass(item.gradeLevel, item.className)}</div>
                          </div>
                          {item.hasAnomaly && (
                            <span className="text-xs bg-red-100 dark:bg-red-900/30 text-red-600 dark:text-red-400 px-1.5 py-0.5 rounded">{t('admin.audit.anomaly')}</span>
                          )}
                        </div>
                      </td>
                      <td className="px-4 py-3 text-xs text-muted-foreground max-w-xs truncate">
                        {item.projectTitle || item.organizationName}
                      </td>
                      <td className="px-4 py-3 text-xs text-muted-foreground">{item.serviceDate}</td>
                      <td className="px-4 py-3 text-xs text-muted-foreground">{minutesToHours(item.durationMinutes)}h</td>
                      <td className="px-4 py-3">
                        <span className={`text-xs ${item.daysWaiting >= 7 ? 'text-destructive font-medium' : 'text-muted-foreground'}`}>
                          {t('admin.audit.daysWaiting', { days: item.daysWaiting })}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        <div className="flex justify-end">
                          {/* Explicit, keyboard/screen-reader-reachable open trigger (D5) — the row click above is a mouse convenience, not the only path. Decision actions live in the detail modal's footer only, not duplicated here. */}
                          <button onClick={e => { e.stopPropagation(); openDetail(item.id); }}
                            className="p-1.5 rounded-lg text-muted-foreground hover:bg-accent hover:text-foreground transition-colors">
                            <MoreHorizontal className="w-4 h-4" />
                          </button>
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            <div className="px-4 pb-4">
              <PageControls page={page} onPageChange={setPage} pageSize={params.pageSize ?? 20} onPageSizeChange={setPageSize} />
            </div>
          </div>

          {/* Mobile: card view */}
          <div className="lg:hidden space-y-2">
            {items.map(item => {
              const isSelected = selected.has(item.id);
              return (
                <div key={item.id} onClick={() => openDetail(item.id)}
                  className={`bg-card border rounded-2xl overflow-hidden transition-colors cursor-pointer ${isSelected ? 'border-primary/50 bg-primary/5' : 'border-border'}`}>
                  <div className="p-4">
                    <div className="flex items-start gap-3">
                      <button onClick={e => { e.stopPropagation(); toggleSelect(item.id); }}
                        className={`mt-0.5 w-5 h-5 rounded border-2 shrink-0 flex items-center justify-center transition-colors ${isSelected ? 'bg-primary border-primary' : 'border-border hover:border-primary'}`}>
                        {isSelected && <span className="text-primary-foreground text-xs">✓</span>}
                      </button>
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2 flex-wrap mb-1">
                          <div className={`w-2 h-2 rounded-full shrink-0 ${priorityColors[item.priority]}`} />
                          <span className="text-sm text-foreground font-medium">{item.studentName}</span>
                          <span className="text-xs text-muted-foreground">{formatGradeClass(item.gradeLevel, item.className)}</span>
                          {item.hasAnomaly && (
                            <span className="text-xs bg-red-100 dark:bg-red-900/30 text-red-600 dark:text-red-400 px-1.5 py-0.5 rounded flex items-center gap-0.5">
                              <AlertTriangle className="w-3 h-3" /> {t('admin.audit.anomaly')}
                            </span>
                          )}
                          <span className="text-xs text-muted-foreground ml-auto flex items-center gap-1">
                            <Clock className="w-3 h-3" /> {t('admin.audit.daysWaiting', { days: item.daysWaiting })}
                          </span>
                        </div>
                        <div className="text-xs text-muted-foreground mb-2">
                          {item.projectTitle || item.organizationName} · {minutesToHours(item.durationMinutes)}h · {item.serviceDate}
                        </div>
                        {item.hasAnomaly && item.anomalyNote && (
                          <div className="bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-lg px-3 py-2">
                            <div className="text-xs text-red-700 dark:text-red-300">{item.anomalyNote}</div>
                          </div>
                        )}
                      </div>
                      {/* Decision actions live in the detail modal's footer only — this just opens it (D5's explicit open trigger). */}
                      <button onClick={e => { e.stopPropagation(); openDetail(item.id); }}
                        className="p-2 rounded-lg text-muted-foreground hover:bg-accent hover:text-foreground transition-colors shrink-0">
                        <MoreHorizontal className="w-4 h-4" />
                      </button>
                    </div>
                  </div>
                </div>
              );
            })}
            <PageControls page={page} onPageChange={setPage} pageSize={params.pageSize ?? 20} onPageSizeChange={setPageSize} />
          </div>
        </>
      )}

      {/* Detail workbench (04-DataViewOptimization.md §3.2) */}
      {(() => {
        const detailQueueItem = items.find(i => i.id === detailId);
        if (!detailId || !detailQueueItem) return null;
        const tabs: DetailWorkbenchTab[] = [
          {
            key: 'basic',
            label: t('admin.audit.tabBasicInfo'),
            content: detailRecordLoading || !detailRecord ? (
              <div className="text-sm text-muted-foreground">{t('common.loading')}</div>
            ) : (
              <dl className="grid grid-cols-1 sm:grid-cols-2 gap-x-6 gap-y-3">
                <Field label={t('admin.audit.fieldStudent')} value={`${detailRecord.studentName} · ${formatGradeClass(detailQueueItem.gradeLevel, detailQueueItem.className)}`} />
                <Field label={t('admin.audit.fieldRecordNumber')} value={detailRecord.recordNumber} />
                <Field label={t('admin.audit.fieldSource')} value={t(`admin.audit.source${detailRecord.sourceType.charAt(0).toUpperCase() + detailRecord.sourceType.slice(1)}`)} />
                <Field label={t('admin.audit.fieldProjectOrg')} value={detailRecord.projectTitle || detailRecord.organizationName} />
                <Field label={t('admin.audit.fieldServiceDate')} value={detailRecord.serviceDate} />
                <Field label={t('admin.audit.fieldDuration')} value={`${minutesToHours(detailRecord.durationMinutes)} ${t('admin.audit.hoursUnit')}`} />
                <Field label={t('admin.audit.fieldStartTime')} value={formatLocalInstant(detailRecord.startAt)} />
                <Field label={t('admin.audit.fieldEndTime')} value={formatLocalInstant(detailRecord.endAt)} />
                <Field label={t('admin.audit.fieldLocation')} value={detailRecord.location} />
                <Field label={t('admin.audit.fieldCategory')} value={detailRecord.serviceCategoryName} />
                <Field label={t('admin.audit.fieldSupervisor')} value={[detailRecord.supervisorName, detailRecord.supervisorContact].filter(Boolean).join(' · ')} />
                <Field label={t('admin.audit.fieldTravelTime')} value={detailRecord.includesTravelTime ? (detailRecord.travelTimeReason || t('common.yes')) : t('common.no')} />
                <div className="sm:col-span-2">
                  <Field label={t('admin.audit.fieldDescription')} value={detailRecord.description} />
                </div>
                {detailRecord.hasAnomaly && (
                  <div className="sm:col-span-2 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-lg px-3 py-2">
                    <div className="text-xs text-red-700 dark:text-red-300 font-medium flex items-center gap-1.5">
                      <AlertTriangle className="w-3.5 h-3.5" /> {t('admin.audit.fieldAnomalyReason')}
                    </div>
                    <div className="text-sm text-red-700 dark:text-red-300 whitespace-pre-wrap mt-1">
                      {detailRecord.anomalyNote || '—'}
                    </div>
                  </div>
                )}
              </dl>
            ),
          },
          {
            key: 'history',
            label: t('admin.audit.tabHistory'),
            content: detailTimelineLoading ? (
              <div className="text-sm text-muted-foreground">{t('common.loading')}</div>
            ) : detailTimeline.length === 0 ? (
              <div className="text-sm text-muted-foreground">{t('admin.audit.noHistory')}</div>
            ) : (
              <ol className="space-y-3">
                {detailTimeline.map((entry, i) => (
                  <li key={i} className="flex gap-3">
                    <div className="w-2 h-2 rounded-full bg-primary mt-1.5 shrink-0" />
                    <div className="flex-1 min-w-0">
                      <div className="text-xs text-muted-foreground">{formatLocalInstant(entry.occurredAt)} · {entry.type}</div>
                      <div className="text-sm text-foreground">{entry.description}</div>
                    </div>
                  </li>
                ))}
              </ol>
            ),
          },
          {
            key: 'evidence',
            label: t('admin.audit.tabEvidence'),
            content: detailRecordLoading || !detailRecord ? (
              <div className="text-sm text-muted-foreground">{t('common.loading')}</div>
            ) : detailRecord.evidenceFiles.length === 0 ? (
              <div className="text-sm text-muted-foreground">{t('admin.audit.noEvidence')}</div>
            ) : (
              <EvidencePreview files={detailRecord.evidenceFiles} />
            ),
          },
        ];
        const showDecisions = canDecide && (detailQueueItem.status === 'pending' || detailQueueItem.status === 'underReview');
        return (
          <DetailWorkbench
            open
            onClose={() => setDetailId(null)}
            title={detailQueueItem.studentName}
            subtitle={detailRecord?.recordNumber}
            tabs={tabs}
            activeTab={detailTab}
            onTabChange={setDetailTab}
            footer={
              // Cancel replaces the removed header X; grid-cols-2 gives a 2x2 layout on mobile
              // for the 4-button case (Cancel + 3 decisions), sm:flex returns it to a row on desktop.
              <div className={`grid gap-3 ${showDecisions ? 'grid-cols-2 sm:flex' : ''}`}>
                <button onClick={() => setDetailId(null)}
                  className="flex-1 py-2.5 rounded-xl border border-border text-sm text-foreground hover:bg-accent transition-colors">
                  {t('common.cancel')}
                </button>
                {showDecisions && (
                  <>
                    <button onClick={() => handleDecision(detailQueueItem.id, 'return')}
                      className="flex-1 py-2.5 rounded-xl border border-border text-sm text-muted-foreground hover:bg-orange-50 hover:text-orange-600 hover:border-orange-300 dark:hover:bg-orange-900/20 transition-colors flex items-center justify-center gap-1.5">
                      <RotateCcw className="w-4 h-4" /> {t('admin.audit.return')}
                    </button>
                    <button onClick={() => handleDecision(detailQueueItem.id, 'reject')}
                      className="flex-1 py-2.5 rounded-xl border border-border text-sm text-muted-foreground hover:bg-red-50 hover:text-red-600 hover:border-red-300 dark:hover:bg-red-900/20 transition-colors flex items-center justify-center gap-1.5">
                      <XCircle className="w-4 h-4" /> {t('admin.audit.reject')}
                    </button>
                    <button onClick={() => handleDecision(detailQueueItem.id, 'approve')}
                      className="flex-1 py-2.5 rounded-xl bg-green-600 text-white text-sm hover:bg-green-700 transition-colors flex items-center justify-center gap-1.5">
                      <CheckCircle className="w-4 h-4" /> {t('admin.audit.approve')}
                    </button>
                  </>
                )}
              </div>
            }
          />
        );
      })()}

      {/* Decision modal */}
      {decisionModal && (
        <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center">
          <div className="absolute inset-0 bg-foreground/20 backdrop-blur-sm" />
          <div className="relative bg-card border border-border rounded-2xl p-6 w-full max-w-md mx-4 mb-4 sm:mb-0 z-50">
            <h3 className="text-foreground mb-4">
              {decisionModal.action === 'approve' ? `✅ ${t('admin.audit.confirmApprove')}` : decisionModal.action === 'return' ? `↩️ ${t('admin.audit.returnSupplement')}` : `❌ ${t('admin.audit.rejectRecord')}`}
            </h3>

            {decisionModal.action !== 'approve' && (
              <>
                <div className="mb-3">
                  <label className="block text-xs text-muted-foreground mb-1.5">{t('admin.audit.reasonCategory')}</label>
                  <select value={decisionReason} onChange={e => setDecisionReason(e.target.value)}
                    className="w-full px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none">
                    <option value="">{t('admin.audit.selectReason')}</option>
                    {decisionModal.action === 'return' ? (
                      <>
                        <option value="MISSING_EVIDENCE">{t('admin.audit.reasonMissingEvidence')}</option>
                        <option value="SUPERVISOR_MISSING">{t('admin.audit.reasonSupervisorMissing')}</option>
                        <option value="DURATION_UNCLEAR">{t('admin.audit.reasonDurationUnclear')}</option>
                        <option value="INFO_INCOMPLETE">{t('admin.audit.reasonInfoIncomplete')}</option>
                      </>
                    ) : (
                      <>
                        <option value="FAKE_SERVICE">{t('admin.audit.reasonFakeService')}</option>
                        <option value="NON_ELIGIBLE">{t('admin.audit.reasonNonEligible')}</option>
                        <option value="DUPLICATE">{t('admin.audit.reasonDuplicate')}</option>
                        <option value="SAFETY_ISSUE">{t('admin.audit.reasonSafetyIssue')}</option>
                      </>
                    )}
                  </select>
                </div>
              </>
            )}

            <div className="mb-4">
              <label className="block text-xs text-muted-foreground mb-1.5">
                {t('admin.audit.reviewComment')}{decisionModal.action === 'approve' ? ` (${t('admin.audit.optional')})` : '*'}
              </label>
              <textarea
                value={decisionComment}
                onChange={e => setDecisionComment(e.target.value)}
                rows={10}
                maxLength={1000}
                placeholder={decisionModal.action === 'approve' ? t('admin.audit.placeholderApprove') : t('admin.audit.placeholderReject')}
                className="w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground placeholder:text-muted-foreground text-sm focus:outline-none focus:ring-2 focus:ring-primary/50 font-mono resize-none overflow-y-auto whitespace-pre-wrap overscroll-contain"
              />
            </div>

            <div className="flex gap-3">
              <button onClick={() => setDecisionModal(null)}
                className="flex-1 py-3 rounded-xl border border-border text-foreground hover:bg-accent text-sm transition-colors">
                {t('admin.audit.cancel')}
              </button>
              <button
                onClick={submitDecision}
                disabled={decisionModal.action !== 'approve' && (!decisionReason || !decisionComment)}
                className={`flex-1 py-3 rounded-xl text-sm transition-colors flex items-center justify-center gap-2 disabled:opacity-50 ${
                  decisionModal.action === 'approve' ? 'bg-green-600 text-white hover:bg-green-700' :
                  decisionModal.action === 'return' ? 'bg-orange-500 text-white hover:bg-orange-600' :
                  'bg-destructive text-destructive-foreground hover:opacity-90'
                }`}
              >
                <Send className="w-4 h-4" />
                {t('admin.audit.confirm')} {decisionModal.action === 'approve' ? t('admin.audit.approve') : decisionModal.action === 'return' ? t('admin.audit.return') : t('admin.audit.reject')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
