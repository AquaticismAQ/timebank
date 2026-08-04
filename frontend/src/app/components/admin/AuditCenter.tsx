import { useState, useEffect, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { Search, CheckCircle, XCircle, RotateCcw, AlertTriangle, Clock, ChevronDown, ChevronUp, Send } from 'lucide-react';
import { GRADE_LABEL_KEYS } from '../../mock/data';
import { type ReviewQueueItem } from '../../api/types';
import { toast } from 'sonner';
import { api } from '../../api/client';

type SortKey = 'daysWaiting' | 'submittedAt' | 'durationMinutes';

export function AuditCenter() {
  const { t } = useTranslation();
  const [search, setSearch] = useState('');
  const [filterGrade, setFilterGrade] = useState('all');
  const [showAnomaly, setShowAnomaly] = useState(false);
  const [sortKey, setSortKey] = useState<SortKey>('daysWaiting');
  const [sortDesc, setSortDesc] = useState(true);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [decisionModal, setDecisionModal] = useState<{ id: string; action: 'approve' | 'return' | 'reject' } | null>(null);
  const [decisionComment, setDecisionComment] = useState('');
  const [decisionReason, setDecisionReason] = useState('');
  const [queue, setQueue] = useState<ReviewQueueItem[]>([]);

  useEffect(() => {
    api.reviewQueue.list().then(setQueue).catch(() => {});
  }, []);

  const grades = [...new Set(queue.map(r => r.grade))];;

  const filtered = useMemo(() => {
    let items = queue.filter(r => {
      const matchSearch = !search || r.studentName.includes(search) || r.organizationName.includes(search) || (r.projectTitle || '').includes(search);
      const matchGrade = filterGrade === 'all' || r.grade === filterGrade;
      const matchAnomaly = !showAnomaly || r.hasAnomaly;
      return matchSearch && matchGrade && matchAnomaly;
    });
    items = [...items].sort((a, b) => {
      const aVal = a[sortKey] as number | string;
      const bVal = b[sortKey] as number | string;
      if (sortDesc) return bVal > aVal ? 1 : -1;
      return aVal > bVal ? 1 : -1;
    });
    return items;
  }, [search, filterGrade, showAnomaly, sortKey, sortDesc, queue]);

  const toggleSort = (key: SortKey) => {
    if (sortKey === key) setSortDesc(!sortDesc);
    else { setSortKey(key); setSortDesc(true); }
  };

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

  const submitDecision = () => {
    if (!decisionModal) return;
    const { id, action } = decisionModal;
    const actionLabels: Record<string, string> = { approve: t('admin.audit.approved'), return: t('admin.audit.returned'), reject: t('admin.audit.rejected') };
    setQueue(prev => prev.filter(r => r.id !== id));
    toast.success(t('admin.audit.recordDecision', { action: actionLabels[action] }));
    setDecisionModal(null);
    setExpandedId(null);
  };

  const bulkApprove = () => {
    setQueue(prev => prev.filter(r => !selected.has(r.id)));
    toast.success(t('admin.audit.batchApproved', { count: selected.size }));
    setSelected(new Set());
  };

  const priorityColors = { high: 'bg-red-500', normal: 'bg-yellow-500', low: 'bg-green-500' };
  const priorityLabels = { high: t('admin.audit.priorityHigh'), normal: t('admin.audit.priorityNormal'), low: t('admin.audit.priorityLow') };

  return (
    <div className="p-4 lg:p-6 space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-foreground">{t('admin.audit.title')}</h1>
          <p className="text-sm text-muted-foreground">{t('admin.audit.pendingCount', { count: queue.length })}</p>
        </div>
        {selected.size > 0 && (
          <button onClick={bulkApprove}
            className="flex items-center gap-1.5 px-4 py-2 bg-green-600 text-white rounded-xl text-sm hover:bg-green-700 transition-colors">
            <CheckCircle className="w-4 h-4" />
            {t('admin.audit.batchApprove')} ({selected.size})
          </button>
        )}
      </div>

      {/* Filters row */}
      <div className="flex flex-wrap gap-2">
        <div className="relative flex-1 min-w-0">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground" />
          <input type="text" value={search} onChange={e => setSearch(e.target.value)} placeholder={t('admin.audit.searchPlaceholder')}
            className="w-full pl-9 pr-4 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground placeholder:text-muted-foreground text-sm focus:outline-none focus:ring-2 focus:ring-primary/50" />
        </div>
        <select value={filterGrade} onChange={e => setFilterGrade(e.target.value)}
          className="px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none">
          <option value="all">{t('admin.audit.allGrades')}</option>
          {grades.map(g => <option key={g} value={g}>{t(GRADE_LABEL_KEYS[g])}</option>)}
        </select>
        <button
          onClick={() => setShowAnomaly(!showAnomaly)}
          className={`flex items-center gap-1.5 px-3 py-2.5 rounded-xl border text-sm transition-colors ${
            showAnomaly ? 'bg-red-100 dark:bg-red-900/30 border-red-300 text-red-700 dark:text-red-400' : 'border-border text-muted-foreground hover:bg-accent'
          }`}
        >
          <AlertTriangle className="w-4 h-4" />
          {t('admin.audit.onlyAnomalies')}
        </button>
      </div>

      {/* Sort controls */}
      <div className="flex gap-2 overflow-x-auto">
        {[
          { key: 'daysWaiting' as SortKey, label: t('admin.audit.sortDaysWaiting') },
          { key: 'submittedAt' as SortKey, label: t('admin.audit.sortSubmitted') },
          { key: 'durationMinutes' as SortKey, label: t('admin.audit.sortServiceHours') },
        ].map(({ key, label }) => (
          <button
            key={key}
            onClick={() => toggleSort(key)}
            className={`px-3 py-1.5 rounded-lg text-xs whitespace-nowrap flex items-center gap-1 transition-colors ${
              sortKey === key ? 'bg-blue-100 text-blue-700 dark:bg-blue-500/20 dark:text-blue-300' : 'bg-muted text-muted-foreground hover:bg-accent'
            }`}
          >
            {label} {sortKey === key && (sortDesc ? '↓' : '↑')}
          </button>
        ))}
      </div>

      {/* Results */}
      <div className="text-xs text-muted-foreground">{t('admin.audit.recordsCount', { count: filtered.length })}</div>

      {/* Review items */}
      {filtered.length === 0 ? (
        <div className="text-center py-12 text-muted-foreground">
          <CheckCircle className="w-10 h-10 mx-auto mb-2 opacity-30" />
          <p className="text-sm">{t('admin.audit.queueEmpty')}</p>
        </div>
      ) : (
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
                {filtered.map(item => {
                  const isSelected = selected.has(item.id);
                  return (
                    <tr key={item.id} className={`hover:bg-muted/30 transition-colors ${isSelected ? 'bg-primary/5' : ''}`}>
                      <td className="px-4 py-3">
                        <button onClick={() => toggleSelect(item.id)}
                          className={`w-5 h-5 rounded border-2 flex items-center justify-center transition-colors ${isSelected ? 'bg-primary border-primary' : 'border-border hover:border-primary'}`}>
                          {isSelected && <span className="text-primary-foreground text-xs">✓</span>}
                        </button>
                      </td>
                      <td className="px-4 py-3">
                        <div className="flex items-center gap-2">
                          <div className={`w-2 h-2 rounded-full shrink-0 ${priorityColors[item.priority]}`} />
                          <div>
                            <div className="text-sm text-foreground">{item.studentName}</div>
                            <div className="text-xs text-muted-foreground">{t(GRADE_LABEL_KEYS[item.grade])} {item.className}</div>
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
                      <td className="px-4 py-3 text-xs text-muted-foreground">{item.durationMinutes / 60}h</td>
                      <td className="px-4 py-3">
                        <span className={`text-xs ${item.daysWaiting >= 7 ? 'text-destructive font-medium' : 'text-muted-foreground'}`}>
                          {t('admin.audit.daysWaiting', { days: item.daysWaiting })}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        <div className="flex gap-1 justify-end">
                          <button onClick={() => handleDecision(item.id, 'return')}
                            className="px-2 py-1 rounded-lg border border-border text-xs text-muted-foreground hover:bg-orange-50 hover:text-orange-600 hover:border-orange-300 dark:hover:bg-orange-900/20 transition-colors flex items-center gap-1">
                            <RotateCcw className="w-3 h-3" /> {t('admin.audit.return')}
                          </button>
                          <button onClick={() => handleDecision(item.id, 'reject')}
                            className="px-2 py-1 rounded-lg border border-border text-xs text-muted-foreground hover:bg-red-50 hover:text-red-600 hover:border-red-300 dark:hover:bg-red-900/20 transition-colors flex items-center gap-1">
                            <XCircle className="w-3 h-3" /> {t('admin.audit.reject')}
                          </button>
                          <button onClick={() => handleDecision(item.id, 'approve')}
                            className="px-2 py-1 rounded-lg border border-border text-xs text-muted-foreground hover:bg-green-50 hover:text-green-600 hover:border-green-300 dark:hover:bg-green-900/20 transition-colors flex items-center gap-1">
                            <CheckCircle className="w-3 h-3" /> {t('admin.audit.approve')}
                          </button>
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>

          {/* Mobile: card view */}
          <div className="lg:hidden space-y-2">
            {filtered.map(item => {
              const isExpanded = expandedId === item.id;
              const isSelected = selected.has(item.id);
              return (
                <div key={item.id} className={`bg-card border rounded-2xl overflow-hidden transition-colors ${isSelected ? 'border-primary/50 bg-primary/5' : 'border-border'}`}>
                  <div className="p-4">
                    <div className="flex items-start gap-3">
                      <button onClick={() => toggleSelect(item.id)}
                        className={`mt-0.5 w-5 h-5 rounded border-2 shrink-0 flex items-center justify-center transition-colors ${isSelected ? 'bg-primary border-primary' : 'border-border hover:border-primary'}`}>
                        {isSelected && <span className="text-primary-foreground text-xs">✓</span>}
                      </button>
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2 flex-wrap mb-1">
                          <div className={`w-2 h-2 rounded-full shrink-0 ${priorityColors[item.priority]}`} />
                          <span className="text-sm text-foreground font-medium">{item.studentName}</span>
                          <span className="text-xs text-muted-foreground">{t(GRADE_LABEL_KEYS[item.grade])} {item.className}</span>
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
                          {item.projectTitle || item.organizationName} · {item.durationMinutes / 60}h · {item.serviceDate}
                        </div>
                        {item.hasAnomaly && item.anomalyNote && (
                          <div className="bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-lg px-3 py-2 mb-2">
                            <div className="text-xs text-red-700 dark:text-red-300">{item.anomalyNote}</div>
                          </div>
                        )}
                        {isExpanded && (
                          <div className="border-t border-border pt-3 mb-3 space-y-2 text-xs text-muted-foreground">
                            <p>{t('admin.audit.serviceDate')}: {item.serviceDate}</p>
                            <p>{t('admin.audit.reportedHours')}: {item.durationMinutes / 60} {t('admin.audit.hoursUnit')}</p>
                            <p>{t('admin.audit.organization')}: {item.organizationName}</p>
                            <p>{t('admin.audit.submitted')}: {item.submittedAt.slice(0, 16).replace('T', ' ')}</p>
                            <div className="flex gap-2 flex-wrap mt-2">
                              <span className="px-2 py-1 bg-muted rounded text-foreground">📄 {t('admin.audit.serviceConfirmation')}</span>
                              <span className="px-2 py-1 bg-muted rounded text-foreground">🖼 {t('admin.audit.activityPhotos')}</span>
                            </div>
                          </div>
                        )}
                        <div className="flex items-center justify-between">
                          <button onClick={() => setExpandedId(isExpanded ? null : item.id)}
                            className="text-xs text-muted-foreground hover:text-foreground flex items-center gap-1">
                            {isExpanded ? <><ChevronUp className="w-3 h-3" /> {t('admin.audit.collapse')}</> : <><ChevronDown className="w-3 h-3" /> {t('admin.audit.viewMaterials')}</>}
                          </button>
                          <div className="flex gap-2">
                            <button onClick={() => handleDecision(item.id, 'return')}
                              className="px-3 py-1.5 rounded-lg border border-border text-xs text-muted-foreground hover:bg-orange-50 hover:text-orange-600 hover:border-orange-300 dark:hover:bg-orange-900/20 transition-colors flex items-center gap-1">
                              <RotateCcw className="w-3 h-3" /> {t('admin.audit.return')}
                            </button>
                            <button onClick={() => handleDecision(item.id, 'reject')}
                              className="px-3 py-1.5 rounded-lg border border-border text-xs text-muted-foreground hover:bg-red-50 hover:text-red-600 hover:border-red-300 dark:hover:bg-red-900/20 transition-colors flex items-center gap-1">
                              <XCircle className="w-3 h-3" /> {t('admin.audit.reject')}
                            </button>
                            <button onClick={() => handleDecision(item.id, 'approve')}
                              className="px-3 py-1.5 rounded-lg border border-border text-xs text-muted-foreground hover:bg-green-50 hover:text-green-600 hover:border-green-300 dark:hover:bg-green-900/20 transition-colors flex items-center gap-1">
                              <CheckCircle className="w-3 h-3" /> {t('admin.audit.approve')}
                            </button>
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        </>
      )}

      {/* Decision modal */}
      {decisionModal && (
        <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center">
          <div className="absolute inset-0 bg-foreground/20 backdrop-blur-sm" onClick={() => setDecisionModal(null)} />
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
                placeholder={decisionModal.action === 'approve' ? t('admin.audit.placeholderApprove') : t('admin.audit.placeholderReject')}
                className="w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground placeholder:text-muted-foreground text-sm focus:outline-none focus:ring-2 focus:ring-primary/50 font-mono resize-none overflow-y-auto whitespace-pre-wrap"
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
