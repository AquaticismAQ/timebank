import { useState, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { CheckCircle, XCircle, RotateCcw, ThumbsUp } from 'lucide-react';
import { toast } from 'sonner';
import { api } from '../../api/client';
import { ApiError, mapApiError, newIdempotencyKey } from '../../api/http';
import { usePermissions } from '../../App';
import { usePagedApi } from '../../api/useApi';
import { PageControls } from '../PageControls';
import { ListStateBanner } from '../ListStateBanner';
import { type EventProposal } from '../../api/types';

const STATUS_COLOR: Record<EventProposal['status'], string> = {
  draft: 'bg-muted text-muted-foreground',
  submitted: 'bg-blue-100 dark:bg-blue-900/30 text-blue-700 dark:text-blue-400',
  underReview: 'bg-yellow-100 dark:bg-yellow-900/30 text-yellow-700 dark:text-yellow-400',
  changesRequested: 'bg-orange-100 dark:bg-orange-900/30 text-orange-700 dark:text-orange-400',
  approved: 'bg-green-100 dark:bg-green-900/30 text-green-700 dark:text-green-400',
  rejected: 'bg-red-100 dark:bg-red-900/30 text-red-700 dark:text-red-400',
  withdrawn: 'bg-muted text-muted-foreground',
};

type Decision = 'approve' | 'reject' | 'requestChanges' | 'recommendApproval';

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
  const [filterStatus, setFilterStatus] = useState<EventProposal['status'] | 'all'>('submitted');
  const [decisionModal, setDecisionModal] = useState<{ item: EventProposal; decision: Decision } | null>(null);
  const [comment, setComment] = useState('');
  const [saving, setSaving] = useState(false);
  const decisionKeyRef = useRef(newIdempotencyKey());

  const { items, page, loading, error, setFilter, setPage, refetch } = usePagedApi(api.eventProposals.list, { filter: { status: 'submitted' } });

  const handleStatusChange = (value: EventProposal['status'] | 'all') => {
    setFilterStatus(value);
    setFilter('status', value === 'all' ? undefined : value);
  };

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

      <div className="flex gap-2 flex-wrap">
        {(['all', 'submitted', 'underReview', 'changesRequested', 'approved', 'rejected'] as const).map(s => (
          <button key={s} onClick={() => handleStatusChange(s)}
            className={`text-xs px-3 py-1.5 rounded-full border ${filterStatus === s ? 'bg-primary text-primary-foreground border-primary' : 'border-border text-muted-foreground hover:bg-accent'}`}>
            {s === 'all' ? t('common.all', { defaultValue: 'All' }) : t(`admin.proposals.status${s.charAt(0).toUpperCase() + s.slice(1)}`)}
          </button>
        ))}
      </div>

      <ListStateBanner error={error} empty={!loading && items.length === 0} emptyLabel={t('admin.proposals.noProposals')} />

      <div className="space-y-3">
        {items.map(p => (
          <div key={p.id} className="bg-card border border-border rounded-2xl p-4">
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <div className="flex items-center gap-2 mb-1">
                  <span className={`text-xs px-2 py-0.5 rounded-full ${STATUS_COLOR[p.status]}`}>{t(`admin.proposals.status${p.status.charAt(0).toUpperCase() + p.status.slice(1)}`)}</span>
                  <span className="text-xs text-muted-foreground">{p.categoryName}</span>
                </div>
                <div className="text-sm text-foreground font-medium truncate">{p.title}</div>
                <div className="text-xs text-muted-foreground">{p.initiatorName}{p.plannedDate ? ` · ${p.plannedDate}` : ''}</div>
                {p.goal && <p className="text-xs text-muted-foreground mt-2">{p.goal}</p>}
              </div>
              {canReview && (p.status === 'submitted' || p.status === 'underReview') && (
                <div className="flex gap-1.5 shrink-0">
                  <button onClick={() => openDecision(p, 'approve')} title={decisionLabel('approve')}
                    className="p-1.5 rounded-lg text-green-600 hover:bg-green-50 dark:hover:bg-green-900/20"><CheckCircle className="w-4 h-4" /></button>
                  <button onClick={() => openDecision(p, 'requestChanges')} title={decisionLabel('requestChanges')}
                    className="p-1.5 rounded-lg text-orange-600 hover:bg-orange-50 dark:hover:bg-orange-900/20"><RotateCcw className="w-4 h-4" /></button>
                  <button onClick={() => openDecision(p, 'recommendApproval')} title={decisionLabel('recommendApproval')}
                    className="p-1.5 rounded-lg text-blue-600 hover:bg-blue-50 dark:hover:bg-blue-900/20"><ThumbsUp className="w-4 h-4" /></button>
                  <button onClick={() => openDecision(p, 'reject')} title={decisionLabel('reject')}
                    className="p-1.5 rounded-lg text-red-600 hover:bg-red-50 dark:hover:bg-red-900/20"><XCircle className="w-4 h-4" /></button>
                </div>
              )}
            </div>
          </div>
        ))}
      </div>

      <PageControls page={page} onPageChange={setPage} />

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
