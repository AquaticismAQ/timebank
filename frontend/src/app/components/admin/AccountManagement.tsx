import { useState, useEffect, useMemo, useRef, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { Search, ArrowUpRight, ArrowDownLeft, Plus, ChevronDown, ChevronUp } from 'lucide-react';
import { TRANSACTION_TYPE_LABELS } from '../../lib/statusLabels';
import { type Student, type Account, type AccountTransaction } from '../../api/types';
import { formatCoins, minutesToHours, formatGradeClass, formatClassName } from '../../lib/format';
import { toast } from 'sonner';
import { api } from '../../api/client';
import { ApiError, mapApiError, newIdempotencyKey } from '../../api/http';
import { usePermissions, useAuth } from '../../App';
import { usePagedApi } from '../../api/useApi';
import { useDictionaries } from '../../api/useDictionaries';
import { PageControls } from '../PageControls';
import { ListStateBanner } from '../ListStateBanner';
import { SearchPicker } from '../SearchPicker';
import { isoToLocalDate } from '../../lib/datetime';

export function AccountManagement() {
  const { t } = useTranslation();
  const { hasPermission } = usePermissions();
  const { user } = useAuth();
  const canManage = hasPermission('accounts.adjust');
  const { items, page, loading, error, params, setPage, setPageSize, setSearch: setApiSearch, setFilter, refetch } = usePagedApi<Student>(api.students.list, {});
  const { grades, classes } = useDictionaries();
  const [search, setSearch] = useState('');
  const [filterGrade, setFilterGrade] = useState('all');
  const [filterClass, setFilterClass] = useState('all');
  const [expandedId, setExpandedId] = useState<string | null>(null);

  const handleGradeChange = (value: string) => {
    setFilterGrade(value);
    setFilter('gradeId', value === 'all' ? undefined : value);
    setFilterClass('all');
    setFilter('classId', undefined);
  };

  const handleClassChange = (value: string) => {
    setFilterClass(value);
    setFilter('classId', value === 'all' ? undefined : value);
  };

  const availableGrades = useMemo(() => {
    if (user && user.roles.includes('teacher') && !user.roles.includes('admin') && user.scopeGradeIds.length > 0) {
      return grades.filter(g => user.scopeGradeIds.includes(g.id));
    }
    return grades;
  }, [grades, user]);

  const availableClasses = useMemo(
    () => classes.filter(c => filterGrade === 'all' || c.gradeId === filterGrade),
    [classes, filterGrade],
  );

  const [balanceMap, setBalanceMap] = useState<Record<string, number>>({});
  const enrichWithBalances = useCallback(async (students: Student[]) => {
    try {
      const accountPage = await api.accounts.list({ pageSize: 200 });
      const map: Record<string, number> = {};
      for (const acct of accountPage.data) {
        map[acct.studentId] = acct.balance;
      }
      setBalanceMap(map);
    } catch { /* accounts may not be accessible, fallback to 0 */ }
  }, []);

  useEffect(() => {
    if (items.length > 0) enrichWithBalances(items);
  }, [items, enrichWithBalances]);
  const [showAdjust, setShowAdjust] = useState(false);
  const [adjustStudentId, setAdjustStudentId] = useState('');
  const [adjustStudent, setAdjustStudent] = useState('');
  const [adjustAmount, setAdjustAmount] = useState('');
  const [adjustReason, setAdjustReason] = useState('');
  const [adjustNote, setAdjustNote] = useState('');
  const [adjustSaving, setAdjustSaving] = useState(false);
  const adjustKeyRef = useRef(newIdempotencyKey());

  // Per-row transaction preview — there's no bulk "all accounts' transactions" endpoint on the real
  // backend, so recent transactions are fetched lazily for one student at a time, only when its row
  // is expanded (resolve accountId via api.accounts.get, then a small pageSize:10 preview fetch).
  const [expandedTx, setExpandedTx] = useState<AccountTransaction[]>([]);
  const [expandedTxLoading, setExpandedTxLoading] = useState(false);
  const [expandedAccount, setExpandedAccount] = useState<Account | null>(null);

  useEffect(() => {
    if (!expandedId) {
      setExpandedTx([]);
      setExpandedAccount(null);
      return;
    }
    let cancelled = false;
    setExpandedTxLoading(true);
    api.accounts.get(expandedId)
      .then(account => {
        if (!cancelled) setExpandedAccount(account);
        return api.accounts.transactions(account.id, { pageSize: 10 });
      })
      .then(result => { if (!cancelled) setExpandedTx(result.data); })
      .catch(() => { if (!cancelled) { setExpandedTx([]); setExpandedAccount(null); } })
      .finally(() => { if (!cancelled) setExpandedTxLoading(false); });
    return () => { cancelled = true; };
  }, [expandedId]);

  const handleSearchChange = (value: string) => {
    setSearch(value);
    setApiSearch(value);
  };

  const getBalance = useCallback((s: Student) => balanceMap[s.id] ?? s.timeCoinBalance ?? 0, [balanceMap]);

  const filtered = useMemo(() => {
    return [...items].sort((a, b) => getBalance(b) - getBalance(a));
  }, [items, getBalance]);

  const colorMap: Record<string, string> = {
    green: 'text-green-600 dark:text-green-400',
    orange: 'text-orange-600 dark:text-orange-400',
    blue: 'text-blue-600 dark:text-blue-400',
    yellow: 'text-yellow-600 dark:text-yellow-400',
    red: 'text-red-600 dark:text-red-400',
    purple: 'text-purple-600 dark:text-purple-400',
  };

  const txBg: Record<string, string> = {
    green: 'bg-green-100 dark:bg-green-900/30',
    orange: 'bg-orange-100 dark:bg-orange-900/30',
    blue: 'bg-blue-100 dark:bg-blue-900/30',
    yellow: 'bg-yellow-100 dark:bg-yellow-900/30',
    red: 'bg-red-100 dark:bg-red-900/30',
    purple: 'bg-purple-100 dark:bg-purple-900/30',
  };

  return (
    <div className="p-4 lg:p-6 space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-foreground">{t('admin.accounts.title')}</h1>
          <p className="text-sm text-muted-foreground">{t('admin.accounts.subtitle')}</p>
        </div>
        <button onClick={() => { setAdjustStudentId(''); setAdjustStudent(''); setAdjustAmount(''); setAdjustReason(''); setAdjustNote(''); setShowAdjust(true); }}
          className="flex items-center gap-1.5 px-4 py-2 bg-[oklch(0.488_0.243_264.376)] text-white rounded-xl text-sm hover:opacity-90">
          <Plus className="w-4 h-4" />
          {t('admin.accounts.manualAdjust')}
        </button>
      </div>

      {/* Summary — computed from the current page only; there's no aggregate-stats endpoint and the
          real /students list is paginated, not fetched in full (same caveat as StudentList.tsx). */}
      <div className="grid grid-cols-3 gap-3">
        <div className="bg-card border border-border rounded-xl p-4 text-center">
          <div className="text-2xl font-semibold text-foreground">
            {formatCoins(items.reduce((s, st) => s + getBalance(st), 0))}
          </div>
          <div className="text-xs text-muted-foreground">{t('admin.accounts.totalCirculating')}</div>
        </div>
        <div className="bg-card border border-border rounded-xl p-4 text-center">
          <div className="text-2xl font-semibold text-foreground">
            {items.filter(s => getBalance(s) > 0).length}
          </div>
          <div className="text-xs text-muted-foreground">{t('admin.accounts.accountsWithBalance')}</div>
        </div>
        <div className="bg-card border border-border rounded-xl p-4 text-center">
          <div className="text-2xl font-semibold text-foreground">
            {items.length > 0
              ? formatCoins(Math.round(items.reduce((s, st) => s + getBalance(st), 0) / items.length))
              : formatCoins(0)}
          </div>
          <div className="text-xs text-muted-foreground">{t('admin.accounts.avgBalance')}</div>
        </div>
      </div>

      {/* Search + grade/class filters */}
      <div className="flex flex-wrap gap-2">
        <div className="relative flex-1 min-w-40">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground" />
          <input type="text" value={search} onChange={e => handleSearchChange(e.target.value)} placeholder={t('admin.accounts.searchPlaceholder')}
            className="w-full pl-9 pr-4 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-primary/50" />
        </div>
        <select value={filterGrade} onChange={e => handleGradeChange(e.target.value)}
          className="px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none">
          <option value="all">{t('admin.students.allGrades')}</option>
          {availableGrades.map(g => <option key={g.id} value={g.id}>{g.name}</option>)}
        </select>
        <select value={filterClass} onChange={e => handleClassChange(e.target.value)}
          className="px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none">
          <option value="all">{t('admin.students.allClasses')}</option>
          {availableClasses.map(c => <option key={c.id} value={c.id}>{formatClassName(c.name)}</option>)}
        </select>
      </div>

      <ListStateBanner error={error} empty={!loading && filtered.length === 0} emptyLabel="No students" />

      {/* Account list */}
      <div className="space-y-2">
        {filtered.map(student => {
          const isExpanded = expandedId === student.id;
          const studentTxs = isExpanded ? expandedTx : [];

          return (
            <div key={student.id} className="bg-card border border-border rounded-xl overflow-hidden">
              <button className="w-full flex items-center gap-4 p-4 text-left" onClick={() => setExpandedId(isExpanded ? null : student.id)}>
                <div className="w-10 h-10 bg-accent rounded-full flex items-center justify-center shrink-0">
                  <span className="text-foreground text-sm font-medium">{student.name[0]}</span>
                </div>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2">
                    <span className="text-sm text-foreground font-medium">{student.name}</span>
                    <span className="text-xs text-muted-foreground">{formatGradeClass(student.gradeLevel, student.className)}</span>
                  </div>
                  <div className="text-xs text-muted-foreground">{t('admin.accounts.earnedCoins', { earned: formatCoins(Math.round((student.completedMinutes ?? 0) / 60 * 2 * 100)), redeemed: 0 })}</div>
                </div>
                <div className="text-right shrink-0">
                  <div className="text-lg font-semibold text-foreground">{formatCoins(getBalance(student))}</div>
                  <div className="text-xs text-muted-foreground">{t('admin.accounts.coinsUnit')}</div>
                </div>
                {isExpanded ? <ChevronUp className="w-4 h-4 text-muted-foreground" /> : <ChevronDown className="w-4 h-4 text-muted-foreground" />}
              </button>

              {isExpanded && (
                <div className="px-4 pb-4 border-t border-border pt-3 space-y-3">
                  {expandedTxLoading ? (
                    <p className="text-xs text-muted-foreground">{t('common.loading')}</p>
                  ) : studentTxs.length === 0 ? (
                    <p className="text-xs text-muted-foreground">{t('admin.accounts.noTransactions')}</p>
                  ) : (
                    studentTxs.map(tx => {
                      const typeInfo = TRANSACTION_TYPE_LABELS[tx.type];
                      return (
                        <div key={tx.id} className="flex items-center gap-3">
                          <div className={`w-8 h-8 rounded-full flex items-center justify-center shrink-0 ${txBg[typeInfo.color]}`}>
                            {typeInfo.sign === '+' ? <ArrowUpRight className={`w-4 h-4 ${colorMap[typeInfo.color]}`} /> : <ArrowDownLeft className={`w-4 h-4 ${colorMap[typeInfo.color]}`} />}
                          </div>
                          <div className="flex-1 min-w-0">
                            <div className="text-xs text-foreground">{t(typeInfo.label)} · {tx.relatedRecordTitle || tx.description}</div>
                            <div className="text-xs text-muted-foreground">{isoToLocalDate(tx.createdAt)}</div>
                          </div>
                          <div className={`text-sm font-medium ${colorMap[typeInfo.color]}`}>{typeInfo.sign}{formatCoins(tx.amount)}</div>
                        </div>
                      );
                    })
                  )}
                  {canManage && (
                  <div className="flex gap-2 pt-1">
                    <button onClick={() => { setAdjustStudentId(student.id); setAdjustStudent(student.name); setShowAdjust(true); }}
                      className="text-xs px-3 py-1.5 border border-border rounded-lg text-muted-foreground hover:bg-accent">
                      {t('admin.accounts.manualAdjust')}
                    </button>
                    <button onClick={async () => {
                      try {
                        const account = expandedAccount?.id ? expandedAccount : await api.accounts.get(student.id);
                        const isFrozen = expandedAccount?.frozen ? expandedAccount.frozen > 0 : false;
                        if (isFrozen) {
                          await api.accounts.unfreeze(account.id, 'ADMIN_ACTION', t('admin.accounts.unfrozenByAdmin'));
                          toast.success(t('admin.accounts.accountUnfrozen', { name: student.name }));
                        } else {
                          await api.accounts.freeze(account.id, 'ADMIN_ACTION', t('admin.accounts.frozenByAdmin'));
                          toast.success(t('admin.accounts.accountFrozen', { name: student.name }));
                        }
                        if (expandedId === student.id) setExpandedAccount(await api.accounts.get(student.id));
                      } catch (err) {
                        if (err instanceof ApiError) toast.error(mapApiError(err).message);
                        else toast.error(t('common.unknownError'));
                      }
                    }}
                      className="text-xs px-3 py-1.5 border border-border rounded-lg text-muted-foreground hover:bg-accent">
                      {expandedId === student.id && expandedAccount?.frozen ? t('admin.accounts.unfreeze') : t('admin.accounts.freeze')}
                    </button>
                  </div>
                  )}
                </div>
              )}
            </div>
          );
        })}
      </div>

      <PageControls page={page} onPageChange={setPage} pageSize={params.pageSize ?? 20} onPageSizeChange={setPageSize} />

      {/* Adjust modal */}
      {showAdjust && (
        <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center">
          <div className="absolute inset-0 bg-foreground/20 backdrop-blur-sm" onClick={() => setShowAdjust(false)} />
          <div className="relative bg-card border border-border rounded-2xl p-6 w-full max-w-md mx-4 mb-4 sm:mb-0 z-50">
            <h3 className="text-foreground mb-4">{t('admin.accounts.adjustTitle')}</h3>
            <div className="space-y-3">
              <div>
                <label className="block text-xs text-muted-foreground mb-1.5">{t('admin.accounts.student')}</label>
                {/* A real picker resolving to a studentId, not free text — Docs/issues/037-*.md:
                    the old plain <input> here never set adjustStudentId at all, so submitting
                    silently adjusted whatever account api.accounts.get('') happened to fall
                    back to, regardless of what name was typed. */}
                <SearchPicker
                  selected={adjustStudentId ? [{ id: adjustStudentId, label: adjustStudent }] : []}
                  onChange={opts => { setAdjustStudentId(opts[0]?.id ?? ''); setAdjustStudent(opts[0]?.label ?? ''); }}
                  fetchOptions={async search => (await api.students.directory(search))
                    .map(s => ({ id: s.id, label: s.displayName, sublabel: formatGradeClass(s.gradeLevel, s.className) }))}
                  placeholder={t('admin.accounts.studentPlaceholder')}
                  searchPlaceholder={t('admin.accounts.studentPlaceholder')}
                  noResultsLabel={t('student.proposals.noResults')}
                  loadingLabel={t('common.loading')}
                />
              </div>
              <div>
                <label className="block text-xs text-muted-foreground mb-1.5">{t('admin.accounts.amountLabel')}</label>
                <input type="number" value={adjustAmount} onChange={e => setAdjustAmount(e.target.value)}
                  placeholder={t('admin.accounts.amountPlaceholder')}
                  className="w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none" />
              </div>
              <div>
                <label className="block text-xs text-muted-foreground mb-1.5">{t('admin.accounts.reasonCategory')} *</label>
                <select value={adjustReason} onChange={e => setAdjustReason(e.target.value)}
                  className="w-full px-3 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none">
                  <option value="">{t('admin.accounts.selectReason')}</option>
                  <option value="CORRECTION">{t('admin.accounts.reasonCorrection')}</option>
                  <option value="SYSTEM_ERROR">{t('admin.accounts.reasonSystemError')}</option>
                  <option value="POLICY_ADJUST">{t('admin.accounts.reasonPolicyAdjust')}</option>
                  <option value="COMPENSATION">{t('admin.accounts.reasonCompensation')}</option>
                </select>
              </div>
              <div>
                <label className="block text-xs text-muted-foreground mb-1.5">{t('admin.accounts.note')} *</label>
                <textarea rows={10} value={adjustNote} onChange={e => setAdjustNote(e.target.value)}
                  placeholder={t('admin.accounts.notePlaceholder')} maxLength={1000}
                  className="w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none font-mono resize-none overflow-y-auto whitespace-pre-wrap" />
              </div>
            </div>
            <div className="flex gap-3 mt-4">
              <button onClick={() => setShowAdjust(false)} className="flex-1 py-3 rounded-xl border border-border text-foreground hover:bg-accent text-sm">{t('common.cancel')}</button>
              <button
                disabled={adjustSaving}
                onClick={async () => {
                  if (!adjustStudentId || !adjustAmount || !adjustReason || !adjustNote) {
                    toast.error(t('admin.accounts.fillAllFields'));
                    return;
                  }
                  setAdjustSaving(true);
                  try {
                    const account = await api.accounts.get(adjustStudentId);
                    const centiCoins = Math.round(Number(adjustAmount) * 100);
                    await api.accounts.adjust(account.id, centiCoins, adjustReason, adjustNote, adjustKeyRef.current);
                    adjustKeyRef.current = newIdempotencyKey();
                    toast.success(t('admin.accounts.adjustSuccess', { name: adjustStudent, action: Number(adjustAmount) > 0 ? t('admin.accounts.credited') : t('admin.accounts.deducted'), amount: Math.abs(Number(adjustAmount)) }));
                    setShowAdjust(false);
                    setAdjustStudent('');
                    setAdjustStudentId('');
                    setAdjustAmount('');
                    setAdjustReason('');
                    setAdjustNote('');
                  } catch (err) {
                    if (err instanceof ApiError) toast.error(mapApiError(err).message);
                    else toast.error(t('common.unknownError'));
                  } finally {
                    setAdjustSaving(false);
                  }
                }}
                className="flex-1 py-3 rounded-xl bg-[oklch(0.488_0.243_264.376)] text-white hover:opacity-90 text-sm disabled:opacity-50"
              >
                {t('admin.accounts.confirmAdjust')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
