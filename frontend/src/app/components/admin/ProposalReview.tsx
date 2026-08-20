import { useState, useRef, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { CheckCircle, XCircle, RotateCcw, Search, MoreHorizontal } from 'lucide-react';
import { toast } from 'sonner';
import { api } from '../../api/client';
import { ApiError, mapApiError, newIdempotencyKey } from '../../api/http';
import { usePermissions, useAuth } from '../../App';
import { useUrlSyncedPagedApi } from '../../api/useApi';
import { useDictionaries } from '../../api/useDictionaries';
import { PageControls } from '../PageControls';
import { ListStateBanner } from '../ListStateBanner';
import { DetailWorkbench, type DetailWorkbenchTab } from '../shared/DetailWorkbench';
import { type EventProposal, type TimelineEntry } from '../../api/types';
import { minutesToHours, formatGradeClass } from '../../lib/format';
import { PROPOSAL_STATUS_LABELS, STATUS_COLOR_CLASSES } from '../../lib/statusLabels';

const STATUSES: (EventProposal['status'])[] = ['submitted', 'underReview', 'changesRequested', 'approved', 'rejected'];
type SortKey = 'plannedStartAt' | 'createdAt' | 'status';
const SORT_KEYS: SortKey[] = ['plannedStartAt', 'createdAt', 'status'];

type Decision = 'approve' | 'reject' | 'requestChanges';

/** Small label/value pair for the detail workbench's tabs — same local pattern as AuditCenter/CaseReview's own `Field`. */
function Field({ label, value }: { label: string; value?: string | number | null }) {
  return (
    <div>
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="text-sm text-foreground whitespace-pre-wrap">{value || '—'}</div>
    </div>
  );
}

/**
 * `api.eventProposals.review()`/`.list()` were fully wired (I2-d7) but had
 * no admin consumer at all — `eventProposals.*` was only ever called from
 * the student's own `EventProposals.tsx`, so a submitted proposal had no
 * way to actually be reviewed through the real UI. Found and closed during
 * I2 hardening (`00-Status.md` §25), same pattern as Cases/Registrants.
 */
export function ProposalReview() {
  const { t } = useTranslation();
  const { hasPermission } = usePermissions();
  const canReview = hasPermission('eventProposals.review');
  const [decisionModal, setDecisionModal] = useState<{ item: EventProposal; decision: Decision } | null>(null);
  const [comment, setComment] = useState('');
  const [saving, setSaving] = useState(false);
  const decisionKeyRef = useRef(newIdempotencyKey());
  const { serviceCategories, grades } = useDictionaries();
  const { user } = useAuth();
  // Same scoping AuditCenter.tsx applies to its own grade dropdown — a
  // teacher only ever has rows in their own grades, so the picker shouldn't
  // offer dead options even though the server would just return zero rows either way.
  const availableGrades = user && user.roles.includes('teacher') && !user.roles.includes('admin') && user.scopeGradeIds.length > 0
    ? grades.filter(g => user.scopeGradeIds.includes(g.id))
    : grades;

  // 04-DataViewOptimization.md §5.2: unified detail workbench.
  const [detailId, setDetailId] = useState<string | null>(null);
  const [detailTab, setDetailTab] = useState('content');
  const [detailTimeline, setDetailTimeline] = useState<TimelineEntry[]>([]);
  const [detailTimelineLoading, setDetailTimelineLoading] = useState(false);

  const { items, page, loading, error, params, setSearch, setFilter, setSort, setPage, setPageSize, refetch } =
    useUrlSyncedPagedApi(api.eventProposals.list, { filter: { status: 'submitted' }, sort: '-plannedStartAt' });

  const sortKey = (params.sort?.replace(/^-/, '') as SortKey) ?? 'plannedStartAt';
  const sortDesc = params.sort?.startsWith('-') ?? true;
  // 05-SearchFilterSortConsistency.md §2.1: one button per sort field, same click-to-toggle-direction
  // interaction as ProjectManagement's handleSortClick.
  const handleSortClick = (key: SortKey) => {
    const nextDesc = sortKey === key ? !sortDesc : true;
    setSort(nextDesc ? `-${key}` : key);
  };
  const hasActiveFilters = !!(params.search || params.filter?.categoryId || params.filter?.gradeId);
  const clearFilters = () => { setSearch(''); setFilter('categoryId', undefined); setFilter('gradeId', undefined); };

  const openDetail = (id: string) => { setDetailId(id); setDetailTab('content'); };

  useEffect(() => {
    if (!detailId) { setDetailTimeline([]); return; }
    let cancelled = false;
    setDetailTimelineLoading(true);
    api.eventProposals.timeline(detailId)
      .then(entries => { if (!cancelled) setDetailTimeline(entries); })
      .catch(() => { if (!cancelled) setDetailTimeline([]); })
      .finally(() => { if (!cancelled) setDetailTimelineLoading(false); });
    return () => { cancelled = true; };
  }, [detailId]);

  const openDecision = (item: EventProposal, decision: Decision) => {
    setDecisionModal({ item, decision });
    setComment('');
  };

  const submitDecision = async () => {
    if (!decisionModal) return;
    setSaving(true);
    const { item, decision } = decisionModal;
    try {
      const reasonCode = decision.toUpperCase();
      await api.eventProposals.review(item.id, decision, comment || undefined, reasonCode, item.version, decisionKeyRef.current);
      decisionKeyRef.current = newIdempotencyKey();
      if (decision !== 'approve') toast.success(t('admin.proposals.decisionRecorded'));
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

  const decisionLabel = (d: Decision) => t(
    d === 'approve' ? 'admin.proposals.approve' :
    d === 'reject' ? 'admin.proposals.rejectProposal' : 'admin.proposals.requestChanges'
  );

  return (
    <div className="p-4 lg:p-6 space-y-4">
      <div>
        <h1 className="text-foreground">{t('admin.proposals.title')}</h1>
        <p className="text-sm text-muted-foreground">{t('admin.proposals.count', { count: page?.totalItems ?? items.length })}</p>
      </div>

      {/* Search & filters — 05-SearchFilterSortConsistency.md §2.4: search alone below lg:, filters as an
          Nx1 grid alongside it, merged into one row at lg: and up. */}
      <div className="flex flex-col gap-2 lg:flex-row lg:flex-wrap">
        <div className="relative lg:flex-1 lg:min-w-40">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground" />
          <input type="text" value={params.search ?? ''} onChange={e => setSearch(e.target.value)} placeholder={t('admin.proposals.searchPlaceholder')}
            className="w-full pl-9 pr-4 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground placeholder:text-muted-foreground text-sm focus:outline-none focus:ring-2 focus:ring-primary/50" />
        </div>
        <div className="grid grid-cols-3 gap-2 lg:contents">
          <select value={params.filter?.categoryId ?? 'all'} onChange={e => setFilter('categoryId', e.target.value === 'all' ? undefined : e.target.value)}
            className="px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none">
            <option value="all">{t('admin.proposals.allCategories')}</option>
            {serviceCategories.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
          <select value={params.filter?.status ?? 'all'} onChange={e => setFilter('status', e.target.value === 'all' ? undefined : e.target.value)}
            className="px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none">
            <option value="all">{t('admin.proposals.allStatuses')}</option>
            {STATUSES.map(s => <option key={s} value={s}>{t(PROPOSAL_STATUS_LABELS[s].label)}</option>)}
          </select>
          <select value={params.filter?.gradeId ?? 'all'} onChange={e => setFilter('gradeId', e.target.value === 'all' ? undefined : e.target.value)}
            className="px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none">
            <option value="all">{t('admin.proposals.allGrades')}</option>
            {availableGrades.map(g => <option key={g.id} value={g.id}>{g.name}</option>)}
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
            {key === 'plannedStartAt' ? t('admin.proposals.sortPlannedDate') : key === 'createdAt' ? t('admin.proposals.sortCreated') : t('admin.proposals.sortStatus')}
            {sortKey === key && (sortDesc ? ' ↓' : ' ↑')}
          </button>
        ))}
      </div>

      <ListStateBanner
        error={error}
        empty={!loading && items.length === 0}
        emptyLabel={t(hasActiveFilters ? 'common.list.emptyNoResults' : 'admin.proposals.noProposals')}
        hasActiveFilters={hasActiveFilters}
        onClearFilters={clearFilters}
      />

      <div className="space-y-3">
        {items.map(p => (
          <div key={p.id} onClick={() => openDetail(p.id)}
            className="bg-card border border-border rounded-2xl p-4 cursor-pointer hover:bg-muted/30 transition-colors">
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <div className="flex items-center gap-2 mb-1">
                  <span className={`text-xs px-2 py-0.5 rounded-full ${STATUS_COLOR_CLASSES[PROPOSAL_STATUS_LABELS[p.status].color]}`}>{t(PROPOSAL_STATUS_LABELS[p.status].label)}</span>
                  <span className="text-xs text-muted-foreground">{p.categoryName}</span>
                </div>
                <div className="text-sm text-foreground font-medium truncate">{p.title}</div>
                <div className="text-xs text-muted-foreground">{p.initiatorName}{typeof p.gradeLevel === 'number' ? ` · ${formatGradeClass(p.gradeLevel, p.className ?? '')}` : ''}{p.plannedDate ? ` · ${p.plannedDate}` : ''}{p.location ? ` · ${p.location}` : ''}</div>
                {p.goal && <p className="text-xs text-muted-foreground mt-2">{p.goal}</p>}
              </div>
              {/* Decision actions live in the detail modal's footer only — this just opens it. */}
              <button onClick={e => { e.stopPropagation(); openDetail(p.id); }}
                className="p-2 rounded-lg text-muted-foreground hover:bg-accent hover:text-foreground transition-colors shrink-0">
                <MoreHorizontal className="w-4 h-4" />
              </button>
            </div>
          </div>
        ))}
      </div>

      <PageControls page={page} onPageChange={setPage} pageSize={params.pageSize ?? 20} onPageSizeChange={setPageSize} />

      {/* Detail workbench (04-DataViewOptimization.md §5.2) — renders fields (coOrganizers/mentorTeacher/partnerOrg/riskMeasures/materialNeeds/budgetNeeds/recruitmentMethod/proofMethod) that already existed on EventProposal/ProposalDto but had no admin consumer before this. */}
      {(() => {
        const detailProposal = items.find(i => i.id === detailId);
        if (!detailId || !detailProposal) return null;
        const tabs: DetailWorkbenchTab[] = [
          {
            key: 'content',
            label: t('admin.proposals.tabContent'),
            content: (
              <dl className="grid grid-cols-1 sm:grid-cols-2 gap-x-6 gap-y-3">
                <Field label={t('admin.proposals.fieldStatus')} value={t(PROPOSAL_STATUS_LABELS[detailProposal.status].label)} />
                <Field label={t('student.proposals.serviceCategory')} value={detailProposal.categoryName} />
                <Field label={t('student.proposals.detailLabels.plannedDate')} value={detailProposal.plannedDate} />
                <Field label={t('student.proposals.detailLabels.location')} value={detailProposal.location} />
                <Field label={t('student.proposals.detailLabels.activityFormat')} value={detailProposal.activityFormat} />
                <Field label={t('admin.proposals.fieldCapacity')} value={detailProposal.estimatedCapacity} />
                <Field label={t('admin.proposals.fieldDuration')} value={detailProposal.estimatedDurationMinutes ? `${minutesToHours(detailProposal.estimatedDurationMinutes)} ${t('admin.audit.hoursUnit')}` : undefined} />
                <div className="sm:col-span-2"><Field label={t('student.proposals.detailLabels.goal')} value={detailProposal.goal} /></div>
                <div className="sm:col-span-2"><Field label={t('student.proposals.detailLabels.description')} value={detailProposal.description} /></div>
                <div className="sm:col-span-2"><Field label={t('student.proposals.detailLabels.expectedOutcomes')} value={detailProposal.expectedOutcomes} /></div>
                <div className="sm:col-span-2"><Field label={t('student.proposals.detailLabels.targetParticipants')} value={detailProposal.targetParticipants} /></div>
                <div className="sm:col-span-2"><Field label={t('student.proposals.detailLabels.volunteerTasks')} value={detailProposal.volunteerTasks} /></div>
                {detailProposal.linkedProjectTitle && (
                  <div className="sm:col-span-2 text-sm text-green-700 dark:text-green-400">
                    {t('admin.proposals.approvedProjectNote', { title: detailProposal.linkedProjectTitle })}
                  </div>
                )}
              </dl>
            ),
          },
          {
            key: 'organization',
            label: t('admin.proposals.tabOrganization'),
            content: (
              <dl className="grid grid-cols-1 sm:grid-cols-2 gap-x-6 gap-y-3">
                <Field label={t('student.proposals.detailLabels.initiator')}
                  value={typeof detailProposal.gradeLevel === 'number'
                    ? `${detailProposal.initiatorName} · ${formatGradeClass(detailProposal.gradeLevel, detailProposal.className ?? '')}`
                    : detailProposal.initiatorName} />
                <Field label={t('student.proposals.detailLabels.coOrganizers')} value={detailProposal.coOrganizers} />
                <Field label={t('student.proposals.detailLabels.mentorTeacher')} value={detailProposal.mentorTeacher} />
                <Field label={t('student.proposals.detailLabels.partnerOrg')} value={detailProposal.partnerOrg} />
                <Field label={t('student.proposals.detailLabels.contactInfo')} value={detailProposal.contactInfo} />
                <Field label={t('student.proposals.detailLabels.recruitmentMethod')} value={detailProposal.recruitmentMethod} />
                <Field label={t('student.proposals.detailLabels.eligibility')} value={detailProposal.eligibility} />
                <Field label={t('student.proposals.detailLabels.proofMethod')} value={detailProposal.proofMethod} />
                <div className="sm:col-span-2"><Field label={t('student.proposals.detailLabels.riskMeasures')} value={detailProposal.riskMeasures} /></div>
                <div className="sm:col-span-2"><Field label={t('student.proposals.detailLabels.materialNeeds')} value={detailProposal.materialNeeds} /></div>
                <div className="sm:col-span-2"><Field label={t('student.proposals.detailLabels.budgetNeeds')} value={detailProposal.budgetNeeds} /></div>
              </dl>
            ),
          },
          {
            key: 'history',
            label: t('admin.proposals.tabHistory'),
            content: detailTimelineLoading ? (
              <div className="text-sm text-muted-foreground">{t('common.loading')}</div>
            ) : detailTimeline.length === 0 ? (
              <div className="text-sm text-muted-foreground">{t('admin.proposals.noHistory')}</div>
            ) : (
              <ol className="space-y-3">
                {detailTimeline.map((entry, i) => (
                  <li key={i} className="flex gap-3">
                    <div className="w-2 h-2 rounded-full bg-primary mt-1.5 shrink-0" />
                    <div className="flex-1 min-w-0">
                      <div className="text-xs text-muted-foreground">{entry.occurredAt.slice(0, 16).replace('T', ' ')} · {entry.type}</div>
                      <div className="text-sm text-foreground">{entry.description}</div>
                    </div>
                  </li>
                ))}
              </ol>
            ),
          },
        ];
        const showDecisions = canReview && (detailProposal.status === 'submitted' || detailProposal.status === 'underReview');
        return (
          <DetailWorkbench
            open
            onClose={() => setDetailId(null)}
            title={detailProposal.title}
            subtitle={detailProposal.initiatorName}
            tabs={tabs}
            activeTab={detailTab}
            onTabChange={setDetailTab}
            // 3 decision buttons now fit alongside Cancel in a clean 2x2 grid on mobile
            // (was 4 + a separate mobile-only header X before recommendApproval's removal) —
            // same unified footer pattern CaseReview.tsx already uses.
            closeButton="none"
            footer={
              <div className={`grid gap-3 ${showDecisions ? 'grid-cols-2 sm:flex' : ''}`}>
                <button onClick={() => setDetailId(null)}
                  className="flex-1 py-2.5 rounded-xl border border-border text-sm text-foreground hover:bg-accent transition-colors">
                  {t('common.cancel')}
                </button>
                {showDecisions && (
                  <>
                    <button onClick={() => openDecision(detailProposal, 'approve')}
                      className="flex-1 py-2.5 rounded-xl bg-green-600 text-white text-sm hover:bg-green-700 transition-colors flex items-center justify-center gap-1.5">
                      <CheckCircle className="w-4 h-4" /> {decisionLabel('approve')}
                    </button>
                    <button onClick={() => openDecision(detailProposal, 'requestChanges')}
                      className="flex-1 py-2.5 rounded-xl border border-border text-sm text-muted-foreground hover:bg-orange-50 hover:text-orange-600 hover:border-orange-300 dark:hover:bg-orange-900/20 transition-colors flex items-center justify-center gap-1.5">
                      <RotateCcw className="w-4 h-4" /> {decisionLabel('requestChanges')}
                    </button>
                    <button onClick={() => openDecision(detailProposal, 'reject')}
                      className="flex-1 py-2.5 rounded-xl border border-border text-sm text-muted-foreground hover:bg-red-50 hover:text-red-600 hover:border-red-300 dark:hover:bg-red-900/20 transition-colors flex items-center justify-center gap-1.5">
                      <XCircle className="w-4 h-4" /> {decisionLabel('reject')}
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
              <h3 className="text-foreground">{decisionLabel(decisionModal.decision)}</h3>
            </div>
            <p className="text-xs text-muted-foreground mb-3">{decisionModal.item.title}</p>
            <textarea rows={3} value={comment} onChange={e => setComment(e.target.value)}
              placeholder={t('admin.proposals.commentPlaceholder')} maxLength={1000}
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
