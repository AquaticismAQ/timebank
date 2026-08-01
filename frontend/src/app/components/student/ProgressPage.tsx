import { useState, useEffect } from 'react';
import { RECOGNITION_LEVELS, GRADE_LABEL_KEYS, type StudentProgress } from '../../mock/data';
import { ChevronRight, AlertCircle } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';
import { api } from '../../api/client';
import { useAuth } from '../../App';

function CircleProgress({ value, max, size = 120, color = 'text-primary' }: { value: number; max: number; size?: number; color?: string }) {
  const r = (size - 12) / 2;
  const circ = 2 * Math.PI * r;
  const pct = Math.min(value / max, 1);
  const offset = circ * (1 - pct);
  return (
    <svg width={size} height={size} className="-rotate-90">
      <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="currentColor" strokeWidth={8} className="text-border" />
      <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="currentColor" strokeWidth={8}
        strokeDasharray={circ} strokeDashoffset={offset} strokeLinecap="round" className={`${color} transition-all duration-700`} />
    </svg>
  );
}

function ProgressBar({ value, max, color = 'bg-primary', showLabel = true }: { value: number; max: number; color?: string; showLabel?: boolean }) {
  const pct = Math.min((value / max) * 100, 100);
  return (
    <div>
      {showLabel && (
        <div className="flex justify-between text-xs text-muted-foreground mb-1">
          <span>{value} hours</span>
          <span>{max} hours</span>
        </div>
      )}
      <div className="h-2 bg-muted rounded-full overflow-hidden">
        <div className={`h-full ${color} rounded-full transition-all duration-500`} style={{ width: `${pct}%` }} />
      </div>
    </div>
  );
}

export function ProgressPage() {
  const { t } = useTranslation();
  const { user } = useAuth();
  const [progress, setProgress] = useState<StudentProgress | null>(null);
  const navigate = useNavigate();

  useEffect(() => {
    api.students.progress(user?.studentId || 'std_001').then(setProgress).catch(() => {});
  }, [user?.studentId]);

  if (!progress) return <div className="p-4 lg:p-6">{t('common.appName')}</div>;

  const annualPct = progress.completedHours / progress.annualRequiredHours;
  const stagePct = progress.completedHours / progress.stageRequiredHours;

  const currentLevel = RECOGNITION_LEVELS.find((l, i) =>
    progress.completedHours >= l.minHours && progress.completedHours < (RECOGNITION_LEVELS[i + 1]?.minHours ?? Infinity)
  ) || RECOGNITION_LEVELS[0];
  const nextLevel = RECOGNITION_LEVELS.find(l => l.minHours > progress.completedHours);

  return (
    <div className="p-4 lg:p-6">
      <div className="flex items-center justify-between mb-5">
        <h2 className="text-foreground">{t('student.progress.title')}</h2>
        <span className="text-xs text-muted-foreground">{progress.academicYear} {t('common.academicYear')}</span>
      </div>

      {/* ── Desktop: 2-column ───────────────────────────────────────────── */}
      <div className="lg:grid lg:grid-cols-2 lg:gap-6 space-y-4 lg:space-y-0">

        {/* Left column */}
        <div className="space-y-4">

          {/* Annual requirement */}
          <div className="bg-card border border-border rounded-2xl p-5">
            <h3 className="text-foreground mb-4">{t('student.progress.annualReq')}</h3>
            <div className="flex items-center gap-5 mb-5">
              <div className="relative shrink-0">
                <CircleProgress value={progress.completedHours} max={progress.annualRequiredHours} size={110} />
                <div className="absolute inset-0 flex items-center justify-center">
                  <div className="text-center">
                    <div className="text-xl font-bold text-foreground">{Math.round(annualPct * 100)}%</div>
                    <div className="text-xs text-muted-foreground">{t('student.progress.completed')}</div>
                  </div>
                </div>
              </div>
              <div className="flex-1">
                <div className="text-3xl font-semibold text-foreground">{progress.completedHours}</div>
                <div className="text-muted-foreground text-sm mb-2">/ {progress.annualRequiredHours} {t('common.hours')}</div>
                {progress.underReviewHours > 0 && (
                  <div className="text-xs text-blue-500 mb-1">+{progress.underReviewHours}h {t('student.progress.hoursPending')}</div>
                )}
                <div className="text-sm text-muted-foreground">
                  {t('student.progress.stillNeed')} <span className="text-destructive font-medium">{progress.gapHours}</span> {t('common.hours')}
                </div>
              </div>
            </div>

            <ProgressBar value={progress.completedHours} max={progress.annualRequiredHours} />
            {progress.underReviewHours > 0 && (
              <div className="mt-2">
                <div className="text-xs text-muted-foreground mb-1">{t('student.records.underReview')}</div>
                <ProgressBar value={progress.completedHours + progress.underReviewHours} max={progress.annualRequiredHours} color="bg-blue-400" showLabel={false} />
              </div>
            )}
          </div>

          {/* Graduation requirement */}
          <div className="bg-card border border-border rounded-2xl p-5">
            <h3 className="text-foreground mb-1">{t('student.progress.graduationReq')}</h3>
            <div className="text-muted-foreground text-sm mb-3">
              {t('student.progress.completedLabel')} <span className="text-foreground font-semibold">{progress.completedHours}</span> / {progress.stageRequiredHours} {t('common.hours')}
            </div>
            <div className="mb-2 relative">
              <div className="h-3 bg-muted rounded-full overflow-hidden">
                <div className="h-full bg-primary rounded-full transition-all duration-500" style={{ width: `${Math.min(stagePct, 1) * 100}%` }} />
              </div>
            </div>
            <div className="flex justify-between text-xs text-muted-foreground mb-4">
              <span>{progress.completedHours}h</span>
              <span>{progress.stageRequiredHours}h</span>
            </div>

            <div className="grid grid-cols-3 gap-2">
              {[
                { label: t('student.progress.grade9'), target: 75, done: true },
                { label: t('student.progress.grade10'), target: 75, done: false, current: true },
                { label: t('student.progress.grade11'), target: 75, done: false },
              ].map((yr, i) => (
                <div key={i} className={`p-2.5 rounded-xl text-center ${
                  yr.current ? 'bg-primary/10 border border-primary/30' :
                  yr.done ? 'bg-green-50 dark:bg-green-900/20 border border-green-200 dark:border-green-800' :
                  'bg-muted'
                }`}>
                  <div className={`text-xs mb-0.5 whitespace-pre-line ${yr.current ? 'text-primary' : yr.done ? 'text-green-700 dark:text-green-400' : 'text-muted-foreground'}`}>
                    {yr.label}
                  </div>
                  <div className={`text-sm font-medium ${yr.current ? 'text-primary' : yr.done ? 'text-green-600 dark:text-green-400' : 'text-muted-foreground'}`}>
                    {yr.target}h {yr.done ? '✓' : yr.current ? t('student.progress.inProgress') : '—'}
                  </div>
                </div>
              ))}
            </div>
          </div>

          {/* Alert */}
          {progress.gapHours > 50 && (
            <div className="bg-orange-50 dark:bg-orange-900/20 border border-orange-200 dark:border-orange-800 rounded-xl p-4 flex gap-3">
              <AlertCircle className="w-5 h-5 text-orange-500 shrink-0 mt-0.5" />
              <div>
                <div className="text-sm text-orange-800 dark:text-orange-200 font-medium">{t('student.progress.insufficient')}</div>
                <div className="text-xs text-orange-700 dark:text-orange-300 mt-0.5">
                  {t('student.progress.gapMsg')}{progress.gapHours}{t('student.progress.gapSuffix')}
                </div>
                <button onClick={() => navigate('/student/projects')}
                  className="mt-2 text-xs text-orange-600 dark:text-orange-400 hover:underline flex items-center gap-1">
                  {t('student.progress.browseProjects')} <ChevronRight className="w-3 h-3" />
                </button>
              </div>
            </div>
          )}
        </div>

        {/* Right column */}
        <div className="space-y-4">

          {/* Grade & level */}
          <div className="bg-card border border-border rounded-2xl p-4">
            <div className="flex items-center justify-between">
              <div>
                <div className="text-xs text-muted-foreground mb-0.5">{t('student.progress.currentGrade')}</div>
                <div className="text-foreground font-medium">{t(GRADE_LABEL_KEYS[progress.grade])}</div>
              </div>
              <div className="text-right">
                <div className="text-xs text-muted-foreground mb-0.5">{t('student.progress.honorLevel')}</div>
                <div className="text-foreground font-medium">{currentLevel.icon} {t(currentLevel.label)}</div>
              </div>
              {nextLevel && (
                <div className="text-right">
                  <div className="text-xs text-muted-foreground mb-0.5">{t('student.progress.distanceToNext')}</div>
                  <div className="text-2xl font-bold text-foreground">{nextLevel.minHours - progress.completedHours}h</div>
                </div>
              )}
            </div>
          </div>

          {/* By category */}
          <div className="bg-card border border-border rounded-2xl p-4">
            <h3 className="text-foreground mb-4">{t('student.progress.byCategory')}</h3>
            <div className="space-y-4">
              {progress.byCategory.map(cat => {
                const total = cat.completedMinutes + cat.underReviewMinutes;
                const maxMins = Math.max(total, 120);
                const completedPct = (cat.completedMinutes / (maxMins * 2)) * 100;
                const reviewPct = (cat.underReviewMinutes / (maxMins * 2)) * 100;
                return (
                  <div key={cat.categoryId}>
                    <div className="flex items-center justify-between text-xs mb-1.5">
                      <span className="text-foreground font-medium">{cat.categoryName.startsWith('mock.') ? t(cat.categoryName) : cat.categoryName}</span>
                      <span className="text-muted-foreground">
                        {cat.completedMinutes / 60}h
                        {cat.underReviewMinutes > 0 && <span className="text-blue-500"> +{cat.underReviewMinutes / 60}h</span>}
                      </span>
                    </div>
                    <div className="h-2 bg-muted rounded-full overflow-hidden flex">
                      <div className="h-full bg-primary rounded-full" style={{ width: `${completedPct}%` }} />
                      <div className="h-full bg-blue-400/60 rounded-full" style={{ width: `${reviewPct}%` }} />
                    </div>
                  </div>
                );
              })}
            </div>
          </div>

          {/* Recognition levels reference */}
          <div className="bg-card border border-border rounded-2xl p-4">
            <h3 className="text-foreground mb-3">{t('student.progress.honorStandards')}</h3>
            <div className="space-y-2">
              {RECOGNITION_LEVELS.map(level => (
                <div key={level.level} className={`flex items-center gap-3 p-3 rounded-xl ${
                  currentLevel.level === level.level ? 'bg-primary/10 border border-primary/20' : 'bg-muted/50'
                }`}>
                  <span className="text-xl">{level.icon}</span>
                  <div className="flex-1">
                    <div className="text-sm text-foreground">{t(level.label)}</div>
                    <div className="text-xs text-muted-foreground">
                      {level.level === 'none' ? t('student.progress.notYetBronze') : `${t('student.progress.cumulative')} ${level.minHours} ${t('common.hours')}`}
                    </div>
                  </div>
                  {currentLevel.level === level.level && (
                    <span className="text-xs text-primary font-medium">{t('student.progress.current')}</span>
                  )}
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
