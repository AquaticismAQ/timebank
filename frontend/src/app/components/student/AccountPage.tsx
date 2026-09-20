import { useState, useEffect, useCallback } from 'react';
import { ArrowUpRight, ArrowDownLeft, Lock, ChevronDown, ChevronUp, Gift, Building2 } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { TRANSACTION_TYPE_LABELS, RECOGNITION_LEVELS } from '../../lib/statusLabels';
import { type Account, type AccountTransaction } from '../../api/types';
import { formatCoins } from '../../lib/format';
import { api } from '../../api/client';
import { useAuth } from '../../App';
import { usePagedApi } from '../../api/useApi';
import type { PageResult } from '../../api/http';
import type { ListParams } from '../../lib/pagination';
import { PageControls } from '../PageControls';
import { ListStateBanner } from '../ListStateBanner';
import { isoToLocalDate } from '../../lib/datetime';

/**
 * The API has no `description` field — `reasonCode` (plus an optional admin
 * `memo`) stands in for it (`api/client.ts`'s `transactions`). Rendering the
 * raw code showed students literal "SERVICE_APPROVED" in their ledger, so
 * known codes get a translated label here and anything unrecognised falls
 * back to the raw code rather than to a blank cell.
 */
// list-conventions: no-search — `/accounts/{id}/transactions` takes no search parameter, and a
// ledger is read by date rather than by name.

function useTxDescription() {
  const { t } = useTranslation();
  return (description: string) => {
    const [code, ...rest] = description.split(': ');
    const label = t(`student.account.reason.${code}`, { defaultValue: code });
    return rest.length ? `${label}: ${rest.join(': ')}` : label;
  };
}

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

export function AccountPage() {
  const { t } = useTranslation();
  const txDescription = useTxDescription();
  const { user } = useAuth();
  const [loadedAccount, setAccount] = useState<Account | null>(null);
  const [loadingAccount, setLoadingAccount] = useState(true);
  const [expandedId, setExpandedId] = useState<string | null>(null);

  // `getOrNull`, not `get`: a student with no approved record yet has no
  // account row, which is a zero balance rather than a failure (issue 099).
  // `loadingAccount` is tracked separately so the guard below can tell "still
  // fetching" from "fetched, and there is nothing" — when the two were the
  // same flag, a permanent null looked exactly like a slow network.
  useEffect(() => {
    let cancelled = false;
    setLoadingAccount(true);
    api.accounts.getOrNull(user?.studentId || 'std_001')
      .then(a => { if (!cancelled) setAccount(a); })
      .catch(() => { if (!cancelled) setAccount(null); })
      .finally(() => { if (!cancelled) setLoadingAccount(false); });
    return () => { cancelled = true; };
  }, [user?.studentId]);

  const accountId = loadedAccount?.id;

  const fetchTransactions = useCallback(
    (params: ListParams) => {
      if (!accountId) {
        return Promise.resolve<PageResult<AccountTransaction>>({
          data: [],
          page: { number: params?.page ?? 1, size: params?.pageSize ?? 20, totalItems: 0, totalPages: 1 },
        });
      }
      return api.accounts.transactions(accountId, params);
    },
    [accountId]
  );

  const { items: transactions, page, params, loading, error, setPage, setPageSize, refetch } = usePagedApi(fetchTransactions, {});

  // The transactions fetch depends on `accountId`, which only becomes known
  // after api.accounts.get() resolves — re-trigger once it does (the hook's
  // own effect only re-runs on params/tick change, not on `fn` identity).
  useEffect(() => {
    if (accountId) refetch();
  }, [accountId, refetch]);

  if (loadingAccount) return <div className="p-4 lg:p-6 text-muted-foreground">{t('common.loading')}</div>;

  // No row means no transactions means zero — the page renders normally and
  // shows the student a balance of 0, which is the true answer.
  const account = loadedAccount ?? api.accounts.zeroAccount(user?.studentId ?? '');

  const totalEarnedCoins = account.totalEarned / 100;
  const currentLevel = RECOGNITION_LEVELS.find((l, i) =>
    totalEarnedCoins >= l.minHours && totalEarnedCoins < (RECOGNITION_LEVELS[i + 1]?.minHours ?? Infinity)
  ) || RECOGNITION_LEVELS[0];
  const nextLevel = RECOGNITION_LEVELS.find(l => l.minHours > totalEarnedCoins);

  return (
    <div className="p-4 lg:p-6">
      <h2 className="text-foreground mb-5">{t('student.account.title')}</h2>

      {/* ── Desktop: 2-column ───────────────────────────────────────────── */}
      <div className="lg:grid lg:grid-cols-5 lg:gap-6">

        {/* Left — balance + level */}
        <div className="lg:col-span-2 space-y-4">

          {/* Balance card */}
          <div className="bg-gradient-to-br from-blue-50 to-blue-100 border border-blue-200 dark:border-transparent dark:from-[oklch(0.488_0.243_264.376)] dark:to-[oklch(0.488_0.243_264.376)]/75 rounded-2xl p-6 text-blue-900 dark:text-white">
            <div className="text-sm text-blue-500 dark:text-white/60 mb-1">{t('student.account.currentBalance')}</div>
            <div className="flex items-end gap-2 mb-5">
              <span className="text-5xl font-bold">{formatCoins(account.balance)}</span>
              <span className="text-lg text-blue-400 dark:text-white/60 mb-1">{t('student.account.coinsBalance')}</span>
            </div>
            <div className="grid grid-cols-3 gap-2 text-center">
              <div className="bg-blue-200/50 dark:bg-white/15 rounded-xl p-2">
                <div className="text-xl font-semibold">{formatCoins(account.totalEarned)}</div>
                <div className="text-xs text-blue-500 dark:text-white/60">{t('student.account.totalEarned')}</div>
              </div>
              <div className="bg-blue-200/50 dark:bg-white/15 rounded-xl p-2">
                <div className="text-xl font-semibold">{formatCoins(account.totalRedeemed)}</div>
                <div className="text-xs text-blue-500 dark:text-white/60">{t('student.account.exchanged')}</div>
              </div>
              <div className="bg-blue-200/50 dark:bg-white/15 rounded-xl p-2">
                <div className="text-xl font-semibold">{formatCoins(account.frozen)}</div>
                <div className="text-xs text-blue-500 dark:text-white/60">{t('student.account.frozen')}</div>
              </div>
            </div>
          </div>

          {/* Recognition level */}
          <div className="bg-card border border-border rounded-2xl p-4">
            <div className="flex items-center justify-between mb-3">
              <h3 className="text-sm font-medium text-foreground">{t('student.account.honorLevel')}</h3>
              {nextLevel && (
                <div className="text-xs text-muted-foreground">{nextLevel.minHours - totalEarnedCoins} {t('student.account.distanceTo')}{t(nextLevel.label)}</div>
              )}
            </div>
            <div className="flex items-center gap-3 mb-4">
              <span className="text-3xl">{currentLevel.icon}</span>
              <div>
                <div className="text-foreground font-medium">{t(currentLevel.label)}</div>
                <div className="text-xs text-muted-foreground">{t('student.account.totalEarnedLabel')}: {formatCoins(account.totalEarned)} {t('common.coins')}</div>
              </div>
            </div>
            <div className="space-y-2">
              {RECOGNITION_LEVELS.slice(1).map(level => {
                const prog = Math.min(totalEarnedCoins / level.minHours, 1);
                const reached = totalEarnedCoins >= level.minHours;
                return (
                  <div key={level.level} className="flex items-center gap-3">
                    <span className="text-lg w-6 text-center shrink-0">{level.icon}</span>
                    <div className="flex-1">
                      <div className="flex items-center justify-between text-xs mb-0.5">
                        <span className={reached ? 'text-foreground' : 'text-muted-foreground'}>{t(level.label)}</span>
                        <span className="text-muted-foreground">{level.minHours}h</span>
                      </div>
                      <div className="h-1.5 bg-muted rounded-full overflow-hidden">
                        <div className={`h-full rounded-full transition-all ${reached ? 'bg-primary' : 'bg-primary/40'}`} style={{ width: `${prog * 100}%` }} />
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          </div>

          {/* Feature gates */}
          <div className="grid grid-cols-2 gap-3">
            {[
              { label: t('student.account.exchangeCenter'), desc: t('student.account.exchangeDesc'), icon: Gift },
              { label: t('student.account.loanApplication'), desc: t('student.account.loanDesc'), icon: Building2 },
            ].map(item => (
              <div key={item.label} className="bg-muted/50 border border-border/50 rounded-xl p-4 opacity-60">
                <item.icon className="w-5 h-5 text-muted-foreground mb-1.5" />
                <div className="text-sm text-foreground">{item.label}</div>
                <div className="text-xs text-muted-foreground mt-0.5">{item.desc}</div>
                <div className="mt-2 text-xs bg-muted text-muted-foreground px-2 py-1 rounded-md inline-block">{t('student.account.notAvailable')}</div>
              </div>
            ))}
          </div>
        </div>

        {/* Right — transaction list */}
        <div className="lg:col-span-3 mt-4 lg:mt-0">
          <div className="bg-card border border-border rounded-2xl overflow-hidden">
            <div className="px-4 py-3 border-b border-border">
              <h3 className="text-sm font-medium text-foreground">{t('student.account.transactions')}</h3>
            </div>

            <ListStateBanner error={error} empty={!loading && transactions.length === 0} emptyLabel={t('student.account.noTransactions')} />

            {transactions.length > 0 && (
              <>
                {/* Desktop table view */}
                <table className="hidden lg:table w-full text-sm">
                  <thead className="bg-muted/50">
                    <tr>
                      <th className="text-left px-4 py-2.5 text-xs text-muted-foreground font-medium">{t('student.account.type')}</th>
                      <th className="text-left px-4 py-2.5 text-xs text-muted-foreground font-medium">{t('student.account.description')}</th>
                      <th className="text-left px-4 py-2.5 text-xs text-muted-foreground font-medium">{t('student.account.time')}</th>
                      <th className="text-right px-4 py-2.5 text-xs text-muted-foreground font-medium">{t('student.account.change')}</th>
                      <th className="text-right px-4 py-2.5 text-xs text-muted-foreground font-medium">{t('student.account.balance')}</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-border">
                    {transactions.map(tx => {
                      const typeInfo = TRANSACTION_TYPE_LABELS[tx.type];
                      return (
                        <tr key={tx.id} className="hover:bg-muted/30 transition-colors">
                          <td className="px-4 py-3">
                            <div className="flex items-center gap-2">
                              <div className={`w-7 h-7 rounded-full flex items-center justify-center shrink-0 ${txBg[typeInfo.color]}`}>
                                {typeInfo.sign === '+' ? <ArrowUpRight className={`w-4 h-4 ${colorMap[typeInfo.color]}`} /> :
                                 typeInfo.sign === '-' ? <ArrowDownLeft className={`w-4 h-4 ${colorMap[typeInfo.color]}`} /> :
                                 <Lock className={`w-4 h-4 ${colorMap[typeInfo.color]}`} />}
                              </div>
                              <span className="text-xs text-foreground">{t(typeInfo.label)}</span>
                            </div>
                          </td>
                          <td className="px-4 py-3 text-xs text-muted-foreground max-w-xs truncate">
                            {tx.relatedRecordTitle || txDescription(tx.description)}
                          </td>
                          <td className="px-4 py-3 text-xs text-muted-foreground">{isoToLocalDate(tx.createdAt)}</td>
                          <td className={`px-4 py-3 text-right font-medium ${colorMap[typeInfo.color]}`}>
                            {typeInfo.sign}{formatCoins(tx.amount)}
                          </td>
                          <td className="px-4 py-3 text-right text-xs text-muted-foreground">{formatCoins(tx.balanceAfter)}</td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>

                {/* Mobile: expandable cards */}
                <div className="lg:hidden divide-y divide-border">
                  {transactions.map(tx => {
                    const typeInfo = TRANSACTION_TYPE_LABELS[tx.type];
                    const isExpanded = expandedId === tx.id;
                    return (
                      <div key={tx.id}>
                        <button className="w-full flex items-center gap-3 p-4 text-left"
                          onClick={() => setExpandedId(isExpanded ? null : tx.id)}>
                          <div className={`w-10 h-10 rounded-full flex items-center justify-center shrink-0 ${txBg[typeInfo.color]}`}>
                            {typeInfo.sign === '+' ? <ArrowUpRight className={`w-5 h-5 ${colorMap[typeInfo.color]}`} /> :
                             typeInfo.sign === '-' ? <ArrowDownLeft className={`w-5 h-5 ${colorMap[typeInfo.color]}`} /> :
                             <Lock className={`w-5 h-5 ${colorMap[typeInfo.color]}`} />}
                          </div>
                          <div className="flex-1 min-w-0">
                            <div className="flex items-center justify-between">
                              <span className="text-sm text-foreground">{t(typeInfo.label)}</span>
                              <span className={`font-semibold ${colorMap[typeInfo.color]}`}>{typeInfo.sign}{formatCoins(tx.amount)}</span>
                            </div>
                            <div className="text-xs text-muted-foreground truncate">{tx.relatedRecordTitle || txDescription(tx.description)}</div>
                            <div className="text-xs text-muted-foreground">{isoToLocalDate(tx.createdAt)}</div>
                          </div>
                          {isExpanded ? <ChevronUp className="w-4 h-4 text-muted-foreground shrink-0" /> : <ChevronDown className="w-4 h-4 text-muted-foreground shrink-0" />}
                        </button>
                        {isExpanded && (
                          <div className="px-4 pb-4 border-t border-border pt-3 space-y-2 text-xs text-muted-foreground">
                            <div className="flex justify-between"><span>{t('student.account.balanceBefore')}</span><span className="text-foreground">{formatCoins(tx.balanceBefore)} {t('common.coins')}</span></div>
                            <div className="flex justify-between"><span>{t('student.account.balanceAfter')}</span><span className="text-foreground">{formatCoins(tx.balanceAfter)} {t('common.coins')}</span></div>
                            <div className="flex justify-between"><span>{t('student.account.operator')}</span><span className="text-foreground">{tx.operatorName}</span></div>
                            <div><span>{t('student.account.description')}: </span><span className="text-foreground">{txDescription(tx.description)}</span></div>
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>
              </>
            )}

            <div className="px-4 pb-4">
              {/* The page-size control every other paginated list carries
                  (issue 141). A ledger is the list most likely to be long,
                  and it was the one offering no way to see more of it at
                  once. Options come from `lib/pagination.ts`, so this one
                  cannot offer a different set from the rest. */}
              <PageControls page={page} onPageChange={setPage}
                pageSize={params.pageSize ?? 20} onPageSizeChange={setPageSize} />
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
