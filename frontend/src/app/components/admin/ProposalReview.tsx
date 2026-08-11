import { useState, useRef, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { CheckCircle, XCircle, RotateCcw, ThumbsUp, Search, Eye } from 'lucide-react';
import { toast } from 'sonner';
import { api } from '../../api/client';
import { ApiError, mapApiError, newIdempotencyKey } from '../../api/http';
import { usePermissions } from '../../App';
import { useUrlSyncedPagedApi } from '../../api/useApi';
import { useDictionaries } from '../../api/useDictionaries';
import { PageControls } from '../PageControls';
import { ListStateBanner } from '../ListStateBanner';
import { DetailWorkbench, type DetailWorkbenchTab } from '../shared/DetailWorkbench';
import { type EventProposal, type TimelineEntry } from '../../api/types';
import { minutesToHours } from '../../lib/format';
import { PROPOSAL_STATUS_LABELS, STATUS_COLOR_CLASSES } from '../../lib/statusLabels';

const STATUSES: (EventProposal['status'])[] = ['submitted', 'underReview', 'changesRequested', 'approved', 'rejected'];
type SortKey = 'plannedStartAt' | 'createdAt' | 'status';
const SORT_KEYS: SortKey[] = ['plannedStartAt', 'createdAt', 'status'];

type Decision = 'approve' | 'reject' | 'requestChanges' | 'recommendApproval';

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
  const { serviceCategories } = useDictionaries();

  // 04-DataViewOptimization.md §5.2: unified detail workbench.
  const [detailId, setDetailId] = useState<string | null>(null);
  const [detailTab, setDetailTab] = useState('content');
  const [detailTimeline, setDetailTimeline] = useState<TimelineEntry[]>([]);
  const [detailTimelineLoading, setDetailTimelineLoading] = useState(false);

  const { items, page, loading, error, params, setSearch, setFilter, setSort, setPage, setPageSize, refetch } =
    useUrlSyncedPagedApi(api.eventProposals.list, { filter: { status: 'submitted' }, sort: '-plannedStartAt' });

  const sortKey = (params.sort?.replace(/^-/, '') as SortKey) ?? 'plannedStartAt';
  const sortDesc = params.sort?.startsWith('-') ?? true;
  const setSortKey = (key: SortKey) => setSort(sortDesc ? `-${key}` : key);
  const toggleSortDir = () => setSort(sortDesc ? sortKey : `-${sortKey}`);
  const hasActiveFilters = !!(params.search || params.filter?.categoryId);
  const clearFilters = () => { setSearch(''); setFilter('categoryId', undefined); };

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
    d === 'reject' ? 'admin.proposals.rejectProposal' :
    d === 'requestChanges' ? 'admin.proposals.requestChanges' : 'admin.proposals.recommendApproval'
  );

  if (!hasPermission('eventProposals.read')) {
    return <div className="p-4 lg:p-6 text-sm text-muted-foreground">{t('common.noPermission', { defaultValue: 'You do not have permission to view this page.' })}</div>;
  }

  return (
    <div className="p-4 lg:p-6 space-y-4">
      <div>
        <h1 className="text-foreground">{t('admin.proposals.title')}</h1>
        <p className="text-sm text-muted-foreground">{t('admin.proposals.count', { count: page?.totalItems ?? items.length })}</p>
      </div>

      {/* Unified filter bar (04-DataViewOptimization.md §2.1): search + dropdowns, no status pills. */}
      <div className="flex flex-wrap gap-2">
        <div className="relative flex-1 min-w-40">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground" />
          <input type="text" value={params.search ?? ''} onChange={e => setSearch(e.target.value)} placeholder={t('admin.proposals.searchPlaceholder')}
            className="w-full pl-9 pr-4 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground placeholder:text-muted-foreground text-sm focus:outline-none focus:ring-2 focus:ring-primary/50" />
        </div>
        <select value={params.filter?.categoryId ?? 'all'} onChange={e => setFilter('categoryId', e.target.value === 'all' ? undefined : e.target.value)}
          className="px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none">
          <option value="all">{t('common.all')}</option>
          {serviceCategories.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
        </select>
        <select value={params.filter?.status ?? 'all'} onChange={e => setFilter('status', e.target.value === 'all' ? undefined : e.target.value)}
          className="px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none">
          <option value="all">{t('common.all')}</option>
          {STATUSES.map(s => <option key={s} value={s}>{t(PROPOSAL_STATUS_LABELS[s].label)}</option>)}
        </select>
        <select value={sortKey} onChange={e => setSortKey(e.target.value as SortKey)}
          className="px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none">
          {SORT_KEYS.map(key => (
            <option key={key} value={key}>
              {key === 'plannedStartAt' ? t('admin.proposals.sortPlannedDate') : key === 'createdAt' ? t('admin.proposals.sortCreated') : t('admin.proposals.sortStatus')}
            </option>
          ))}
        </select>
        <button onClick={toggleSortDir}
          className="px-2.5 py-2.5 rounded-xl bg-muted text-muted-foreground hover:bg-accent text-sm transition-colors">
          {sortDesc ? '↓' : '↑'}
        </button>
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
                <div className="text-xs text-muted-foreground">{p.initiatorName}{p.plannedDate ? ` · ${p.plannedDate}` : ''}{p.location ? ` · ${p.location}` : ''}</div>
                {p.goal && <p className="text-xs text-muted-foreground mt-2">{p.goal}</p>}
              </div>
              <div className="flex flex-col items-end gap-1.5 shrink-0">
                <button onClick={e => { e.stopPropagation(); openDetail(p.id); }}
                  className="p-1.5 rounded-lg text-muted-foreground hover:bg-accent flex items-center gap-1 text-xs">
                  <Eye className="w-3.5 h-3.5" /> {t('common.viewDetails')}
                </button>
                {canReview && (p.status === 'submitted' || p.status === 'underReview') && (
                  <div className="flex gap-1.5 flex-wrap justify-end">
                    <button onClick={e => { e.stopPropagation(); openDecision(p, 'approve'); }}
                      className="px-2 py-1 rounded-lg border border-border text-xs text-muted-foreground hover:bg-green-50 hover:text-green-600 hover:border-green-300 dark:hover:bg-green-900/20 transition-colors flex items-center gap-1">
                      <CheckCircle className="w-3 h-3" /> {decisionLabel('approve')}
                    </button>
                    <button onClick={e => { e.stopPropagation(); openDecision(p, 'requestChanges'); }}
                      className="px-2 py-1 rounded-lg border border-border text-xs text-muted-foreground hover:bg-orange-50 hover:text-orange-600 hover:border-orange-300 dark:hover:bg-orange-900/20 transition-colors flex items-center gap-1">
                      <RotateCcw className="w-3 h-3" /> {decisionLabel('requestChanges')}
                    </button>
                    <button onClick={e => { e.stopPropagation(); openDecision(p, 'recommendApproval'); }}
                      className="px-2 py-1 rounded-lg border border-border text-xs text-muted-foreground hover:bg-blue-50 hover:text-blue-600 hover:border-blue-300 dark:hover:bg-blue-900/20 transition-colors flex items-center gap-1">
                      <ThumbsUp className="w-3 h-3" /> {decisionLabel('recommendApproval')}
                    </button>
                    <button onClick={e => { e.stopPropagation(); openDecision(p, 'reject'); }}
                      className="px-2 py-1 rounded-lg border border-border text-xs text-muted-foreground hover:bg-red-50 hover:text-red-600 hover:border-red-300 dark:hover:bg-red-900/20 transition-colors flex items-center gap-1">
                      <XCircle className="w-3 h-3" /> {decisionLabel('reject')}
                    </button>
                  </div>
                )}
              </div>
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
                <Field label={t('student.proposals.detailLabels.initiator')} value={detailProposal.initiatorName} />
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
        return (
          <DetailWorkbench
            open
            onClose={() => setDetailId(null)}
            title={detailProposal.title}
            subtitle={detailProposal.initiatorName}
            tabs={tabs}
            activeTab={detailTab}
            onTabChange={setDetailTab}
            footer={canReview && (detailProposal.status === 'submitted' || detailProposal.status === 'underReview') ? (
              <div className="flex gap-2 flex-wrap">
                <button onClick={() => openDecision(detailProposal, 'approve')}
                  className="flex-1 py-2.5 rounded-xl bg-green-600 text-white text-sm hover:bg-green-700 transition-colors flex items-center justify-center gap-1.5">
                  <CheckCircle className="w-4 h-4" /> {decisionLabel('approve')}
                </button>
                <button onClick={() => openDecision(detailProposal, 'requestChanges')}
                  className="flex-1 py-2.5 rounded-xl border border-border text-sm text-muted-foreground hover:bg-orange-50 hover:text-orange-600 hover:border-orange-300 dark:hover:bg-orange-900/20 transition-colors flex items-center justify-center gap-1.5">
                  <RotateCcw className="w-4 h-4" /> {decisionLabel('requestChanges')}
                </button>
                <button onClick={() => openDecision(detailProposal, 'recommendApproval')}
                  className="flex-1 py-2.5 rounded-xl border border-border text-sm text-muted-foreground hover:bg-blue-50 hover:text-blue-600 hover:border-blue-300 dark:hover:bg-blue-900/20 transition-colors flex items-center justify-center gap-1.5">
                  <ThumbsUp className="w-4 h-4" /> {decisionLabel('recommendApproval')}
                </button>
                <button onClick={() => openDecision(detailProposal, 'reject')}
                  className="flex-1 py-2.5 rounded-xl border border-border text-sm text-muted-foreground hover:bg-red-50 hover:text-red-600 hover:border-red-300 dark:hover:bg-red-900/20 transition-colors flex items-center justify-center gap-1.5">
                  <XCircle className="w-4 h-4" /> {decisionLabel('reject')}
                </button>
              </div>
            ) : undefined}
          />
        );
      })()}

      {decisionModal && (
        <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50 p-4" onClick={() => setDecisionModal(null)}>
          <div className="bg-card border border-border rounded-2xl p-5 w-full max-w-md" onClick={e => e.stopPropagation()}>
            <div className="flex items-center justify-between mb-3">
              <h3 className="text-foreground">{decisionLabel(decisionModal.decision)}</h3>
            </div>
            <p className="text-xs text-muted-foreground mb-3">{decisionModal.item.title}</p>
            <textarea rows={3} value={comment} onChange={e => setComment(e.target.value)}
              placeholder={t('admin.proposals.commentPlaceholder')}
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
