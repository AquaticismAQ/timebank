import { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { BarChart2, TrendingUp, Users, Clock } from 'lucide-react';
import { type ReportData, type AdminStats } from '../../api/types';
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer, LineChart, Line, PieChart, Pie, Cell } from 'recharts';

const TOTAL_BAR_COLOR = '#94a3b8'; // slate-400 — visible in both light and dark
import { api } from '../../api/client';
import { minutesToHours } from '../../lib/format';
import { useDictionaries } from '../../api/useDictionaries';
import { ListStateBanner } from '../ListStateBanner';

const COLORS = ['#6366f1', '#22c55e', '#f59e0b', '#ef4444', '#8b5cf6', '#06b6d4'];

/** Drawn once, used for both the school-wide and the grade-scoped pie (issue 135). */
function CategoryPie({ data }: { data: { name: string; value: number }[] }) {
  return (
    <ResponsiveContainer width="100%" height={220}>
      <PieChart>
        <Pie key="pie" data={data} cx="50%" cy="50%" outerRadius={80} fill="#8884d8" dataKey="value"
          label={({ name, percent }) => `${name} ${Math.round((percent ?? 0) * 100)}%`}>
          {data.map((_, i) => (
            <Cell key={`cell-${i}`} fill={COLORS[i % COLORS.length]} />
          ))}
        </Pie>
        <Tooltip key="tooltip"
          contentStyle={{ background: 'var(--card)', border: '1px solid var(--border)', borderRadius: 12 }}
          labelStyle={{ color: 'var(--foreground)' }}
          itemStyle={{ color: 'var(--foreground)' }}
        />
      </PieChart>
    </ResponsiveContainer>
  );
}

export function ReportsPage() {
  const { t } = useTranslation();
  const [activeTab, setActiveTab] = useState<'participation' | 'hours' | 'projects'>('participation');
  const [participationSubTab, setParticipationSubTab] = useState<'participated' | 'completed'>('participated');
  const [data, setData] = useState<ReportData | null>(null);
  const [stats, setStats] = useState<AdminStats | null>(null);
  // Issue 093 §1. `yearFilter` used to be a hardcoded '2025-2026' that nothing
  // read: the fetch below had `[]` dependencies and `api.reports.data()` took
  // no argument. The options now come from `/academic-years` and the selection
  // is what the report endpoints are scoped by.
  const { academicYears, serviceCategories, loading: dictionariesLoading } = useDictionaries();
  /**
   * Which Top Projects list is showing (issue 135). Declared here with the
   * other hooks, above the `if (!data || !stats) return` guard below — a hook
   * after a conditional return runs on some renders and not others, which is
   * what took this page down in issue 133.
   */
  const [projectScope, setProjectScope] = useState<'school' | 'mine'>('school');
  const [yearFilter, setYearFilter] = useState('');

  // Most recent year first — `/academic-years` returns them unordered.
  const yearOptions = [...academicYears].sort((a, b) => b.startDate.localeCompare(a.startDate));
  // The current year is the default until the user picks one, derived rather
  // than written into state on load: setting state from an effect would let the
  // fetch below fire once unscoped and then again a render later.
  const selectedYear = yearFilter || academicYears.find(y => y.current)?.id || '';

  useEffect(() => {
    // Waits for the dictionary load so the first request already carries a year.
    // A still-empty `selectedYear` past that point means the dictionary call
    // failed — fetch anyway and let the server apply its own default.
    if (dictionariesLoading) return;
    api.reports.data(selectedYear || undefined).then(setData).catch(() => {});
  }, [dictionariesLoading, selectedYear]);

  useEffect(() => {
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

  // Issue 093 §2. The card is labelled Participation Rate, but read
  // `stats.avgCompletionRate` — a field `GET /dashboards/admin` does not return
  // (so it always rendered an em dash) and which measures something else anyway:
  // completing the requirement, not taking part at all. Derived here from the
  // participation figures the page has already loaded.
  const participationTotals = data.participationByGrade.reduce(
    (acc, g) => ({ total: acc.total + g.total, participated: acc.participated + g.participated }),
    { total: 0, participated: 0 },
  );
  const participationRate = participationTotals.total > 0
    ? participationTotals.participated / participationTotals.total
    : null;

  // Issue 093 §3. Peak and lowest were fixed strings in the translation bundle
  // ("April · 620h"), left over from before the endpoint existed, so they
  // contradicted the curve drawn beside them. Derived from the plotted series.
  const monthsWithHours = data.serviceHoursByMonth.filter(m => typeof m.hours === 'number');
  /**
   * Minutes on the wire, hours on the axis — a day is often a fraction of one.
   *
   * Plain consts, not `useMemo`: everything from here down sits *after* the
   * `if (!data || !stats) return` above, so a hook here runs on some renders
   * and not others — React counts them and throws (#310, and it took the whole
   * page down when this was first written that way). A 365-element map is not
   * worth a hook anyway.
   */
  /**
   * Both series on one set of points (issue 135): a teacher reads their own
   * grades against the school, which is the comparison a single line cannot
   * make. `scopedHours` is empty for an administrator — they *are* the school,
   * and a second identical line would say nothing twice.
   */
  const scopedByDay = new Map(data.serviceHoursByDayInScope.map(d => [d.day, d.minutes]));
  const hasScopedSeries = scopedByDay.size > 0;
  const dailyHours = data.serviceHoursByDay.map(d => ({
    day: d.day,
    hours: Math.round((d.minutes / 60) * 100) / 100,
    scopedHours: hasScopedSeries ? Math.round(((scopedByDay.get(d.day) ?? 0) / 60) * 100) / 100 : undefined,
  }));
  /** One tick per month: the first day of each, which is a value the axis actually holds. */
  const seenMonths = new Set<string>();
  const monthTicks = dailyHours
    .filter(d => {
      const ym = d.day.slice(0, 7);
      if (seenMonths.has(ym)) return false;
      seenMonths.add(ym);
      return true;
    })
    .map(d => d.day);
  // Issue 093 §1. The card read `stats.totalServiceHours`, which
  // `GET /dashboards/admin` computes for the *current* academic year and has no
  // year parameter — so once the dropdown worked, a card labelled only "Total
  // Service Hours" would have kept showing this year's figure under a header
  // naming another. Summed from the year-scoped trend the page already loads.
  // (Active Projects and Approved This Month below are deliberately left alone:
  // both name their own period and are "right now" figures, not year figures.)
  const totalServiceHours = monthsWithHours.reduce((sum, m) => sum + m.hours, 0);
  const peakMonth = monthsWithHours.length
    ? monthsWithHours.reduce((a, b) => (b.hours > a.hours ? b : a))
    : null;
  const lowestMonth = monthsWithHours.length
    ? monthsWithHours.reduce((a, b) => (b.hours < a.hours ? b : a))
    : null;

  /**
   * Slices are service categories, not individual projects (issue 134).
   *
   * A pie of projects was ten titles truncated to eight characters, four of
   * them the same generated name — it could not be read, and "which project"
   * is what the table beside it already answers. "Which kind of service" is the
   * question a pie is for, and there are six categories rather than ten
   * look-alike titles.
   *
   * Names come from the dictionary, so they follow the reader's language; the
   * server sends ids and counts. An unknown id falls back to the id rather than
   * vanishing from the chart.
   */
  const toPie = (rows: { categoryId: string; participants: number }[]) => rows.map(c => ({
    name: serviceCategories.find(sc => sc.id === c.categoryId)?.name ?? c.categoryId,
    value: c.participants,
  }));
  const pieData = toPie(data.participationByCategory);
  const scopedPieData = toPie(data.participationByCategoryInScope);
  /** A teacher gets both pies; an administrator gets the one that is already everything. */
  const hasScopedBreakdown = scopedPieData.length > 0 || data.topProjectsInScope.length > 0;
  const shownProjects = projectScope === 'mine' ? data.topProjectsInScope : data.topProjects;

  return (
    <div className="p-4 lg:p-6 space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-foreground">{t('admin.reports.title')}</h1>
          <p className="text-sm text-muted-foreground">
            {t('admin.reports.dataUpdated', { date: new Date(data.generatedAt).toLocaleString() })}
          </p>
        </div>
        <div className="flex gap-2">
          <select value={selectedYear} onChange={e => setYearFilter(e.target.value)}
            disabled={yearOptions.length === 0}
            className="px-3 py-2 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none disabled:opacity-60">
            {yearOptions.map(year => (
              <option key={year.id} value={year.id}>
                {t('admin.reports.academicYear', { year: year.code })}
              </option>
            ))}
          </select>
        </div>
      </div>

      {/* Key metrics */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
        {[
          { label: t('admin.reports.totalServiceHours'), value: `${totalServiceHours.toLocaleString()}h`, icon: Clock, color: 'text-blue-500 bg-blue-100 dark:bg-blue-900/30' },
          { label: t('admin.reports.participationRate'), value: participationRate != null ? `${Math.round(participationRate * 100)}%` : '—', icon: Users, color: 'text-green-500 bg-green-100 dark:bg-green-900/30' },
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
            <h3 className="text-foreground mb-4">{t('admin.reports.hoursTrend')}</h3>
            {/* Drawn a day at a time, labelled a month at a time (issue 133).
                Twelve points across a school year is too coarse to read: a busy
                weekend and a quiet fortnight average into the same flat line.
                The axis keeps month ticks because a label per day is unreadable
                and nobody navigates a year by date. */}
            <ResponsiveContainer width="100%" height={280}>
              <LineChart data={dailyHours} margin={{ top: 5, right: 10, left: -20, bottom: 5 }}>
                <CartesianGrid key="grid" strokeDasharray="3 3" stroke="currentColor" className="text-border" />
                <XAxis key="xaxis" dataKey="day" tick={{ fontSize: 12 }}
                  ticks={monthTicks} tickFormatter={d => d.slice(0, 7)} interval={0} minTickGap={0} />
                <YAxis key="yaxis" tick={{ fontSize: 12 }} />
                <Tooltip key="tooltip"
                  contentStyle={{ background: 'var(--card)', border: '1px solid var(--border)', borderRadius: 12 }}
                  labelStyle={{ color: 'var(--foreground)' }}
                  itemStyle={{ color: 'var(--foreground)' }}
                />
                {hasScopedSeries && <Legend key="legend" />}
                {/* No per-point dot: 365 of them is a solid band, not a line. */}
                <Line key="line" type="monotone" dataKey="hours" stroke="#6366f1" strokeWidth={2} dot={false} activeDot={{ r: 5 }} name={t('admin.reports.seriesSchool')} />
                {hasScopedSeries && (
                  <Line key="scoped" type="monotone" dataKey="scopedHours" stroke="#f59e0b" strokeWidth={2} dot={false} activeDot={{ r: 5 }} name={t('admin.reports.seriesMyGrades')} />
                )}
              </LineChart>
            </ResponsiveContainer>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div className="bg-card border border-border rounded-xl p-4">
              <div className="text-muted-foreground text-xs mb-1">{t('admin.reports.peakMonth')}</div>
              <div className="text-foreground font-semibold">
                {peakMonth ? `${peakMonth.month} · ${peakMonth.hours}h` : '—'}
              </div>
            </div>
            <div className="bg-card border border-border rounded-xl p-4">
              <div className="text-muted-foreground text-xs mb-1">{t('admin.reports.lowestMonth')}</div>
              <div className="text-foreground font-semibold">
                {lowestMonth ? `${lowestMonth.month} · ${lowestMonth.hours}h` : '—'}
              </div>
            </div>
          </div>
        </div>
      )}

      {activeTab === 'projects' && (
        <div className="space-y-4">
          {data.topProjects.length === 0 && pieData.length === 0 ? (
            <div className="bg-card border border-border rounded-2xl">
              <ListStateBanner error={null} empty emptyLabel={t('admin.reports.noProjectData')} />
            </div>
          ) : (
            <>
              {/* One pie for an administrator, two for a teacher (issue 135):
                  the school's mix, and their own grades' beside it. Side by
                  side rather than stacked, because the point is the comparison
                  and a scroll between them defeats it. */}
              <div className={`grid gap-3 ${hasScopedBreakdown ? 'lg:grid-cols-2' : 'grid-cols-1'}`}>
                <div className="bg-card border border-border rounded-2xl p-4">
                  <h3 className="text-foreground mb-4">
                    {t('admin.reports.participationByCategory')}
                    {hasScopedBreakdown && <span className="text-sm text-muted-foreground ml-2">· {t('admin.reports.wholeSchool')}</span>}
                  </h3>
                  <CategoryPie data={pieData} />
                </div>
                {hasScopedBreakdown && (
                  <div className="bg-card border border-border rounded-2xl p-4">
                    <h3 className="text-foreground mb-4">
                      {t('admin.reports.participationByCategory')}
                      <span className="text-sm text-muted-foreground ml-2">· {t('admin.reports.myGrades')}</span>
                    </h3>
                    {scopedPieData.length > 0
                      ? <CategoryPie data={scopedPieData} />
                      : <div className="h-[220px] flex items-center justify-center text-sm text-muted-foreground">{t('admin.reports.noProjectData')}</div>}
                  </div>
                )}
              </div>

              <div className="bg-card border border-border rounded-2xl overflow-hidden">
                <div className="px-4 py-3 border-b border-border flex items-center gap-3 flex-wrap">
                  <h3 className="text-foreground">{t('admin.reports.topProjects')}</h3>
                  {/* Two tabs for a teacher, none for an administrator — a
                      second tab holding the same rows is a control that does
                      nothing (issue 135). */}
                  {hasScopedBreakdown && (
                    <div className="flex gap-1 ml-auto">
                      {(['school', 'mine'] as const).map(scope => (
                        <button key={scope} onClick={() => setProjectScope(scope)}
                          className={`px-3 py-1.5 rounded-lg text-xs transition-colors ${
                            projectScope === scope
                              ? 'bg-blue-100 text-blue-700 dark:bg-blue-500/20 dark:text-blue-300'
                              : 'bg-muted text-muted-foreground hover:bg-accent'}`}>
                          {t(scope === 'school' ? 'admin.reports.wholeSchool' : 'admin.reports.myGrades')}
                        </button>
                      ))}
                    </div>
                  )}
                </div>
                <div className="divide-y divide-border">
                  {shownProjects.length === 0 && (
                    <div className="px-4 py-6 text-sm text-muted-foreground">{t('admin.reports.noProjectData')}</div>
                  )}
                  {shownProjects.map((proj, i) => (
                    <div key={i} className="flex items-center gap-3 px-4 py-3">
                      <div className={`w-6 h-6 rounded-full flex items-center justify-center text-xs font-medium ${
                        i === 0 ? 'bg-yellow-100 dark:bg-yellow-900/30 text-yellow-700 dark:text-yellow-400' :
                        i === 1 ? 'bg-slate-100 dark:bg-slate-900/30 text-slate-600 dark:text-slate-400' :
                        i === 2 ? 'bg-orange-100 dark:bg-orange-900/30 text-orange-700 dark:text-orange-400' :
                        'bg-muted text-muted-foreground'
                      }`}>{i + 1}</div>
                      <div className="flex-1 min-w-0">
                        <div className="text-sm text-foreground truncate">{proj.title}</div>
                        <div className="text-xs text-muted-foreground">{proj.participants} {t('admin.reports.people')} · {minutesToHours(proj.totalMinutes)}h {t('admin.reports.total')}</div>
                      </div>
                      <div className="text-sm font-medium text-foreground">{minutesToHours(proj.totalMinutes)}h</div>
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
