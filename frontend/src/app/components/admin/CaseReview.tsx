import { useState, useRef, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { useBadgeCounts } from '../layout/BadgeCounts';
import { CheckCircle, XCircle, HelpCircle, MoreHorizontal } from 'lucide-react';
import { toast } from 'sonner';
import { api } from '../../api/client';
import { ApiError, mapApiError, newIdempotencyKey } from '../../api/http';
import { usePermissions, useAuth } from '../../App';
import { useUrlSyncedPagedApi } from '../../api/useApi';
import { useAcademicYearFilter } from '../../api/useAcademicYearFilter';
import { useDictionaries } from '../../api/useDictionaries';
import { PageControls } from '../PageControls';
import { ListStateBanner } from '../ListStateBanner';
import { EvidencePreview } from '../shared/EvidencePreview';
import { DetailWorkbench, type DetailWorkbenchTab } from '../shared/DetailWorkbench';
import { type Case, type EvidenceFile, type TimelineEntry } from '../../api/types';
import { CASE_STATUS_LABELS, STATUS_COLOR_CLASSES } from '../../lib/statusLabels';
import { formatGradeClass } from '../../lib/format';
import { formatLocalInstant, isoToLocalDate } from '../../lib/datetime';
import { ListSearchInput } from '../ListSearchInput';
import { useEscapeToClose } from '../../lib/useEscapeToClose';

const STATUSES: (Case['status'])[] = ['submitted', 'needsInformation', 'resolved', 'rejected'];
const TYPES: (Case['type'])[] = ['appeal', 'supplement', 'manualCorrection'];
type SortKey = 'submittedAt' | 'createdAt' | 'status';
const SORT_KEYS: SortKey[] = ['submittedAt', 'createdAt', 'status'];

const TYPE_LABEL: Record<Case['type'], string> = {
  appeal: 'admin.cases.typeAppeal',
  supplement: 'admin.cases.typeSupplement',
  manualCorrection: 'admin.cases.typeManualCorrection',
};

/** Small label/value pair for the detail workbench's Basic Info tab — same pattern as AuditCenter's own local `Field`, not shared since the two domains' field sets don't overlap enough to be worth it. */
function Field({ label, value }: { label: string; value?: string | number | null }) {
  return (
    <div>
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="text-sm text-foreground whitespace-pre-wrap">{value || '—'}</div>
    </div>
  );
}

export function CaseReview() {
  const { t } = useTranslation();
  const badges = useBadgeCounts();
  const { hasPermission } = usePermissions();
  const { user } = useAuth();
  const { grades } = useDictionaries();
  // Same scoping AuditCenter.tsx applies to its own grade dropdown — a
  // teacher only ever has rows in their own grades, so the picker shouldn't
  // offer dead options even though the server would just return zero rows either way.
  const availableGrades = user && user.roles.includes('teacher') && !user.roles.includes('admin') && user.scopeGradeIds.length > 0
    ? grades.filter(g => user.scopeGradeIds.includes(g.id))
    : grades;
  const canDecide = hasPermission('cases.decide');
  const [decisionModal, setDecisionModal] = useState<{ item: Case; decision: 'resolve' | 'requestInformation' | 'reject' } | null>(null);
  const [comment, setComment] = useState('');
  const [saving, setSaving] = useState(false);
  const decisionKeyRef = useRef(newIdempotencyKey());

  // 04-DataViewOptimization.md §4.2: unified detail workbench.
  const [detailId, setDetailId] = useState<string | null>(null);
  const [detailTab, setDetailTab] = useState('basic');
  const [detailTimeline, setDetailTimeline] = useState<TimelineEntry[]>([]);
  const [detailTimelineLoading, setDetailTimelineLoading] = useState(false);
  // null = no linked resource, or the current user can't access it — either way, no materials section content.
  const [detailEvidence, setDetailEvidence] = useState<EvidenceFile[] | null>(null);
  const [detailEvidenceLoading, setDetailEvidenceLoading] = useState(false);

  // Escape closes the topmost open dialog (issue 102 §5).
  useEscapeToClose(() => setDecisionModal(null), Boolean(decisionModal));

  const { items, page, loading, error, params, setSearch, setFilter, setSort, setPage, setPageSize, refetch } =
    useUrlSyncedPagedApi(api.cases.list, { sort: '-submittedAt' });

  const academicYearFilter = useAcademicYearFilter(params, setFilter);

  const openDetail = (id: string) => { setDetailId(id); setDetailTab('basic'); };

  useEffect(() => {
    if (!detailId) { setDetailTimeline([]); setDetailEvidence(null); return; }
    let cancelled = false;
    setDetailTimelineLoading(true);
    api.cases.timeline(detailId)
      .then(entries => { if (!cancelled) setDetailTimeline(entries as TimelineEntry[]); })
      .catch(() => { if (!cancelled) setDetailTimeline([]); })
      .finally(() => { if (!cancelled) setDetailTimelineLoading(false); });
    const item = items.find(i => i.id === detailId);
    if (item?.subjectType === 'serviceRecord' && item.subjectId) {
      setDetailEvidenceLoading(true);
      // Real permission check happens server-side (a 403/404 here just means no materials to show) —
      // deliberately not duplicating a client-side scope check per §4.2 (D8)'s reasoning.
      api.serviceRecords.get(item.subjectId)
        .then(record => { if (!cancelled) setDetailEvidence(record.evidenceFiles); })
        .catch(() => { if (!cancelled) setDetailEvidence(null); })
        .finally(() => { if (!cancelled) setDetailEvidenceLoading(false); });
    } else {
      setDetailEvidence(null);
    }
    return () => { cancelled = true; };
  }, [detailId, items]);

  const sortKey = (params.sort?.replace(/^-/, '') as SortKey) ?? 'submittedAt';
  const sortDesc = params.sort?.startsWith('-') ?? true;
  // 05-SearchFilterSortConsistency.md §2.1: one button per sort field, same click-to-toggle-direction
  // interaction as ProjectManagement's handleSortClick.
  const handleSortClick = (key: SortKey) => {
    const nextDesc = sortKey === key ? !sortDesc : true;
    setSort(nextDesc ? `-${key}` : key);
  };
  const hasActiveFilters = !!(params.search || params.filter?.status || params.filter?.type || params.filter?.gradeId);
  const clearFilters = () => { setSearch(''); setFilter('status', undefined); setFilter('type', undefined); setFilter('gradeId', undefined); };

  const openDecision = (item: Case, decision: 'resolve' | 'requestInformation' | 'reject') => {
    setDecisionModal({ item, decision });
    setComment('');
  };

  const submitDecision = async () => {
    if (!decisionModal) return;
    setSaving(true);
    const { item, decision } = decisionModal;
    try {
      const reasonCode = decision === 'resolve' ? 'RESOLVED' : decision === 'reject' ? 'REJECTED' : 'NEEDS_MORE_INFO';
      await api.cases.decide(item.id, decision, reasonCode, comment || undefined, item.version, decisionKeyRef.current);
      decisionKeyRef.current = newIdempotencyKey();
      toast.success(t('admin.cases.decisionRecorded'));
      badges.refresh(); // a decided case leaves the queue the badge counts (issue 154)
      setDecisionModal(null);
      setDetailId(null);
      refetch();
    } catch (err) {
      if (err instanceof ApiError) toast.error(mapApiError(err).message);
      else toast.error(t('common.unknownError'));
    } finally {
      setSaving(false);
    }
  };

  if (!hasPermission('cases.read')) {
    return <div className="p-4 lg:p-6 text-sm text-muted-foreground">{t('common.noPermission', { defaultValue: 'You do not have permission to view this page.' })}</div>;
  }

  return (
    <div className="p-4 lg:p-6 space-y-4">
      <div>
        <h1 className="text-foreground">{t('admin.cases.title')}</h1>
        <p className="text-sm text-muted-foreground">{t('admin.cases.count', { count: page?.totalItems ?? items.length })}</p>
      </div>

      {/* Filters row — 05-SearchFilterSortConsistency.md §2.4: search alone below lg:, filters as an
          Nx1 grid alongside it, merged into one row at lg: and up. */}
      <div className="flex flex-col gap-2 lg:flex-row lg:flex-wrap">
        <div className="relative lg:flex-1 lg:min-w-40">
          <ListSearchInput
            value={params.search ?? ''}
            onCommit={setSearch}
            placeholder={t('admin.cases.searchPlaceholder')}
          />
        </div>
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-2 lg:contents">
          <select value={params.filter?.type ?? 'all'} onChange={e => setFilter('type', e.target.value === 'all' ? undefined : e.target.value)}
            className="px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none">
            <option value="all">{t('admin.cases.allTypes')}</option>
            {TYPES.map(type => <option key={type} value={type}>{t(TYPE_LABEL[type])}</option>)}
          </select>
          <select value={params.filter?.status ?? 'all'} onChange={e => setFilter('status', e.target.value === 'all' ? undefined : e.target.value)}
            className="px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none">
            <option value="all">{t('admin.cases.allStatuses')}</option>
            {STATUSES.map(s => <option key={s} value={s}>{t(CASE_STATUS_LABELS[s].label)}</option>)}
          </select>
          <select value={params.filter?.gradeId ?? 'all'} onChange={e => setFilter('gradeId', e.target.value === 'all' ? undefined : e.target.value)}
            className="px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none">
            <option value="all">{t('admin.cases.allGrades')}</option>
            {availableGrades.map(g => <option key={g.id} value={g.id}>{g.name}</option>)}
          </select>
          {/* Defaults to the current year (issue 115): a review queue is about
              what is in front of you now, and past years piled in front of it are
              noise. All years stays available so nothing becomes unreachable. */}
          <select value={academicYearFilter.value} onChange={e => academicYearFilter.onChange(e.target.value)}
            className="px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none">
            <option value="all">{t('admin.cases.allAcademicYears')}</option>
            {academicYearFilter.academicYears.map(y => <option key={y.id} value={y.id}>{y.code}</option>)}
          </select>
        </div>
      </div>

      {/* Sort controls: one button per field (§2.1), always an Nx1 grid (§2.5). */}
      <div className="grid grid-cols-3 gap-2">
        {SORT_KEYS.map(key => (
          <button key={key} onClick={() => handleSortClick(key)}
            className={`px-3 py-1.5 rounded-lg text-xs flex items-center justify-center gap-1 transition-colors ${
              sortKey === key ? 'bg-blue-100 text-blue-700 dark:bg-blue-500/20 dark:text-blue-300' : 'bg-muted text-muted-foreground hover:bg-accent'
            }`}>
            {key === 'submittedAt' ? t('admin.cases.sortSubmitted') : key === 'createdAt' ? t('admin.cases.sortCreated') : t('admin.cases.sortStatus')}
            {sortKey === key && (sortDesc ? ' ↓' : ' ↑')}
          </button>
        ))}
      </div>

      <ListStateBanner
        error={error}
        empty={!loading && items.length === 0}
        emptyLabel={t(hasActiveFilters ? 'common.list.emptyNoResults' : 'admin.cases.noCases')}
        hasActiveFilters={hasActiveFilters}
        onClearFilters={clearFilters}
      />

      <div className="space-y-3">
        {items.map(c => (
          <div key={c.id} onClick={() => openDetail(c.id)}
            className="bg-card border border-border rounded-2xl p-4 cursor-pointer hover:bg-muted/30 transition-colors">
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <div className="flex items-center gap-2 mb-1">
                  <span className={`text-xs px-2 py-0.5 rounded-full whitespace-nowrap ${STATUS_COLOR_CLASSES[CASE_STATUS_LABELS[c.status].color]}`}>{t(CASE_STATUS_LABELS[c.status].label)}</span>
                  <span className="text-xs text-muted-foreground">{t(TYPE_LABEL[c.type])}</span>
                </div>
                <div className="text-sm text-foreground font-medium truncate">{c.title}</div>
                <div className="text-xs text-muted-foreground">{c.studentName} · {formatGradeClass(c.gradeLevel, c.className)}{c.submittedAt ? ` · ${isoToLocalDate(c.submittedAt)}` : ''}</div>
                {c.description && <p className="text-xs text-muted-foreground mt-2">{c.description}</p>}
              </div>
              {/* Decision actions live in the detail modal's footer only — this just opens it. */}
              <button onClick={e => { e.stopPropagation(); openDetail(c.id); }}
                className="p-2 rounded-lg text-muted-foreground hover:bg-accent hover:text-foreground transition-colors shrink-0">
                <MoreHorizontal className="w-4 h-4" />
              </button>
            </div>
          </div>
        ))}
      </div>

      <PageControls page={page} onPageChange={setPage} pageSize={params.pageSize ?? 20} onPageSizeChange={setPageSize} />

      {/* Detail workbench (04-DataViewOptimization.md §4.2) */}
      {(() => {
        const detailCase = items.find(i => i.id === detailId);
        if (!detailId || !detailCase) return null;
        const tabs: DetailWorkbenchTab[] = [
          {
            key: 'basic',
            label: t('admin.cases.tabBasicInfo'),
            content: (
              <dl className="grid grid-cols-1 sm:grid-cols-2 gap-x-6 gap-y-3">
                <Field label={t('admin.cases.fieldType')} value={t(TYPE_LABEL[detailCase.type])} />
                <Field label={t('admin.cases.fieldStatus')} value={t(CASE_STATUS_LABELS[detailCase.status].label)} />
                <Field label={t('admin.cases.fieldStudent')} value={`${detailCase.studentName} · ${formatGradeClass(detailCase.gradeLevel, detailCase.className)}`} />
                <Field label={t('admin.cases.fieldReasonCode')} value={detailCase.reasonCode} />
                {detailCase.subjectLabel && <Field label={t('admin.cases.fieldSubject')} value={detailCase.subjectLabel} />}
                {detailCase.requestedAmountCentiCoins != null && <Field label={t('admin.cases.fieldAmount')} value={detailCase.requestedAmountCentiCoins} />}
                <Field label={t('admin.cases.fieldSubmitted')} value={formatLocalInstant(detailCase.submittedAt)} />
                <div className="sm:col-span-2">
                  <Field label={t('admin.cases.fieldDescription')} value={detailCase.description} />
                </div>
              </dl>
            ),
          },
          {
            key: 'history',
            label: t('admin.cases.tabHistory'),
            content: detailTimelineLoading ? (
              <div className="text-sm text-muted-foreground">{t('common.loading')}</div>
            ) : detailTimeline.length === 0 ? (
              <div className="text-sm text-muted-foreground">{t('admin.cases.noHistory')}</div>
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
            key: 'materials',
            label: t('admin.cases.tabMaterials'),
            content: detailEvidenceLoading ? (
              <div className="text-sm text-muted-foreground">{t('common.loading')}</div>
            ) : !detailEvidence || detailEvidence.length === 0 ? (
              <div className="text-sm text-muted-foreground">{t('admin.cases.noMaterials')}</div>
            ) : (
              // Same viewer the record audit uses (issue 124): a case reviewer
              // judges the same evidence an auditor does, so it must be as
              // readable here — thumbnails in place, and a click opens the file
              // in `EvidenceViewerDialog` rather than throwing the reviewer out
              // to a browser tab. Deliberately `EvidencePreview`, not a second
              // viewer of its own.
              <EvidencePreview files={detailEvidence} />
            ),
          },
        ];
        const showDecisions = canDecide && detailCase.status === 'submitted';
        return (
          <DetailWorkbench
            open
            onClose={() => setDetailId(null)}
            title={detailCase.title}
            subtitle={detailCase.studentName}
            tabs={tabs}
            activeTab={detailTab}
            onTabChange={setDetailTab}
            footer={
              <div className={`grid gap-3 ${showDecisions ? 'grid-cols-2 sm:flex' : ''}`}>
                <button onClick={() => setDetailId(null)}
                  className="flex-1 py-2.5 rounded-xl border border-border text-sm text-foreground hover:bg-accent transition-colors">
                  {t('common.cancel')}
                </button>
                {showDecisions && (
                  <>
                    <button onClick={() => openDecision(detailCase, 'resolve')}
                      className="flex-1 py-2.5 rounded-xl bg-green-600 text-white text-sm hover:bg-green-700 transition-colors flex items-center justify-center gap-1.5">
                      <CheckCircle className="w-4 h-4" /> {t('admin.cases.resolve')}
                    </button>
                    <button onClick={() => openDecision(detailCase, 'requestInformation')}
                      className="flex-1 py-2.5 rounded-xl border border-border text-sm text-muted-foreground hover:bg-yellow-50 hover:text-yellow-600 hover:border-yellow-300 dark:hover:bg-yellow-900/20 transition-colors flex items-center justify-center gap-1.5">
                      <HelpCircle className="w-4 h-4" /> {t('admin.cases.requestInfo')}
                    </button>
                    <button onClick={() => openDecision(detailCase, 'reject')}
                      className="flex-1 py-2.5 rounded-xl border border-border text-sm text-muted-foreground hover:bg-red-50 hover:text-red-600 hover:border-red-300 dark:hover:bg-red-900/20 transition-colors flex items-center justify-center gap-1.5">
                      <XCircle className="w-4 h-4" /> {t('admin.cases.rejectCase')}
                    </button>
                  </>
                )}
              </div>
            }
          />
        );
      })()}

      {decisionModal && (
        <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50 p-4">
          <div className="bg-card border border-border rounded-2xl p-5 w-full max-w-md">
            <div className="flex items-center justify-between mb-3">
              <h3 className="text-foreground">{t(`admin.cases.${decisionModal.decision === 'resolve' ? 'resolve' : decisionModal.decision === 'reject' ? 'rejectCase' : 'requestInfo'}`)}</h3>
            </div>
            <p className="text-xs text-muted-foreground mb-3">{decisionModal.item.title}</p>
            <textarea rows={3} value={comment} onChange={e => setComment(e.target.value)}
              placeholder={t('admin.cases.commentPlaceholder')} maxLength={1000}
              className="w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-primary/50 resize-none" />
            <div className="flex gap-3 mt-4">
              <button onClick={() => setDecisionModal(null)} disabled={saving}
                className="flex-1 py-2.5 rounded-xl border border-border text-foreground hover:bg-accent text-sm">{t('common.cancel')}</button>
              <button onClick={submitDecision} disabled={saving}
                className="flex-1 py-2.5 rounded-xl bg-primary text-primary-foreground hover:opacity-90 text-sm disabled:opacity-50">
                {saving ? t('common.saving', { defaultValue: 'Saving...' }) : t('common.submit')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
