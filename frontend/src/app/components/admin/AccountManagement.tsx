import { useState, useEffect, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { Search, ArrowUpRight, ArrowDownLeft, Plus, ChevronDown, ChevronUp } from 'lucide-react';
import { TRANSACTION_TYPE_LABELS, type Student, type AccountTransaction } from '../../mock/data';
import { toast } from 'sonner';
import { api } from '../../api/client';

export function AccountManagement() {
  const { t } = useTranslation();
  const [students, setStudents] = useState<Student[]>([]);
  const [transactions, setTransactions] = useState<AccountTransaction[]>([]);
  const [search, setSearch] = useState('');
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [showAdjust, setShowAdjust] = useState(false);
  const [adjustStudent, setAdjustStudent] = useState('');
  const [adjustAmount, setAdjustAmount] = useState('');
  const [adjustReason, setAdjustReason] = useState('');
  const [adjustNote, setAdjustNote] = useState('');

  useEffect(() => {
    api.students.list().then(setStudents).catch(() => {});
    api.accounts.transactions('all').then(setTransactions).catch(() => {});
  }, []);

  const filtered = useMemo(() => {
    return students.filter(s =>
      !search || s.name.includes(search) || s.studentNumber.includes(search)
    ).sort((a, b) => b.timeCoinBalance - a.timeCoinBalance);
  }, [search]);

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
        <button onClick={() => setShowAdjust(true)}
          className="flex items-center gap-1.5 px-4 py-2 bg-[oklch(0.488_0.243_264.376)] text-white rounded-xl text-sm hover:opacity-90">
          <Plus className="w-4 h-4" />
          {t('admin.accounts.manualAdjust')}
        </button>
      </div>

      {/* Summary */}
      <div className="grid grid-cols-3 gap-3">
        <div className="bg-card border border-border rounded-xl p-4 text-center">
          <div className="text-2xl font-semibold text-foreground">
            {students.reduce((s, st) => s + st.timeCoinBalance, 0)}
          </div>
          <div className="text-xs text-muted-foreground">{t('admin.accounts.totalCirculating')}</div>
        </div>
        <div className="bg-card border border-border rounded-xl p-4 text-center">
          <div className="text-2xl font-semibold text-foreground">
            {students.filter(s => s.timeCoinBalance > 0).length}
          </div>
          <div className="text-xs text-muted-foreground">{t('admin.accounts.accountsWithBalance')}</div>
        </div>
        <div className="bg-card border border-border rounded-xl p-4 text-center">
          <div className="text-2xl font-semibold text-foreground">
            {Math.round(students.reduce((s, st) => s + st.timeCoinBalance, 0) / students.length * 10) / 10}
          </div>
          <div className="text-xs text-muted-foreground">{t('admin.accounts.avgBalance')}</div>
        </div>
      </div>

      {/* Search */}
      <div className="relative">
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground" />
        <input type="text" value={search} onChange={e => setSearch(e.target.value)} placeholder={t('admin.accounts.searchPlaceholder')}
          className="w-full pl-9 pr-4 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-primary/50" />
      </div>

      {/* Account list */}
      <div className="space-y-2">
        {filtered.map(student => {
          const isExpanded = expandedId === student.id;
          const studentTxs = transactions.filter(t => t.accountId === `acc_${student.id.split('_')[1]}`);

          return (
            <div key={student.id} className="bg-card border border-border rounded-xl overflow-hidden">
              <button className="w-full flex items-center gap-4 p-4 text-left" onClick={() => setExpandedId(isExpanded ? null : student.id)}>
                <div className="w-10 h-10 bg-accent rounded-full flex items-center justify-center shrink-0">
                  <span className="text-foreground text-sm font-medium">{student.name[0]}</span>
                </div>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2">
                    <span className="text-sm text-foreground font-medium">{student.name}</span>
                    <span className="text-xs text-muted-foreground">{student.grade} {student.className}</span>
                  </div>
                  <div className="text-xs text-muted-foreground">{t('admin.accounts.earnedCoins', { earned: student.completedHours * 2, redeemed: 0 })}</div>
                </div>
                <div className="text-right shrink-0">
                  <div className="text-lg font-semibold text-foreground">{student.timeCoinBalance}</div>
                  <div className="text-xs text-muted-foreground">{t('admin.accounts.coinsUnit')}</div>
                </div>
                {isExpanded ? <ChevronUp className="w-4 h-4 text-muted-foreground" /> : <ChevronDown className="w-4 h-4 text-muted-foreground" />}
              </button>

              {isExpanded && (
                <div className="px-4 pb-4 border-t border-border pt-3 space-y-3">
                  {studentTxs.length === 0 ? (
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
                            <div className="text-xs text-muted-foreground">{tx.createdAt.slice(0, 10)}</div>
                          </div>
                          <div className={`text-sm font-medium ${colorMap[typeInfo.color]}`}>{typeInfo.sign}{tx.amount}</div>
                        </div>
                      );
                    })
                  )}
                  <div className="flex gap-2 pt-1">
                    <button onClick={() => { setAdjustStudent(student.name); setShowAdjust(true); }}
                      className="text-xs px-3 py-1.5 border border-border rounded-lg text-muted-foreground hover:bg-accent">
                      {t('admin.accounts.manualAdjust')}
                    </button>
                    <button onClick={() => toast.info(t('admin.accounts.freezeComingSoon'))}
                      className="text-xs px-3 py-1.5 border border-border rounded-lg text-muted-foreground hover:bg-accent">
                      {t('admin.accounts.freeze')}
                    </button>
                  </div>
                </div>
              )}
            </div>
          );
        })}
      </div>

      {/* Adjust modal */}
      {showAdjust && (
        <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center">
          <div className="absolute inset-0 bg-foreground/20 backdrop-blur-sm" onClick={() => setShowAdjust(false)} />
          <div className="relative bg-card border border-border rounded-2xl p-6 w-full max-w-md mx-4 mb-4 sm:mb-0 z-50">
            <h3 className="text-foreground mb-4">{t('admin.accounts.adjustTitle')}</h3>
            <div className="space-y-3">
              <div>
                <label className="block text-xs text-muted-foreground mb-1.5">{t('admin.accounts.student')}</label>
                <input type="text" value={adjustStudent} onChange={e => setAdjustStudent(e.target.value)}
                  placeholder={t('admin.accounts.studentPlaceholder')}
                  className="w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none" />
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
                  placeholder={t('admin.accounts.notePlaceholder')}
                  className="w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none font-mono resize-none overflow-y-auto whitespace-pre-wrap" />
              </div>
            </div>
            <div className="flex gap-3 mt-4">
              <button onClick={() => setShowAdjust(false)} className="flex-1 py-3 rounded-xl border border-border text-foreground hover:bg-accent text-sm">{t('common.cancel')}</button>
              <button
                onClick={() => {
                  if (!adjustStudent || !adjustAmount || !adjustReason || !adjustNote) {
                    toast.error(t('admin.accounts.fillAllFields'));
                    return;
                  }
                  toast.success(t('admin.accounts.adjustSuccess', { name: adjustStudent, action: Number(adjustAmount) > 0 ? t('admin.accounts.credited') : t('admin.accounts.deducted'), amount: Math.abs(Number(adjustAmount)) }));
                  setShowAdjust(false);
                  setAdjustStudent('');
                  setAdjustAmount('');
                  setAdjustReason('');
                  setAdjustNote('');
                }}
                className="flex-1 py-3 rounded-xl bg-[oklch(0.488_0.243_264.376)] text-white hover:opacity-90 text-sm"
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
