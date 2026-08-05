import { useState, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { CheckCircle, XCircle, HelpCircle, X } from 'lucide-react';
import { toast } from 'sonner';
import { api } from '../../api/client';
import { ApiError, mapApiError, newIdempotencyKey } from '../../api/http';
import { usePermissions } from '../../App';
import { usePagedApi } from '../../api/useApi';
import { PageControls } from '../PageControls';
import { ListStateBanner } from '../ListStateBanner';
import { type Case } from '../../api/types';

const STATUS_COLOR: Record<Case['status'], string> = {
  draft: 'bg-muted text-muted-foreground',
  submitted: 'bg-blue-100 dark:bg-blue-900/30 text-blue-700 dark:text-blue-400',
  inReview: 'bg-yellow-100 dark:bg-yellow-900/30 text-yellow-700 dark:text-yellow-400',
  needsInformation: 'bg-orange-100 dark:bg-orange-900/30 text-orange-700 dark:text-orange-400',
  resolved: 'bg-green-100 dark:bg-green-900/30 text-green-700 dark:text-green-400',
  rejected: 'bg-red-100 dark:bg-red-900/30 text-red-700 dark:text-red-400',
};

const TYPE_LABEL: Record<Case['type'], string> = {
  appeal: 'admin.cases.typeAppeal',
  supplement: 'admin.cases.typeSupplement',
  manualCorrection: 'admin.cases.typeManualCorrection',
};

export function CaseReview() {
  const { t } = useTranslation();
  const { hasPermission } = usePermissions();
  const canDecide = hasPermission('cases.decide');
  const [filterStatus, setFilterStatus] = useState<Case['status'] | 'all'>('all');
  const [decisionModal, setDecisionModal] = useState<{ item: Case; decision: 'resolve' | 'requestInformation' | 'reject' } | null>(null);
  const [comment, setComment] = useState('');
  const [saving, setSaving] = useState(false);
  const decisionKeyRef = useRef(newIdempotencyKey());

  const { items, page, loading, error, setFilter, setPage, refetch } = usePagedApi(api.cases.list, {});

  const handleStatusChange = (value: Case['status'] | 'all') => {
    setFilterStatus(value);
    setFilter('status', value === 'all' ? undefined : value);
  };

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
      setDecisionModal(null);
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

      <div className="flex gap-2 flex-wrap">
        {(['all', 'submitted', 'inReview', 'needsInformation', 'resolved', 'rejected'] as const).map(s => (
          <button key={s} onClick={() => handleStatusChange(s)}
            className={`text-xs px-3 py-1.5 rounded-full border ${filterStatus === s ? 'bg-primary text-primary-foreground border-primary' : 'border-border text-muted-foreground hover:bg-accent'}`}>
            {s === 'all' ? t('common.all', { defaultValue: 'All' }) : t(`admin.cases.status${s.charAt(0).toUpperCase() + s.slice(1)}`)}
          </button>
        ))}
      </div>

      <ListStateBanner error={error} empty={!loading && items.length === 0} emptyLabel={t('admin.cases.noCases')} />

      <div className="space-y-3">
        {items.map(c => (
          <div key={c.id} className="bg-card border border-border rounded-2xl p-4">
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <div className="flex items-center gap-2 mb-1">
                  <span className={`text-xs px-2 py-0.5 rounded-full ${STATUS_COLOR[c.status]}`}>{t(`admin.cases.status${c.status.charAt(0).toUpperCase() + c.status.slice(1)}`)}</span>
                  <span className="text-xs text-muted-foreground">{t(TYPE_LABEL[c.type])}</span>
                </div>
                <div className="text-sm text-foreground font-medium truncate">{c.title}</div>
                <div className="text-xs text-muted-foreground">{c.studentName}{c.submittedAt ? ` · ${c.submittedAt.slice(0, 10)}` : ''}</div>
                {c.description && <p className="text-xs text-muted-foreground mt-2">{c.description}</p>}
              </div>
              {canDecide && (c.status === 'submitted' || c.status === 'inReview') && (
                <div className="flex gap-1.5 shrink-0">
                  <button onClick={() => openDecision(c, 'resolve')} title={t('admin.cases.resolve')}
                    className="p-1.5 rounded-lg text-green-600 hover:bg-green-50 dark:hover:bg-green-900/20"><CheckCircle className="w-4 h-4" /></button>
                  <button onClick={() => openDecision(c, 'requestInformation')} title={t('admin.cases.requestInfo')}
                    className="p-1.5 rounded-lg text-yellow-600 hover:bg-yellow-50 dark:hover:bg-yellow-900/20"><HelpCircle className="w-4 h-4" /></button>
                  <button onClick={() => openDecision(c, 'reject')} title={t('admin.cases.rejectCase')}
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
              <h3 className="text-foreground">{t(`admin.cases.${decisionModal.decision === 'resolve' ? 'resolve' : decisionModal.decision === 'reject' ? 'rejectCase' : 'requestInfo'}`)}</h3>
              <button onClick={() => setDecisionModal(null)} className="text-muted-foreground hover:text-foreground"><X className="w-4 h-4" /></button>
            </div>
            <p className="text-xs text-muted-foreground mb-3">{decisionModal.item.title}</p>
            <textarea rows={3} value={comment} onChange={e => setComment(e.target.value)}
              placeholder={t('admin.cases.commentPlaceholder')}
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
