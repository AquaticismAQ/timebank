import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router';
import { useTranslation } from 'react-i18next';
import { ClipboardCheck, AlertTriangle, Clock, ArrowRight, FileQuestion, Lightbulb } from 'lucide-react';
import { PROJECT_STATUS_LABELS } from '../../lib/statusLabels';
import { formatGradeClass, minutesToHours } from '../../lib/format';
import { type AdminStats, type ReviewQueueItem, type Project } from '../../api/types';
import { useAuth } from '../../App';
import { api } from '../../api/client';


function StatCard({ label, value, sub, icon: Icon, color, onClick }: {
  label: string; value: string | number; sub?: string;
  icon: typeof Clock; color: string; onClick?: () => void;
}) {
  return (
    <button
      onClick={onClick}
      className={`bg-card border border-border rounded-2xl p-4 text-left hover:bg-accent/50 transition-colors ${onClick ? 'cursor-pointer' : 'cursor-default'}`}
    >
      <div className={`w-10 h-10 rounded-xl flex items-center justify-center mb-3 ${color}`}>
        <Icon className="w-5 h-5" />
      </div>
      <div className="text-2xl font-semibold text-foreground">{value}</div>
      <div className="text-sm text-foreground mt-0.5">{label}</div>
      {sub && <div className="text-xs text-muted-foreground mt-0.5">{sub}</div>}
    </button>
  );
}

function SummaryCard({ label, value, onClick }: { label: string; value: string | number; onClick?: () => void }) {
  const Tag = onClick ? 'button' : 'div';
  return (
    <Tag
      onClick={onClick}
      className={`bg-card border border-border rounded-xl p-4 text-center ${onClick ? 'cursor-pointer hover:bg-accent/50 transition-colors' : ''}`}
    >
      <div className="text-2xl font-semibold text-foreground">{value}</div>
      <div className="text-xs text-muted-foreground mt-0.5">{label}</div>
    </Tag>
  );
}

export function AdminDashboard() {
  const navigate = useNavigate();
  const { user } = useAuth();
  const { t } = useTranslation();
  const [stats, setStats] = useState<AdminStats | null>(null);
  const [pendingReviews, setPendingReviews] = useState<ReviewQueueItem[]>([]);
  const [activeProjects, setActiveProjects] = useState<Project[]>([]);
  const [allReviews, setAllReviews] = useState<ReviewQueueItem[]>([]);
  const [dashboardLoaded, setDashboardLoaded] = useState(false);
  const [pendingCases, setPendingCases] = useState(0);
  const [pendingProposals, setPendingProposals] = useState(0);

  useEffect(() => {
    api.dashboards.admin().then(d => {
      setStats(d.stats);
      setPendingReviews(d.pendingReviews);
      setActiveProjects(d.activeProjects);
    }).catch(() => {}).finally(() => setDashboardLoaded(true));
    api.reviewQueue.list({ pageSize: 100 }).then(result => setAllReviews(result.data)).catch(() => {});
    api.cases.pendingCount().then(setPendingCases).catch(() => {});
    api.eventProposals.pendingCount().then(setPendingProposals).catch(() => {});
  }, []);

  if (!dashboardLoaded) return <div className="p-4 lg:p-6">{t('common.loading')}</div>;

  const urgentReviews = allReviews.filter(r => r.daysWaiting >= 7 || r.hasAnomaly);

  return (
    <div className="p-4 lg:p-6 space-y-6">
      {/* Header */}
      <div>
        <h1 className="text-foreground">{t('admin.dashboard.title')}</h1>
        <p className="text-muted-foreground text-sm mt-0.5">
          {t('admin.dashboard.welcome', { name: user?.displayName, date: new Date().toLocaleDateString(undefined, { month: 'long', day: 'numeric', weekday: 'long' }) })}
        </p>
      </div>

      {stats && (<>
      {/* Key stats */}
      <div className="grid grid-cols-3 gap-3">
        <StatCard label={t('admin.dashboard.pendingReviews')} value={stats.pendingReviews} sub={t('admin.dashboard.clickToReview')} icon={ClipboardCheck}
          color="bg-orange-100 dark:bg-orange-900/30 text-orange-600 dark:text-orange-400"
          onClick={() => navigate('/admin/audit')}
        />
        <StatCard label={t('admin.dashboard.pendingCases')} value={pendingCases} sub={t('admin.dashboard.clickToReview')} icon={FileQuestion}
          color="bg-red-100 dark:bg-red-900/30 text-red-600 dark:text-red-400"
          onClick={() => navigate('/admin/cases')}
        />
        <StatCard label={t('admin.dashboard.pendingProposals')} value={pendingProposals} sub={t('admin.dashboard.clickToReview')} icon={Lightbulb}
          color="bg-yellow-100 dark:bg-yellow-900/30 text-yellow-600 dark:text-yellow-400"
          onClick={() => navigate('/admin/proposals')}
        />
      </div>

      {/* Summary row */}
      <div className="grid grid-cols-2 lg:grid-cols-3 gap-3">
        <SummaryCard label={t('admin.dashboard.activeStudents')} value={stats.activeStudents} onClick={() => navigate('/admin/students')} />
        <SummaryCard label={t('admin.dashboard.approvedThisMonth')} value={stats.approvedThisMonth} />
        <SummaryCard label={t('admin.dashboard.avgReviewDays')} value={`${stats.reviewAvgDays} ${t('admin.dashboard.tableDays', { days: '' }).trim()}`} />
        <SummaryCard label={t('admin.dashboard.totalHours')} value={stats.totalServiceHours.toLocaleString()} />
        <SummaryCard label={t('admin.dashboard.completionRate')} value={stats.avgCompletionRate != null ? `${Math.round(stats.avgCompletionRate * 100)}%` : '—'} />
        <SummaryCard label={t('admin.dashboard.activeProjects')} value={stats.activeProjects} />
      </div>
      </>)}

      {/* Urgent / anomaly reviews */}
      {urgentReviews.length > 0 && (
        <div className="bg-orange-50 dark:bg-orange-900/20 border border-orange-200 dark:border-orange-800 rounded-2xl p-4">
          <div className="flex items-center gap-2 mb-3">
            <AlertTriangle className="w-4 h-4 text-orange-500" />
            <span className="text-sm text-orange-800 dark:text-orange-200 font-medium">{t('admin.dashboard.urgentTitle')}</span>
          </div>
          <div className="space-y-2">
            {urgentReviews.map(r => (
              <div key={r.id} className="flex items-center gap-2 text-sm">
                <div className="flex-1 min-w-0">
                  <span className="text-foreground">{r.studentName}</span>
                  <span className="text-muted-foreground"> · {r.organizationName} · {t('admin.dashboard.urgentWaiting', { days: r.daysWaiting })}</span>
                  {r.hasAnomaly && (
                    <span className="ml-2 text-xs bg-red-100 dark:bg-red-900/30 text-red-600 dark:text-red-400 px-1.5 py-0.5 rounded">{t('admin.dashboard.anomaly')}</span>
                  )}
                </div>
              </div>
            ))}
          </div>
          <button onClick={() => navigate('/admin/audit')} className="mt-3 text-xs text-orange-600 dark:text-orange-400 flex items-center gap-1 hover:underline">
            {t('admin.dashboard.goToAudit')} <ArrowRight className="w-3 h-3" />
          </button>
        </div>
      )}

      {/* Desktop: two-column split for queue + projects */}
      <div className="lg:grid lg:grid-cols-5 lg:gap-6">

        {/* Pending reviews preview — fall back to allReviews when dashboard endpoint is forbidden */}
        {(() => { const displayReviews = pendingReviews.length > 0 ? pendingReviews : allReviews.slice(0, 10); return (
        <div className="lg:col-span-3">
          <div className="flex items-center justify-between mb-3">
            <h3 className="text-foreground">{t('admin.dashboard.reviewQueue')}</h3>
            <button onClick={() => navigate('/admin/audit')} className="text-xs text-primary flex items-center gap-1 hover:underline">
              {t('admin.dashboard.viewAll')} <ArrowRight className="w-3 h-3" />
            </button>
          </div>

          {/* Desktop table */}
          <div className="hidden lg:block bg-card border border-border rounded-2xl overflow-hidden">
            <table className="w-full text-sm">
              <thead className="bg-muted/50 border-b border-border">
                <tr>
                  <th className="text-left px-4 py-2.5 text-xs text-muted-foreground font-medium">{t('admin.dashboard.tablePriority')}</th>
                  <th className="text-left px-4 py-2.5 text-xs text-muted-foreground font-medium">{t('admin.dashboard.tableStudent')}</th>
                  <th className="text-left px-4 py-2.5 text-xs text-muted-foreground font-medium">{t('admin.dashboard.tableOrg')}</th>
                  <th className="text-left px-4 py-2.5 text-xs text-muted-foreground font-medium">{t('admin.dashboard.tableDuration')}</th>
                  <th className="text-left px-4 py-2.5 text-xs text-muted-foreground font-medium">{t('admin.dashboard.tableWaiting')}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {displayReviews.map(r => (
                  <tr key={r.id} className="hover:bg-muted/30 transition-colors">
                    <td className="px-4 py-3">
                      <div className={`w-2 h-2 rounded-full ${r.priority === 'high' ? 'bg-red-500' : r.priority === 'medium' ? 'bg-yellow-500' : 'bg-green-500'}`} />
                    </td>
                    <td className="px-4 py-3">
                      <div className="text-sm text-foreground">{r.studentName}</div>
                      <div className="text-xs text-muted-foreground">{formatGradeClass(r.gradeLevel, r.className)}</div>
                    </td>
                    <td className="px-4 py-3 text-xs text-muted-foreground">{r.organizationName}</td>
                    <td className="px-4 py-3 text-xs text-muted-foreground">{minutesToHours(r.durationMinutes)}h</td>
                    <td className="px-4 py-3">
                      <span className={`text-xs ${r.daysWaiting >= 7 ? 'text-destructive font-medium' : 'text-muted-foreground'}`}>{t('admin.dashboard.tableDays', { days: r.daysWaiting })}</span>
                      {r.hasAnomaly && <span className="ml-1 text-xs bg-red-100 dark:bg-red-900/30 text-red-600 dark:text-red-400 px-1 rounded">{t('admin.dashboard.anomaly')}</span>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* Mobile cards */}
          <div className="lg:hidden space-y-2">
            {displayReviews.map(r => (
              <div key={r.id} className="bg-card border border-border rounded-xl p-4 flex items-center gap-3">
                <div className={`w-2 h-2 rounded-full shrink-0 ${r.priority === 'high' ? 'bg-red-500' : r.priority === 'medium' ? 'bg-yellow-500' : 'bg-green-500'}`} />
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className="text-sm text-foreground">{r.studentName}</span>
                    <span className="text-xs text-muted-foreground">{formatGradeClass(r.gradeLevel, r.className)}</span>
                    {r.hasAnomaly && <span className="text-xs bg-red-100 dark:bg-red-900/30 text-red-600 dark:text-red-400 px-1.5 py-0.5 rounded">{t('admin.dashboard.anomaly')}</span>}
                  </div>
                  <div className="text-xs text-muted-foreground">{r.organizationName} · {minutesToHours(r.durationMinutes)}h · {r.serviceDate}</div>
                </div>
                <div className="text-xs text-muted-foreground shrink-0">{t('admin.dashboard.tableDaysAgo', { days: r.daysWaiting })}</div>
              </div>
            ))}
          </div>
        </div>
        ); })()}

        {/* Active projects */}
        <div className="lg:col-span-2 mt-6 lg:mt-0">
          <div className="flex items-center justify-between mb-3">
            <h3 className="text-foreground">{t('admin.dashboard.activeProjects')}</h3>
            <button onClick={() => navigate('/admin/projects')} className="text-xs text-primary flex items-center gap-1 hover:underline">
              {t('admin.dashboard.manageProjects')} <ArrowRight className="w-3 h-3" />
            </button>
          </div>
          <div className="space-y-2">
            {activeProjects.map(proj => {
              const status = PROJECT_STATUS_LABELS[proj.status];
              return (
                <div key={proj.id} className="bg-card border border-border rounded-xl p-4">
                  <div className="flex items-center gap-2 mb-1">
                    <span className={`text-xs px-2 py-0.5 rounded-full ${
                      proj.status === 'registrationOpen' ? 'bg-green-100 dark:bg-green-900/30 text-green-700 dark:text-green-400' : 'bg-yellow-100 dark:bg-yellow-900/30 text-yellow-700 dark:text-yellow-400'
                    }`}>{t(status.label)}</span>
                  </div>
                  <div className="text-sm text-foreground mb-1">{proj.title}</div>
                  <div className="text-xs text-muted-foreground mb-2">{proj.enrolledCount}/{proj.capacity} {t('admin.dashboard.people')} · {minutesToHours(proj.recognizedMinutes)}h</div>
                  <div className="h-1.5 bg-muted rounded-full overflow-hidden">
                    <div className="h-full bg-primary rounded-full" style={{ width: `${Math.min((proj.enrolledCount / proj.capacity) * 100, 100)}%` }} />
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      </div>
    </div>
  );
}
