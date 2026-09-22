import { useMemo, useState } from 'react';
import { Search, X, MapPin, Clock, Users, ChevronDown, ChevronUp } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { PROJECT_STATUS_LABELS } from '../../lib/statusLabels';
import { type Project, type ProjectStatus, type ApplicationStatus } from '../../api/types';
import { toast } from 'sonner';
import { api } from '../../api/client';
import { isoToLocalDate } from '../../lib/datetime';
import { ApiError, mapApiError } from '../../api/http';
import { usePagedApi } from '../../api/useApi';
import { useDictionaries } from '../../api/useDictionaries';
import { PageControls } from '../PageControls';
import { ListStateBanner } from '../ListStateBanner';
import { minutesToHours } from '../../lib/format';
import { ListSearchInput } from '../ListSearchInput';

const colorMap: Record<string, string> = {
  gray: 'bg-muted text-muted-foreground',
  blue: 'bg-blue-100 dark:bg-blue-900/30 text-blue-700 dark:text-blue-400',
  yellow: 'bg-yellow-100 dark:bg-yellow-900/30 text-yellow-700 dark:text-yellow-400',
  green: 'bg-green-100 dark:bg-green-900/30 text-green-700 dark:text-green-400',
  orange: 'bg-orange-100 dark:bg-orange-900/30 text-orange-700 dark:text-orange-400',
  red: 'bg-red-100 dark:bg-red-900/30 text-red-700 dark:text-red-400',
  purple: 'bg-purple-100 dark:bg-purple-900/30 text-purple-700 dark:text-purple-400',
};

type FilterStatus = ProjectStatus | 'all' | 'canApply';

// Same 3 fields/keys as ProjectManagement.tsx's admin view (Docs/optimization/05-SearchFilterSortConsistency.md) —
// `enrolledCount` has no backend column to sort on (ProjectController's SORTABLE set is
// id/startAt/registrationOpenAt/status/createdAt only), so it stays client-side, current-page-only.
/**
 * The statuses that mean "this student already holds a place (or a claim on one)".
 * CANCELLED is deliberately absent: a cancelled registration means they are *not*
 * registered and may join again — treating any truthy status as "already applied"
 * locked them out of the project permanently (issue 071).
 */
const ACTIVE_APPLICATION_STATUSES = ['pending', 'confirmed', 'waitlisted', 'completed'];

type SortKey = 'createdAt' | 'startAt' | 'enrolledCount';

export function ProjectCenter() {
  const { t } = useTranslation();
  const { serviceCategories } = useDictionaries();
  const { items, page, loading, error, params, setSearch: setPagedSearch, setFilter, setSort, setPage, setPageSize, refetch } = usePagedApi(api.projects.list, {});
  const [search, setSearch] = useState('');
  const [filterStatus, setFilterStatus] = useState<FilterStatus>('all');
  const [filterCategory, setFilterCategory] = useState('all');
  const [sortKey, setSortKey] = useState<SortKey>('createdAt');
  const [sortDesc, setSortDesc] = useState(true);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  // The *server-returned* status per project id, not a "did they press the
  // button" bit. `EnrollmentServiceImpl.register` returns CONFIRMED when under
  // capacity and WAITLISTED when full — it never returns PENDING on this path —
  // so inferring a status locally reported both real outcomes wrongly (issue 070).
  const [applied, setApplied] = useState<Map<string, ApplicationStatus>>(new Map());

  const applyStatusFilter = (val: FilterStatus) => {
    setFilterStatus(val);
    setFilter('status', val === 'all' ? undefined : val === 'canApply' ? 'registrationOpen' : val);
  };

  const applyCategoryFilter = (val: string) => {
    setFilterCategory(val);
    setFilter('categoryId', val === 'all' ? undefined : val);
  };

  const applySearch = (val: string) => {
    setSearch(val);
    setPagedSearch(val);
  };

  const handleSortClick = (key: SortKey) => {
    const nextDesc = sortKey === key ? !sortDesc : true;
    setSortKey(key);
    setSortDesc(nextDesc);
    setSort(nextDesc ? `-${key}` : key);
  };

  const handleApply = async (proj: Project) => {
    const current = applied.get(proj.id) ?? proj.myApplicationStatus;
    if (current && ACTIVE_APPLICATION_STATUSES.includes(current)) { toast.info(t('student.projects.alreadyApplied')); return; }
    if (proj.status !== 'registrationOpen') { toast.error(t('student.projects.notAccepting')); return; }
    try {
      const application = await api.applications.create(proj.id);
      setApplied(prev => new Map(prev).set(proj.id, application.status));
      toast.success(application.status === 'waitlisted'
        ? t('student.projects.waitlistedSuccess', { title: proj.title })
        : t('student.projects.appliedSuccess', { title: proj.title }));
      // Without this the seat count stays stale and `myApplicationStatus` never
      // arrives, so the Cancel button (gated on it) never appears — leaving the
      // student who just registered with no way to back out until a reload.
      refetch();
    } catch (err) {
      if (err instanceof ApiError) toast.error(mapApiError(err).message);
      else toast.error(t('common.unknownError'));
    }
  };

  // `applications.cancel` was fully wired in client.ts (I2-d6) but this
  // screen never called it — a student who registered had no way to back
  // out. Resolves the enrollment id via the scoped list (the backend
  // scopes /applications to the caller's own rows for a student
  // principal, so this never leaks another student's registration).
  const [cancelling, setCancelling] = useState<string | null>(null);
  const handleCancel = async (proj: Project) => {
    setCancelling(proj.id);
    try {
      const page = await api.applications.list({ filter: { projectId: proj.id }, pageSize: 1 });
      const application = page.data[0];
      if (!application) throw new Error('Application not found');
      await api.applications.cancel(application.id);
      setApplied(prev => { const next = new Map(prev); next.delete(proj.id); return next; });
      toast.success(t('student.projects.cancelSuccess'));
      refetch();
    } catch (err) {
      if (err instanceof ApiError) toast.error(mapApiError(err).message);
      else toast.error(t('common.unknownError'));
    } finally {
      setCancelling(null);
    }
  };

  /** Local (just-registered) status wins over the list's, which lags until `refetch()` lands. */
  const statusOf = (proj: Project) => applied.get(proj.id) ?? proj.myApplicationStatus;

  const getApplyBtnText = (proj: Project) => {
    const status = statusOf(proj);
    if (status === 'pending') return t('student.projects.pendingConfirm');
    if (status === 'confirmed') return t('student.projects.registered');
    if (status === 'completed') return t('student.projects.completed');
    if (status === 'waitlisted') return t('student.projects.waitlist');
    if (proj.status !== 'registrationOpen') return t('student.projects.registrationClosed');
    if (proj.enrolledCount >= proj.capacity) return t('student.projects.joinWaitlist');
    return t('student.projects.registerNow');
  };

  const statusOptions = [
    { value: 'all', label: t('student.projects.allStatuses') },
    { value: 'canApply', label: t('student.projects.available') },
    { value: 'inProgress', label: t('student.projects.inProgress') },
    { value: 'completed', label: t('student.projects.completed') },
  ];

  const sortOptions: { key: SortKey; label: string }[] = [
    { key: 'createdAt', label: t('student.projects.sortCreated') },
    { key: 'startAt', label: t('student.projects.sortStartAt') },
    { key: 'enrolledCount', label: t('student.projects.sortEnrolled') },
  ];

  // Filtering and sorting are both the server's (issue 159 §4b). `enrolledCount`
  // used to sort here, over whichever page had arrived — which answered "which
  // projects are filling up" with "which of these twenty are". It looked right
  // only while every project fit on one page.
  const sorted = items;

  if (loading && items.length === 0) return <div className="p-4 lg:p-6 text-muted-foreground">{t('common.loading')}</div>;

  return (
    <div className="p-4 lg:p-6 space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-foreground">{t('student.projects.title')}</h2>
          {/* `totalItems`, not `items.length` — the list is paged, so counting
              the rows on screen would have read "20 projects" for any school
              with more than twenty (issue 159 §4b). */}
          <p className="text-sm text-muted-foreground">{t('student.projects.count', { count: page?.totalItems ?? items.length })}</p>
        </div>
      </div>

      {/* Search & filters — Docs/optimization/05-SearchFilterSortConsistency.md §2.4: search alone below lg:,
          filters as an Nx1 grid alongside it, merged into one row at lg: and up. Same layout as
          ProjectManagement.tsx's admin view, minus the grade filter (not meaningful for a student's own view).

          Deliberately also minus the academic-year filter the admin view has (issue
          112). This list is pinned to the current academic year by the server for
          any student, whatever the request asks for, because last year's projects
          are not joinable and this screen exists to answer "what can I sign up
          for". A dropdown here would offer a choice the server overrules. */}
      <div className="flex flex-col gap-2 lg:flex-row lg:flex-wrap">
        <div className="relative lg:flex-1 lg:min-w-48">
          <ListSearchInput
            value={search}
            onCommit={applySearch}
            placeholder={t('student.projects.searchPlaceholder')}
          />
          {search && (
            <button onClick={() => applySearch('')} className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground">
              <X className="w-3 h-3" />
            </button>
          )}
        </div>
        <div className="grid grid-cols-2 gap-2 lg:contents">
          <select value={filterStatus} onChange={e => applyStatusFilter(e.target.value as FilterStatus)}
            className="px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none">
            {statusOptions.map(opt => <option key={opt.value} value={opt.value}>{opt.label}</option>)}
          </select>
          <select value={filterCategory} onChange={e => applyCategoryFilter(e.target.value)}
            className="px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none">
            <option value="all">{t('student.projects.allCategories')}</option>
            {serviceCategories.map(cat => <option key={cat.id} value={cat.id}>{cat.name}</option>)}
          </select>
        </div>
      </div>

      {/* Sort — always an Nx1 grid, no breakpoint condition (§2.5). */}
      <div className="grid grid-cols-3 gap-2">
        {sortOptions.map(({ key, label }) => (
          <button key={key} onClick={() => handleSortClick(key)}
            className={`px-3 py-1.5 rounded-lg text-xs flex items-center justify-center gap-1 transition-colors ${sortKey === key ? 'bg-blue-100 text-blue-700 dark:bg-blue-500/20 dark:text-blue-300' : 'bg-muted text-muted-foreground hover:bg-accent'}`}>
            {label} {sortKey === key && (sortDesc ? '↓' : '↑')}
          </button>
        ))}
      </div>

      {/* Project grid */}
      <div>
        <ListStateBanner error={error} empty={!loading && items.length === 0} emptyLabel={t('student.projects.noMatch')} />
        {!loading && !error && items.length === 0 && (search || filterStatus !== 'all' || filterCategory !== 'all') && (
          <div className="text-center -mt-8">
            <button onClick={() => { applySearch(''); applyStatusFilter('all'); applyCategoryFilter('all'); }}
              className="mt-2 text-xs text-primary hover:underline">
              {t('student.projects.clearAllFilters')}
            </button>
          </div>
        )}
        {items.length > 0 && (
          <div className="grid grid-cols-1 xl:grid-cols-2 gap-4">
            {sorted.map(proj => {
                const status = PROJECT_STATUS_LABELS[proj.status];
                const isExpanded = expandedId === proj.id;
                const btnText = getApplyBtnText(proj);
                const myStatus = statusOf(proj);
                const isApplied = !!myStatus && ACTIVE_APPLICATION_STATUSES.includes(myStatus);

                return (
                  <div key={proj.id} className="bg-card border border-border rounded-2xl overflow-hidden flex flex-col">
                    <div className="p-4 flex-1">
                      <div className="flex items-center gap-2 mb-2 flex-wrap">
                        <span className={`text-xs px-2 py-0.5 rounded-full whitespace-nowrap ${colorMap[status.color] || colorMap.gray}`}>{t(status.label)}</span>
                        <span className="text-xs text-muted-foreground">{proj.categoryName}</span>
                      </div>
                      <h3 className="text-sm text-foreground font-medium leading-snug mb-2">{proj.title}</h3>
                      <p className="text-xs text-muted-foreground mb-3 line-clamp-2">{proj.summary}</p>

                      <div className="grid grid-cols-2 gap-2 text-xs text-muted-foreground mb-3">
                        <div className="flex items-center gap-1.5">
                          <MapPin className="w-3 h-3 shrink-0" />
                          <span className="truncate">{proj.location}</span>
                        </div>
                        <div className="flex items-center gap-1.5">
                          <Clock className="w-3 h-3 shrink-0" />
                          <span>{minutesToHours(proj.recognizedMinutes)} {t('common.hours')}</span>
                        </div>
                        <div className="flex items-center gap-1.5">
                          <Users className="w-3 h-3 shrink-0" />
                          <span>{proj.enrolledCount}/{proj.capacity} {t('common.people')}</span>
                        </div>
                        <div>{isoToLocalDate(proj.startAt)}</div>
                      </div>

                      {isExpanded && (
                        <div className="border-t border-border pt-3 mb-3 space-y-1.5 text-xs text-muted-foreground">
                          <p><span className="font-medium text-foreground">{t('student.projects.details')}</span>{proj.description}</p>
                          <p><span className="font-medium text-foreground">{t('student.projects.organizer')}</span>{proj.organizationName}</p>
                          <p><span className="font-medium text-foreground">{t('student.projects.owner')}</span>{proj.ownerName}</p>
                          <p><span className="font-medium text-foreground">{t('student.projects.registrationDeadline')}</span>{isoToLocalDate(proj.registrationCloseAt)}</p>
                          <p><span className="font-medium text-foreground">{t('student.projects.evidenceReq')}</span>{proj.evidenceRequirements}</p>
                          <p><span className="font-medium text-foreground">{t('student.projects.eligibleGrades')}</span>{proj.eligibleGrades.join(', ')}</p>
                        </div>
                      )}
                    </div>

                    <div className="flex items-center justify-between px-4 py-3 border-t border-border">
                      <button onClick={() => setExpandedId(isExpanded ? null : proj.id)}
                        className="text-xs text-muted-foreground hover:text-foreground flex items-center gap-1">
                        {isExpanded ? <><ChevronUp className="w-3 h-3" /> {t('student.projects.collapse')}</> : <><ChevronDown className="w-3 h-3" /> {t('student.projects.viewDetails')}</>}
                      </button>
                      <button onClick={() => handleApply(proj)}
                        disabled={isApplied && myStatus !== 'pending'}
                        className={`px-4 py-1.5 rounded-xl text-xs transition-colors ${
                          isApplied ? 'bg-muted text-muted-foreground cursor-default' :
                          proj.status === 'registrationOpen' ? 'bg-[oklch(0.488_0.243_264.376)] text-white hover:opacity-90' :
                          'bg-muted text-muted-foreground cursor-not-allowed'
                        }`}>
                        {btnText}
                      </button>
                      {myStatus && ['pending', 'confirmed', 'waitlisted'].includes(myStatus) && (
                        <button onClick={() => handleCancel(proj)} disabled={cancelling === proj.id}
                          className="px-4 py-1.5 rounded-xl text-xs border border-border text-muted-foreground hover:bg-accent disabled:opacity-50">
                          {t('student.projects.cancelRegistration')}
                        </button>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        <PageControls page={page} onPageChange={setPage} pageSize={params.pageSize ?? 20} onPageSizeChange={setPageSize} />
      </div>
    </div>
  );
}
