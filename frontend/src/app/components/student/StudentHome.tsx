import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router';
import { Clock, ChevronRight, Plus, AlertCircle, FileText, Star } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { RECORD_STATUS_LABELS, RECOGNITION_LEVELS } from '../../lib/statusLabels';
import { type StudentProgress, type Account, type ServiceRecord, type Project } from '../../api/types';
import { formatCoins, minutesToHours } from '../../lib/format';
import { useAuth } from '../../App';
import { api } from '../../api/client';

function ProgressRing({ percent, size = 80 }: { percent: number; size?: number }) {
  const r = (size - 10) / 2;
  const circ = 2 * Math.PI * r;
  const offset = circ * (1 - Math.min(percent, 1));
  return (
    <svg width={size} height={size} className="-rotate-90">
      <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="currentColor" strokeWidth={6} className="text-border" />
      <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="currentColor" strokeWidth={6}
        strokeDasharray={circ} strokeDashoffset={offset}
        strokeLinecap="round" className="text-primary transition-all duration-500"
      />
    </svg>
  );
}

const colorMap: Record<string, string> = {
  gray: 'bg-muted text-muted-foreground',
  blue: 'bg-blue-100 dark:bg-blue-900/30 text-blue-700 dark:text-blue-400',
  yellow: 'bg-yellow-100 dark:bg-yellow-900/30 text-yellow-700 dark:text-yellow-400',
  green: 'bg-green-100 dark:bg-green-900/30 text-green-700 dark:text-green-400',
  orange: 'bg-orange-100 dark:bg-orange-900/30 text-orange-700 dark:text-orange-400',
  red: 'bg-red-100 dark:bg-red-900/30 text-red-700 dark:text-red-400',
  purple: 'bg-purple-100 dark:bg-purple-900/30 text-purple-700 dark:text-purple-400',
};

export function StudentHome() {
  const navigate = useNavigate();
  const { t } = useTranslation();
  const { user } = useAuth();
  const [progress, setProgress] = useState<StudentProgress | null>(null);
  const [account, setAccount] = useState<Account | null>(null);
  const [recentRecords, setRecentRecords] = useState<ServiceRecord[]>([]);
  const [upcomingProjects, setUpcomingProjects] = useState<Project[]>([]);

  useEffect(() => {
    api.dashboards.student(user?.studentId).then(d => {
      setProgress(d.progress);
      setAccount(d.account);
      setRecentRecords(d.recentRecords);
      setUpcomingProjects(d.upcomingProjects);
    }).catch(() => {});
  }, [user?.studentId]);

  if (!progress || !account) return <div className="p-4 lg:p-6">{t('common.appName')}</div>;

  const annualPercent = progress.completedMinutes / progress.annualRequiredMinutes;
  const totalEarnedCoins = account.totalEarned / 100;

  const currentLevel = RECOGNITION_LEVELS.find((l, i) =>
    totalEarnedCoins >= l.minHours && totalEarnedCoins < (RECOGNITION_LEVELS[i + 1]?.minHours ?? Infinity)
  ) || RECOGNITION_LEVELS[0];
  const nextLevel = RECOGNITION_LEVELS.find(l => l.minHours > totalEarnedCoins);

  const pendingActions = [
    recentRecords.find(r => r.status === 'returned') && { label: t('student.home.supplementMaterial'), desc: t('student.home.supplementDesc'), to: '/student/records', icon: AlertCircle, color: 'text-orange-500' },
    recentRecords.filter(r => r.status === 'underReview').length > 0 && { label: t('student.home.reviewUpdate'), desc: `${recentRecords.filter(r => r.status === 'underReview').length} ${t('student.home.recordsPending')}`, to: '/student/records', icon: Clock, color: 'text-blue-500' },
  ].filter(Boolean) as { label: string; desc: string; to: string; icon: typeof Clock; color: string }[];

  return (
    <div className="p-4 lg:p-6">
      {/* Greeting */}
      <div className="mb-5 lg:mb-6">
        <h2 className="text-foreground">{t('student.home.hello')}{user?.displayName} 👋</h2>
        <p className="text-sm text-muted-foreground">{progress.grade} · {progress.academicYear} {t('common.academicYear')}</p>
      </div>

      {/* ── Desktop: 2-column layout ─────────────────────────────────────── */}
      <div className="lg:grid lg:grid-cols-5 lg:gap-6">

        {/* Left column (wider) */}
        <div className="lg:col-span-3 space-y-5">

          {/* Progress hero */}
          <div className="bg-card border border-border rounded-2xl p-5">
            <div className="flex items-center gap-4 mb-4">
              <div className="relative shrink-0">
                <ProgressRing percent={annualPercent} size={100} />
                <div className="absolute inset-0 flex items-center justify-center">
                  <span className="text-sm font-semibold text-foreground">{Math.round(annualPercent * 100)}%</span>
                </div>
              </div>
              <div className="flex-1">
                <div className="text-muted-foreground text-xs mb-1">{t('student.home.annualProgress')}</div>
                <div className="text-foreground mb-0.5">
                  <span className="text-3xl font-semibold">{minutesToHours(progress.completedMinutes)}</span>
                  <span className="text-muted-foreground text-sm"> / {minutesToHours(progress.annualRequiredMinutes)} {t('common.hours')}</span>
                </div>
                {progress.underReviewMinutes > 0 && (
                  <div className="text-xs text-blue-500">+{minutesToHours(progress.underReviewMinutes)} {t('student.home.hoursPending')}</div>
                )}
                <div className="text-xs text-muted-foreground mt-1">{t('student.home.gap')}<span className="text-destructive">{minutesToHours(progress.gapMinutes)}</span> {t('common.hours')}</div>
              </div>
            </div>

            <div className="grid grid-cols-4 gap-3 text-center border-t border-border pt-4">
              <div>
                <div className="text-foreground font-semibold">{minutesToHours(progress.completedMinutes)}h</div>
                <div className="text-muted-foreground text-xs">{t('student.home.completedLabel')}</div>
              </div>
              <div className="border-x border-border">
                <div className="text-blue-500 font-semibold">{minutesToHours(progress.underReviewMinutes)}h</div>
                <div className="text-muted-foreground text-xs">{t('student.home.pending')}</div>
              </div>
              <div className="border-r border-border">
                <div className="text-foreground font-semibold">{formatCoins(account.balance)}</div>
                <div className="text-muted-foreground text-xs">{t('student.home.timeCoins')}</div>
              </div>
              <div>
                <div className="text-foreground font-semibold text-sm">{currentLevel.icon} {t(currentLevel.label)}</div>
                <div className="text-muted-foreground text-xs">{t('student.home.level')}</div>
              </div>
            </div>

            <button onClick={() => navigate('/student/progress')}
              className="mt-3 w-full text-sm text-primary hover:text-primary/80 flex items-center justify-center gap-1 transition-colors">
              {t('student.home.viewFullProgress')} <ChevronRight className="w-4 h-4" />
            </button>
          </div>

          {/* Quick actions */}
          <div className="grid grid-cols-2 gap-3">
            <button onClick={() => navigate('/student/records')}
              className="flex items-center justify-center gap-2 bg-[oklch(0.488_0.243_264.376)] text-white rounded-2xl px-4 py-3 hover:opacity-90 transition-opacity">
              <Plus className="w-5 h-5" />
              <span className="text-sm">{t('student.home.submitRecord')}</span>
            </button>
            <button onClick={() => navigate('/student/projects')}
              className="flex items-center justify-center gap-2 bg-card border border-border text-foreground rounded-2xl px-4 py-3 hover:bg-accent transition-colors">
              <Star className="w-5 h-5 text-primary" />
              <span className="text-sm">{t('student.home.browseProjects')}</span>
            </button>
          </div>

          {/* Pending actions */}
          {pendingActions.length > 0 && (
            <div className="space-y-2">
              <h3 className="text-sm text-muted-foreground font-medium">{t('student.home.todo')}</h3>
              {pendingActions.map((action, i) => (
                <button key={i} onClick={() => navigate(action.to)}
                  className="w-full flex items-center gap-3 bg-card border border-border rounded-xl p-4 hover:bg-accent/50 transition-colors text-left">
                  <action.icon className={`w-5 h-5 ${action.color} shrink-0`} />
                  <div className="flex-1 min-w-0">
                    <div className="text-sm text-foreground">{action.label}</div>
                    <div className="text-xs text-muted-foreground truncate">{action.desc}</div>
                  </div>
                  <ChevronRight className="w-4 h-4 text-muted-foreground shrink-0" />
                </button>
              ))}
            </div>
          )}

          {/* Recent records — desktop table */}
          <div className="bg-card border border-border rounded-2xl overflow-hidden">
            <div className="flex items-center justify-between px-4 py-3 border-b border-border">
              <h3 className="text-sm font-medium text-foreground">{t('student.home.recentRecords')}</h3>
              <button onClick={() => navigate('/student/records')} className="text-xs text-primary">{t('common.allArrow')}</button>
            </div>

            {/* Mobile: card list */}
            <div className="lg:hidden divide-y divide-border">
              {recentRecords.map(rec => {
                const status = RECORD_STATUS_LABELS[rec.status];
                return (
                  <div key={rec.id} className="flex items-center gap-3 p-4">
                    <FileText className="w-4 h-4 text-muted-foreground shrink-0" />
                    <div className="flex-1 min-w-0">
                      <div className="text-sm text-foreground truncate">{rec.projectTitle || rec.organizationName}</div>
                      <div className="text-xs text-muted-foreground">{rec.serviceDate} · {minutesToHours(rec.durationMinutes)}h</div>
                    </div>
                    <span className={`text-xs px-2 py-0.5 rounded-full whitespace-nowrap ${colorMap[status.color] || colorMap.gray}`}>{t(status.label)}</span>
                  </div>
                );
              })}
            </div>

            {/* Desktop: table */}
            <table className="hidden lg:table w-full text-sm">
              <thead className="bg-muted/50">
                <tr>
                  <th className="text-left px-4 py-2.5 text-xs text-muted-foreground font-medium">{t('student.home.projectName')}</th>
                  <th className="text-left px-4 py-2.5 text-xs text-muted-foreground font-medium">{t('student.home.date')}</th>
                  <th className="text-left px-4 py-2.5 text-xs text-muted-foreground font-medium">{t('student.home.duration')}</th>
                  <th className="text-left px-4 py-2.5 text-xs text-muted-foreground font-medium">{t('student.home.category')}</th>
                  <th className="text-left px-4 py-2.5 text-xs text-muted-foreground font-medium">{t('student.home.status')}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {recentRecords.map(rec => {
                  const status = RECORD_STATUS_LABELS[rec.status];
                  return (
                    <tr key={rec.id} className="hover:bg-muted/30 transition-colors">
                      <td className="px-4 py-3 text-foreground">{rec.projectTitle || rec.organizationName}</td>
                      <td className="px-4 py-3 text-muted-foreground">{rec.serviceDate}</td>
                      <td className="px-4 py-3 text-muted-foreground">{minutesToHours(rec.durationMinutes)}h</td>
                      <td className="px-4 py-3 text-xs text-muted-foreground">{rec.serviceCategoryName}</td>
                      <td className="px-4 py-3">
                        <span className={`text-xs px-2 py-0.5 rounded-full whitespace-nowrap ${colorMap[status.color] || colorMap.gray}`}>{t(status.label)}</span>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>

        {/* Right column */}
        <div className="lg:col-span-2 space-y-5 mt-5 lg:mt-0">

          {/* Level card */}
          <div className="bg-card border border-border rounded-2xl p-4">
            <h3 className="text-sm font-medium text-foreground mb-3">{t('student.home.honorLevel')}</h3>
            <div className="flex items-center gap-3 mb-3">
              <span className="text-3xl">{currentLevel.icon}</span>
              <div>
                <div className="text-foreground font-medium">{t(currentLevel.label)}</div>
                {nextLevel && (
                  <div className="text-xs text-muted-foreground">
                    {nextLevel.minHours - totalEarnedCoins} {t('student.home.distanceTo')}{t(nextLevel.label)}
                  </div>
                )}
              </div>
            </div>
            <div className="space-y-1.5">
              {RECOGNITION_LEVELS.slice(1).map(level => {
                const prog = Math.min(totalEarnedCoins / level.minHours, 1);
                const reached = totalEarnedCoins >= level.minHours;
                return (
                  <div key={level.level} className="flex items-center gap-2">
                    <span className="text-sm w-5">{level.icon}</span>
                    <div className="flex-1 h-1.5 bg-muted rounded-full overflow-hidden">
                      <div className={`h-full rounded-full transition-all ${reached ? 'bg-primary' : 'bg-primary/40'}`}
                        style={{ width: `${prog * 100}%` }} />
                    </div>
                    <span className="text-xs text-muted-foreground w-10 text-right">{level.minHours}h</span>
                  </div>
                );
              })}
            </div>
          </div>

          {/* Upcoming projects */}
          {upcomingProjects.length > 0 && (
            <div className="bg-card border border-border rounded-2xl overflow-hidden">
              <div className="flex items-center justify-between px-4 py-3 border-b border-border">
                <h3 className="text-sm font-medium text-foreground">{t('student.home.registering')}</h3>
                <button onClick={() => navigate('/student/projects')} className="text-xs text-primary">{t('student.home.viewMore')}</button>
              </div>
              <div className="divide-y divide-border">
                {upcomingProjects.map(proj => (
                  <div key={proj.id} className="px-4 py-3">
                    <div className="flex items-start justify-between gap-2 mb-1">
                      <div className="text-sm text-foreground font-medium flex-1 leading-snug">{proj.title}</div>
                      <span className="text-xs bg-green-100 dark:bg-green-900/30 text-green-700 dark:text-green-400 px-2 py-0.5 rounded-full whitespace-nowrap shrink-0">Open</span>
                    </div>
                    <div className="text-xs text-muted-foreground">
                      {minutesToHours(proj.recognizedMinutes)}h · {proj.capacity - proj.enrolledCount} {t('student.home.slots')}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Account summary */}
          <div className="bg-gradient-to-br from-blue-50 to-blue-100 border border-blue-200 dark:border-transparent dark:from-[oklch(0.488_0.243_264.376)] dark:to-[oklch(0.488_0.243_264.376)]/75 rounded-2xl p-5 text-blue-900 dark:text-white">
            <div className="text-xs text-blue-500 dark:text-white/60 mb-1">{t('student.home.coinBalance')}</div>
            <div className="text-4xl font-bold mb-3">{formatCoins(account.balance)}</div>
            <div className="grid grid-cols-2 gap-2 text-sm">
              <div className="bg-blue-200/50 dark:bg-white/15 rounded-xl p-2 text-center">
                <div className="font-semibold">{formatCoins(account.totalEarned)}</div>
                <div className="text-xs text-blue-500 dark:text-white/60">{t('student.home.totalEarned')}</div>
              </div>
              <div className="bg-blue-200/50 dark:bg-white/15 rounded-xl p-2 text-center">
                <div className="font-semibold">{formatCoins(account.totalRedeemed)}</div>
                <div className="text-xs text-blue-500 dark:text-white/60">{t('student.home.exchanged')}</div>
              </div>
            </div>
            <button onClick={() => navigate('/student/account')}
              className="mt-3 w-full text-xs text-blue-500 dark:text-white/70 hover:text-blue-700 dark:hover:text-white flex items-center justify-center gap-1 transition-colors">
              {t('student.home.viewAccountDetails')} <ChevronRight className="w-3 h-3" />
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
