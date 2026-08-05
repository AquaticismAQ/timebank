import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router';
import { useTranslation } from 'react-i18next';
import { ClipboardCheck, Users, AlertTriangle, Clock, CheckCircle, ArrowRight } from 'lucide-react';
import { PROJECT_STATUS_LABELS } from '../../mock/data';
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

export function AdminDashboard() {
  const navigate = useNavigate();
  const { user } = useAuth();
  const { t } = useTranslation();
  const [stats, setStats] = useState<AdminStats | null>(null);
  const [pendingReviews, setPendingReviews] = useState<ReviewQueueItem[]>([]);
  const [activeProjects, setActiveProjects] = useState<Project[]>([]);
  const [allReviews, setAllReviews] = useState<ReviewQueueItem[]>([]);

  useEffect(() => {
    api.dashboards.admin().then(d => {
      setStats(d.stats);
      setPendingReviews(d.pendingReviews);
      setActiveProjects(d.activeProjects);
    }).catch(() => {});
    api.reviewQueue.list({ pageSize: 100 }).then(result => setAllReviews(result.data)).catch(() => {});
  }, []);

  if (!stats) return <div className="p-4 lg:p-6">{t('common.loading')}</div>;

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

      {/* Key stats */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
        <StatCard label={t('admin.dashboard.pendingReviews')} value={stats.pendingReviews} sub={t('admin.dashboard.clickToReview')} icon={ClipboardCheck}
          color="bg-orange-100 dark:bg-orange-900/30 text-orange-600 dark:text-orange-400"
          onClick={() => navigate('/admin/audit')}
        />
        <StatCard label={t('admin.dashboard.activeStudents')} value={stats.activeStudents} sub={t('admin.dashboard.totalStudents', { count: stats.totalStudents })} icon={Users}
          color="bg-blue-100 dark:bg-blue-900/30 text-blue-600 dark:text-blue-400"
          onClick={() => navigate('/admin/students')}
        />
        <StatCard label={t('admin.dashboard.approvedThisMonth')} value={stats.approvedThisMonth} sub={t('admin.dashboard.july')} icon={CheckCircle}
          color="bg-green-100 dark:bg-green-900/30 text-green-600 dark:text-green-400"
        />
        <StatCard label={t('admin.dashboard.avgReviewDays')} value={`${stats.reviewAvgDays} ${t('admin.dashboard.tableDays', { days: '' }).trim()}`} sub={t('admin.dashboard.avgReviewSub')} icon={Clock}
          color="bg-purple-100 dark:bg-purple-900/30 text-purple-600 dark:text-purple-400"
        />
      </div>

      {/* Summary row */}
      <div className="grid grid-cols-3 gap-3">
        <div className="bg-card border border-border rounded-xl p-4 text-center">
          <div className="text-2xl font-semibold text-foreground">{stats.totalServiceHours.toLocaleString()}</div>
          <div className="text-xs text-muted-foreground mt-0.5">{t('admin.dashboard.totalHours')}</div>
        </div>
        <div className="bg-card border border-border rounded-xl p-4 text-center">
          <div className="text-2xl font-semibold text-foreground">{stats.avgCompletionRate != null ? `${Math.round(stats.avgCompletionRate * 100)}%` : '—'}</div>
          <div className="text-xs text-muted-foreground mt-0.5">{t('admin.dashboard.completionRate')}</div>
        </div>
        <div className="bg-card border border-border rounded-xl p-4 text-center">
          <div className="text-2xl font-semibold text-foreground">{stats.activeProjects}</div>
          <div className="text-xs text-muted-foreground mt-0.5">{t('admin.dashboard.activeProjects')}</div>
        </div>
      </div>

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

        {/* Pending reviews preview */}
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
                {pendingReviews.map(r => (
                  <tr key={r.id} className="hover:bg-muted/30 transition-colors">
                    <td className="px-4 py-3">
                      <div className={`w-2 h-2 rounded-full ${r.priority === 'high' ? 'bg-red-500' : r.priority === 'medium' ? 'bg-yellow-500' : 'bg-green-500'}`} />
                    </td>
                    <td className="px-4 py-3">
                      <div className="text-sm text-foreground">{r.studentName}</div>
                      <div className="text-xs text-muted-foreground">{r.grade} {r.className}</div>
                    </td>
                    <td className="px-4 py-3 text-xs text-muted-foreground">{r.organizationName}</td>
                    <td className="px-4 py-3 text-xs text-muted-foreground">{r.durationMinutes / 60}h</td>
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
            {pendingReviews.map(r => (
              <div key={r.id} className="bg-card border border-border rounded-xl p-4 flex items-center gap-3">
                <div className={`w-2 h-2 rounded-full shrink-0 ${r.priority === 'high' ? 'bg-red-500' : r.priority === 'medium' ? 'bg-yellow-500' : 'bg-green-500'}`} />
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className="text-sm text-foreground">{r.studentName}</span>
                    <span className="text-xs text-muted-foreground">{r.grade} {r.className}</span>
                    {r.hasAnomaly && <span className="text-xs bg-red-100 dark:bg-red-900/30 text-red-600 dark:text-red-400 px-1.5 py-0.5 rounded">{t('admin.dashboard.anomaly')}</span>}
                  </div>
                  <div className="text-xs text-muted-foreground">{r.organizationName} · {r.durationMinutes / 60}h · {r.serviceDate}</div>
                </div>
                <div className="text-xs text-muted-foreground shrink-0">{t('admin.dashboard.tableDaysAgo', { days: r.daysWaiting })}</div>
              </div>
            ))}
          </div>
        </div>

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
                  <div className="text-xs text-muted-foreground mb-2">{proj.enrolledCount}/{proj.capacity} {t('admin.dashboard.people')} · {proj.recognizedMinutes / 60}h</div>
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
