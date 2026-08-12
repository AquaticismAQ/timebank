import { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { Download, BarChart2, TrendingUp, Users, Clock } from 'lucide-react';
import { type ReportData, type AdminStats } from '../../api/types';
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer, LineChart, Line, PieChart, Pie, Cell } from 'recharts';

const TOTAL_BAR_COLOR = '#94a3b8'; // slate-400 — visible in both light and dark
import { toast } from 'sonner';
import { api } from '../../api/client';
import { ApiError, mapApiError } from '../../api/http';
import { ListStateBanner } from '../ListStateBanner';

const COLORS = ['#6366f1', '#22c55e', '#f59e0b', '#ef4444', '#8b5cf6', '#06b6d4'];

export function ReportsPage() {
  const { t } = useTranslation();
  const [activeTab, setActiveTab] = useState<'participation' | 'hours' | 'projects'>('participation');
  const [participationSubTab, setParticipationSubTab] = useState<'participated' | 'completed'>('participated');
  const [data, setData] = useState<ReportData | null>(null);
  const [stats, setStats] = useState<AdminStats | null>(null);
  const [yearFilter, setYearFilter] = useState('2025-2026');

  useEffect(() => {
    api.reports.data().then(setData).catch(() => {});
    api.dashboards.admin().then(d => setStats(d.stats)).catch(() => {});
  }, []);

  if (!data || !stats) return <div className="p-4 lg:p-6">{t('common.loading')}</div>;

  const participationData = data.participationByGrade.map(d => ({
    name: d.grade.replace('Grade ', 'G'),
    Participants: d.participated,
    Total: d.total,
    ParticipationRate: Math.round(d.rate * 100),
  }));

  const completionData = data.completionByGrade.map(d => ({
    name: d.grade.replace('Grade ', 'G'),
    Completed: d.completed,
    Total: d.total,
    CompletionRate: Math.round(d.rate * 100),
  }));

  const pieData = data.topProjects.map((p, i) => ({
    name: p.title.slice(0, 8),
    value: p.participants,
  }));

  return (
    <div className="p-4 lg:p-6 space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-foreground">{t('admin.reports.title')}</h1>
          <p className="text-sm text-muted-foreground">{t('admin.reports.dataUpdated', { date: '2026-07-27 08:00' })}</p>
        </div>
        <div className="flex gap-2">
          <select value={yearFilter} onChange={e => setYearFilter(e.target.value)}
            className="px-3 py-2 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none">
            <option value="2025-2026">{t('admin.reports.academicYear', { year: '2025-2026' })}</option>
            <option value="2024-2025">{t('admin.reports.academicYear', { year: '2024-2025' })}</option>
          </select>
          <button onClick={async () => {
            try {
              const job = await api.exports.create('participation');
              toast.info(t('admin.system.exportStarted'));
              const poll = async () => {
                try {
                  const status = await api.exports.get(job.jobId);
                  if (status.status === 'SUCCEEDED' || status.status === 'succeeded') {
                    const dl = await api.exports.download(job.jobId);
                    window.open(dl.downloadUrl, '_blank');
                    toast.success(t('admin.reports.exportToast'));
                  } else if (status.status === 'FAILED' || status.status === 'failed') {
                    toast.error(t('admin.system.exportFailed'));
                  } else { setTimeout(poll, 2000); }
                } catch (err) {
                  if (err instanceof ApiError) toast.error(mapApiError(err).message);
                  else toast.error(t('common.unknownError'));
                }
              };
              setTimeout(poll, 1000);
            } catch (err) {
              if (err instanceof ApiError) toast.error(mapApiError(err).message);
              else toast.error(t('common.unknownError'));
            }
          }}
            className="flex items-center gap-1.5 px-3 py-2 border border-border rounded-xl text-sm text-muted-foreground hover:bg-accent">
            <Download className="w-4 h-4" /> {t('admin.reports.export')}
          </button>
        </div>
      </div>

      {/* Key metrics */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
        {[
          { label: t('admin.reports.totalServiceHours'), value: `${stats.totalServiceHours.toLocaleString()}h`, icon: Clock, color: 'text-blue-500 bg-blue-100 dark:bg-blue-900/30' },
          { label: t('admin.reports.participationRate'), value: stats.avgCompletionRate != null ? `${Math.round(stats.avgCompletionRate * 100)}%` : '—', icon: Users, color: 'text-green-500 bg-green-100 dark:bg-green-900/30' },
          { label: t('admin.reports.activeProjects'), value: stats.activeProjects, icon: BarChart2, color: 'text-purple-500 bg-purple-100 dark:bg-purple-900/30' },
          { label: t('admin.reports.approvedThisMonth'), value: stats.approvedThisMonth, icon: TrendingUp, color: 'text-orange-500 bg-orange-100 dark:bg-orange-900/30' },
        ].map((item, i) => (
          <div key={i} className="bg-card border border-border rounded-xl p-4">
            <div className={`w-9 h-9 rounded-xl flex items-center justify-center mb-2 ${item.color}`}>
              <item.icon className="w-4 h-4" />
            </div>
            <div className="text-2xl font-semibold text-foreground">{item.value}</div>
            <div className="text-xs text-muted-foreground">{item.label}</div>
          </div>
        ))}
      </div>

      {/* Tabs */}
      <div className="flex gap-2 border-b border-border">
        {[
          { key: 'participation', label: t('admin.reports.tabParticipation') },
          { key: 'hours', label: t('admin.reports.tabHours') },
          { key: 'projects', label: t('admin.reports.tabProjects') },
        ].map(({ key, label }) => (
          <button key={key} onClick={() => setActiveTab(key as any)}
            className={`px-4 py-2.5 text-sm border-b-2 transition-colors -mb-px ${
              activeTab === key ? 'border-primary text-primary' : 'border-transparent text-muted-foreground hover:text-foreground'
            }`}>
            {label}
          </button>
        ))}
      </div>

      {/* Charts */}
      {activeTab === 'participation' && (
        <div className="space-y-6">
          <div className="bg-card border border-border rounded-2xl p-4">
            {/* Sub-tabs — only ONE BarChart mounts at a time to avoid recharts key collision */}
            <div className="flex gap-2 mb-4">
              {[
                { key: 'participated', label: t('admin.reports.participationByGrade') },
                { key: 'completed', label: t('admin.reports.completionByGrade') },
              ].map(t => (
                <button key={t.key} onClick={() => setParticipationSubTab(t.key as any)}
                  className={`px-3 py-1.5 rounded-lg text-sm transition-colors ${
                    participationSubTab === t.key
                      ? 'bg-[oklch(0.488_0.243_264.376)] text-white'
                      : 'text-muted-foreground hover:bg-accent'
                  }`}>
                  {t.label}
                </button>
              ))}
            </div>

            {participationSubTab === 'participated' && (
              <>
                <p className="text-xs text-muted-foreground mb-4">{t('admin.reports.activeParticipationDesc')}</p>
                <ResponsiveContainer width="100%" height={260}>
                  <BarChart data={participationData} margin={{ top: 5, right: 10, left: -20, bottom: 5 }}>
                    <CartesianGrid key="grid" strokeDasharray="3 3" stroke="currentColor" className="text-border" />
                    <XAxis key="xaxis" dataKey="name" tick={{ fontSize: 12 }} className="text-muted-foreground" />
                    <YAxis key="yaxis" tick={{ fontSize: 12 }} className="text-muted-foreground" />
                    <Tooltip key="tooltip"
                      contentStyle={{ background: 'var(--card)', border: '1px solid var(--border)', borderRadius: 12 }}
                      labelStyle={{ color: 'var(--foreground)' }}
                      itemStyle={{ color: 'var(--foreground)' }}
                      formatter={(value, name) => [value, name]}
                    />
                    <Legend key="legend" wrapperStyle={{ fontSize: 12, paddingTop: 8 }}
                      formatter={name => <span style={{ color: 'var(--muted-foreground)' }}>{name}</span>} />
                    <Bar key="bar-t" dataKey="Total" fill={TOTAL_BAR_COLOR} radius={[4, 4, 0, 0]} />
                    <Bar key="bar-p" dataKey="Participants" fill="#6366f1" radius={[4, 4, 0, 0]} />
                  </BarChart>
                </ResponsiveContainer>
              </>
            )}

            {participationSubTab === 'completed' && (
              <>
                <p className="text-xs text-muted-foreground mb-4">{t('admin.reports.completionDesc')}</p>
                <ResponsiveContainer width="100%" height={260}>
                  <BarChart data={completionData} margin={{ top: 5, right: 10, left: -20, bottom: 5 }}>
                    <CartesianGrid key="grid" strokeDasharray="3 3" stroke="currentColor" className="text-border" />
                    <XAxis key="xaxis" dataKey="name" tick={{ fontSize: 12 }} />
                    <YAxis key="yaxis" tick={{ fontSize: 12 }} />
                    <Tooltip key="tooltip"
                      contentStyle={{ background: 'var(--card)', border: '1px solid var(--border)', borderRadius: 12 }}
                      labelStyle={{ color: 'var(--foreground)' }}
                      itemStyle={{ color: 'var(--foreground)' }}
                      formatter={(value, name) => [value, name]}
                    />
                    <Legend key="legend" wrapperStyle={{ fontSize: 12, paddingTop: 8 }}
                      formatter={name => <span style={{ color: 'var(--muted-foreground)' }}>{name}</span>} />
                    <Bar key="bar-t" dataKey="Total" fill={TOTAL_BAR_COLOR} radius={[4, 4, 0, 0]} />
                    <Bar key="bar-c" dataKey="Completed" fill="#22c55e" radius={[4, 4, 0, 0]} />
                  </BarChart>
                </ResponsiveContainer>
              </>
            )}
          </div>

          {/* Grade completion table */}
          <div className="bg-card border border-border rounded-2xl overflow-hidden">
            <div className="px-4 py-3 border-b border-border">
              <h3 className="text-foreground">{t('admin.reports.gradeCompletionDetails')}</h3>
            </div>
            <div className="overflow-x-auto">
              <table className="w-full">
                <thead>
                  <tr className="border-b border-border">
                    {[t('admin.reports.colGrade'), t('admin.reports.colTotal'), t('admin.reports.colParticipants'), t('admin.reports.colParticipationRate'), t('admin.reports.colCompleted'), t('admin.reports.colCompletionRate')].map(h => (
                      <th key={h} className="px-4 py-3 text-left text-xs text-muted-foreground font-medium">{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {data.participationByGrade.map((row, i) => {
                    const comp = data.completionByGrade[i];
                    return (
                      <tr key={row.grade} className="border-b border-border last:border-b-0 hover:bg-muted/30">
                        <td className="px-4 py-3 text-sm text-foreground">{row.grade}</td>
                        <td className="px-4 py-3 text-sm text-foreground">{row.total}</td>
                        <td className="px-4 py-3 text-sm text-foreground">{row.participated}</td>
                        <td className="px-4 py-3 text-sm">
                          <span className={`${row.rate >= 0.7 ? 'text-green-600 dark:text-green-400' : row.rate >= 0.5 ? 'text-yellow-600 dark:text-yellow-400' : 'text-red-600 dark:text-red-400'}`}>
                            {Math.round(row.rate * 100)}%
                          </span>
                        </td>
                        <td className="px-4 py-3 text-sm text-foreground">{comp.completed}</td>
                        <td className="px-4 py-3 text-sm">
                          <span className={`${comp.rate >= 0.5 ? 'text-green-600 dark:text-green-400' : comp.rate >= 0.3 ? 'text-yellow-600 dark:text-yellow-400' : 'text-red-600 dark:text-red-400'}`}>
                            {Math.round(comp.rate * 100)}%
                          </span>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      )}

      {activeTab === 'hours' && (
        <div className="space-y-4">
          <div className="bg-card border border-border rounded-2xl p-4">
            <h3 className="text-foreground mb-4">{t('admin.reports.monthlyHoursTrend')}</h3>
            <ResponsiveContainer width="100%" height={280}>
              <LineChart data={data.serviceHoursByMonth} margin={{ top: 5, right: 10, left: -20, bottom: 5 }}>
                <CartesianGrid key="grid" strokeDasharray="3 3" stroke="currentColor" className="text-border" />
                <XAxis key="xaxis" dataKey="month" tick={{ fontSize: 12 }} />
                <YAxis key="yaxis" tick={{ fontSize: 12 }} />
                <Tooltip key="tooltip" contentStyle={{ background: 'var(--card)', border: '1px solid var(--border)', borderRadius: 12 }} />
                <Line key="line" type="monotone" dataKey="hours" stroke="#6366f1" strokeWidth={2} dot={{ r: 4 }} activeDot={{ r: 6 }} name="Service Hours (h)" />
              </LineChart>
            </ResponsiveContainer>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div className="bg-card border border-border rounded-xl p-4">
              <div className="text-muted-foreground text-xs mb-1">{t('admin.reports.peakMonth')}</div>
              <div className="text-foreground font-semibold">{t('admin.reports.peakMonthValue')}</div>
              <div className="text-xs text-muted-foreground">{t('admin.reports.peakMonthDesc')}</div>
            </div>
            <div className="bg-card border border-border rounded-xl p-4">
              <div className="text-muted-foreground text-xs mb-1">{t('admin.reports.lowestMonth')}</div>
              <div className="text-foreground font-semibold">{t('admin.reports.lowestMonthValue')}</div>
              <div className="text-xs text-muted-foreground">{t('admin.reports.lowestMonthDesc')}</div>
            </div>
          </div>
        </div>
      )}

      {activeTab === 'projects' && (
        <div className="space-y-4">
          {data.topProjects.length === 0 ? (
            <div className="bg-card border border-border rounded-2xl">
              <ListStateBanner error={null} empty emptyLabel={t('admin.reports.noProjectData')} />
            </div>
          ) : (
            <>
              <div className="bg-card border border-border rounded-2xl p-4">
                <h3 className="text-foreground mb-4">{t('admin.reports.popularProjects')}</h3>
                <ResponsiveContainer width="100%" height={220}>
                  <PieChart>
                    <Pie key="pie" data={pieData} cx="50%" cy="50%" outerRadius={80} fill="#8884d8" dataKey="value" label={({ name, percent }) => `${name} ${Math.round(percent * 100)}%`}>
                      {pieData.map((_, i) => (
                        <Cell key={`cell-${i}`} fill={COLORS[i % COLORS.length]} />
                      ))}
                    </Pie>
                    <Tooltip key="tooltip" contentStyle={{ background: 'var(--card)', border: '1px solid var(--border)', borderRadius: 12 }} />
                  </PieChart>
                </ResponsiveContainer>
              </div>

              <div className="bg-card border border-border rounded-2xl overflow-hidden">
                <div className="px-4 py-3 border-b border-border">
                  <h3 className="text-foreground">{t('admin.reports.topProjects')}</h3>
                </div>
                <div className="divide-y divide-border">
                  {data.topProjects.map((proj, i) => (
                    <div key={i} className="flex items-center gap-3 px-4 py-3">
                      <div className={`w-6 h-6 rounded-full flex items-center justify-center text-xs font-medium ${
                        i === 0 ? 'bg-yellow-100 dark:bg-yellow-900/30 text-yellow-700 dark:text-yellow-400' :
                        i === 1 ? 'bg-slate-100 dark:bg-slate-900/30 text-slate-600 dark:text-slate-400' :
                        i === 2 ? 'bg-orange-100 dark:bg-orange-900/30 text-orange-700 dark:text-orange-400' :
                        'bg-muted text-muted-foreground'
                      }`}>{i + 1}</div>
                      <div className="flex-1 min-w-0">
                        <div className="text-sm text-foreground truncate">{proj.title}</div>
                        <div className="text-xs text-muted-foreground">{proj.participants} {t('admin.reports.people')} · {proj.totalHours}h {t('admin.reports.total')}</div>
                      </div>
                      <div className="text-sm font-medium text-foreground">{proj.totalHours}h</div>
                    </div>
                  ))}
                </div>
              </div>
            </>
          )}
        </div>
      )}
    </div>
  );
}
