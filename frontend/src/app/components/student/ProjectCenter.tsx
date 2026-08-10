import { useState } from 'react';
import { Search, X, MapPin, Clock, Users, ChevronDown, ChevronUp, SlidersHorizontal } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { PROJECT_STATUS_LABELS } from '../../lib/statusLabels';
import { type Project, type ProjectStatus } from '../../api/types';
import { toast } from 'sonner';
import { api } from '../../api/client';
import { ApiError, mapApiError } from '../../api/http';
import { usePagedApi } from '../../api/useApi';
import { useDictionaries } from '../../api/useDictionaries';
import { PageControls } from '../PageControls';
import { ListStateBanner } from '../ListStateBanner';
import { minutesToHours } from '../../lib/format';

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

export function ProjectCenter() {
  const { t } = useTranslation();
  const { serviceCategories } = useDictionaries();
  const { items, page, loading, error, setSearch: setPagedSearch, setFilter, setPage, refetch } = usePagedApi(api.projects.list, {});
  const [search, setSearch] = useState('');
  const [filterStatus, setFilterStatus] = useState<FilterStatus>('all');
  const [filterCategory, setFilterCategory] = useState('all');
  const [showMobileFilters, setShowMobileFilters] = useState(false);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [applied, setApplied] = useState<Set<string>>(new Set());

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

  const handleApply = async (proj: Project) => {
    if (applied.has(proj.id) || proj.myApplicationStatus) { toast.info(t('student.projects.alreadyApplied')); return; }
    if (proj.status !== 'registrationOpen') { toast.error(t('student.projects.notAccepting')); return; }
    try {
      await api.applications.create(proj.id);
      setApplied(prev => new Set([...prev, proj.id]));
      toast.success(t('student.projects.appliedSuccess', { title: proj.title }));
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
      setApplied(prev => { const next = new Set(prev); next.delete(proj.id); return next; });
      toast.success(t('student.projects.cancelSuccess'));
      refetch();
    } catch (err) {
      if (err instanceof ApiError) toast.error(mapApiError(err).message);
      else toast.error(t('common.unknownError'));
    } finally {
      setCancelling(null);
    }
  };

  const getApplyBtnText = (proj: Project) => {
    if (applied.has(proj.id) || proj.myApplicationStatus === 'pending') return t('student.projects.pendingConfirm');
    if (proj.myApplicationStatus === 'confirmed') return t('student.projects.registered');
    if (proj.myApplicationStatus === 'completed') return t('student.projects.completed');
    if (proj.myApplicationStatus === 'waitlisted') return t('student.projects.waitlist');
    if (proj.status !== 'registrationOpen') return t('student.projects.registrationClosed');
    if (proj.enrolledCount >= proj.capacity) return t('student.projects.joinWaitlist');
    return t('student.projects.registerNow');
  };

  const statusOptions = [
    { value: 'all', label: t('student.projects.all') },
    { value: 'canApply', label: t('student.projects.available') },
    { value: 'inProgress', label: t('student.projects.inProgress') },
    { value: 'completed', label: t('student.projects.completed') },
  ];

  const FilterPanel = () => (
    <div className="space-y-4">
      <div>
        <div className="text-xs text-muted-foreground font-medium mb-2">{t('student.projects.statusFilter')}</div>
        <div className="flex flex-col gap-1">
          {statusOptions.map(opt => (
            <button key={opt.value} onClick={() => applyStatusFilter(opt.value as FilterStatus)}
              className={`w-full text-left px-3 py-2 rounded-lg text-sm transition-colors ${
                filterStatus === opt.value ? 'bg-blue-100 text-blue-700 dark:bg-blue-500/20 dark:text-blue-300' : 'text-foreground hover:bg-accent'
              }`}>
              {opt.label}
            </button>
          ))}
        </div>
      </div>
      <div>
        <div className="text-xs text-muted-foreground font-medium mb-2">{t('student.projects.serviceCategory')}</div>
        <div className="flex flex-col gap-1">
          <button onClick={() => applyCategoryFilter('all')}
            className={`w-full text-left px-3 py-2 rounded-lg text-sm transition-colors ${
              filterCategory === 'all' ? 'bg-blue-100 text-blue-700 dark:bg-blue-500/20 dark:text-blue-300' : 'text-foreground hover:bg-accent'
            }`}>
            {t('student.projects.allCategories')}
          </button>
          {serviceCategories.map(cat => (
            <button key={cat.id} onClick={() => applyCategoryFilter(cat.id)}
              className={`w-full text-left px-3 py-2 rounded-lg text-sm transition-colors ${
                filterCategory === cat.id ? 'bg-blue-100 text-blue-700 dark:bg-blue-500/20 dark:text-blue-300' : 'text-foreground hover:bg-accent'
              }`}>
              {cat.name}
            </button>
          ))}
        </div>
      </div>
    </div>
  );

  if (loading && items.length === 0) return <div className="p-4 lg:p-6">{t('common.appName')}</div>;

  return (
    <div className="p-4 lg:p-6">
      <div className="mb-4 lg:mb-5 flex items-center justify-between">
        <div>
          <h2 className="text-foreground">{t('student.projects.title')}</h2>
          <p className="text-sm text-muted-foreground">{t('student.projects.count', { count: items.length })}</p>
        </div>
      </div>

      {/* Search bar */}
      <div className="flex gap-2 mb-4">
        <div className="flex-1 relative">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground" />
          <input type="text" value={search} onChange={e => applySearch(e.target.value)}
            placeholder={t('student.projects.searchPlaceholder')}
            className="w-full pl-9 pr-4 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground placeholder:text-muted-foreground text-sm focus:outline-none focus:ring-2 focus:ring-primary/50" />
          {search && (
            <button onClick={() => applySearch('')} className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground">
              <X className="w-3 h-3" />
            </button>
          )}
        </div>
        {/* Mobile filter toggle */}
        <button onClick={() => setShowMobileFilters(!showMobileFilters)}
          className={`lg:hidden flex items-center gap-1.5 px-3 py-2.5 rounded-xl border text-sm transition-colors ${
            showMobileFilters ? 'bg-[oklch(0.488_0.243_264.376)] text-white border-[oklch(0.488_0.243_264.376)]' : 'border-border text-muted-foreground hover:bg-accent'
          }`}>
          <SlidersHorizontal className="w-4 h-4" />
          {t('common.filter')}
        </button>
      </div>

      {/* Mobile filter panel */}
      {showMobileFilters && (
        <div className="lg:hidden bg-card border border-border rounded-xl p-4 mb-4">
          <div className="flex gap-2 flex-wrap mb-3">
            {statusOptions.map(opt => (
              <button key={opt.value} onClick={() => applyStatusFilter(opt.value as FilterStatus)}
                className={`px-3 py-1.5 rounded-lg text-xs transition-colors ${
                  filterStatus === opt.value ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground hover:bg-accent'
                }`}>
                {opt.label}
              </button>
            ))}
          </div>
          <div className="flex gap-2 flex-wrap">
            <button onClick={() => applyCategoryFilter('all')}
              className={`px-3 py-1.5 rounded-lg text-xs transition-colors ${filterCategory === 'all' ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground hover:bg-accent'}`}>
              {t('student.projects.allCategories')}
            </button>
            {serviceCategories.map(cat => (
              <button key={cat.id} onClick={() => applyCategoryFilter(cat.id)}
                className={`px-3 py-1.5 rounded-lg text-xs transition-colors ${filterCategory === cat.id ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground hover:bg-accent'}`}>
                {cat.name}
              </button>
            ))}
          </div>
        </div>
      )}

      {/* Desktop: sidebar + grid layout */}
      <div className="lg:flex lg:gap-6">

        {/* Desktop filter sidebar */}
        <aside className="hidden lg:block w-48 xl:w-56 shrink-0">
          <div className="bg-card border border-border rounded-2xl p-4 sticky top-4">
            <FilterPanel />
            {(filterStatus !== 'all' || filterCategory !== 'all') && (
              <button onClick={() => { applyStatusFilter('all'); applyCategoryFilter('all'); }}
                className="mt-3 w-full text-xs text-muted-foreground hover:text-destructive transition-colors text-center">
                {t('student.projects.clearFilter')}
              </button>
            )}
          </div>
        </aside>

        {/* Project grid */}
        <div className="flex-1 min-w-0">
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
              {items.map(proj => {
                const status = PROJECT_STATUS_LABELS[proj.status];
                const isExpanded = expandedId === proj.id;
                const btnText = getApplyBtnText(proj);
                const isApplied = applied.has(proj.id) || (proj.myApplicationStatus && ['confirmed', 'completed', 'waitlisted', 'pending'].includes(proj.myApplicationStatus));

                return (
                  <div key={proj.id} className="bg-card border border-border rounded-2xl overflow-hidden flex flex-col">
                    <div className="p-4 flex-1">
                      <div className="flex items-center gap-2 mb-2 flex-wrap">
                        <span className={`text-xs px-2 py-0.5 rounded-full ${colorMap[status.color] || colorMap.gray}`}>{t(status.label)}</span>
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
                        <div>{proj.startAt.slice(0, 10)}</div>
                      </div>

                      {isExpanded && (
                        <div className="border-t border-border pt-3 mb-3 space-y-1.5 text-xs text-muted-foreground">
                          <p><span className="font-medium text-foreground">{t('student.projects.details')}</span>{proj.description}</p>
                          <p><span className="font-medium text-foreground">{t('student.projects.organizer')}</span>{proj.organizationName}</p>
                          <p><span className="font-medium text-foreground">{t('student.projects.owner')}</span>{proj.ownerName}</p>
                          <p><span className="font-medium text-foreground">{t('student.projects.registrationDeadline')}</span>{proj.registrationCloseAt.slice(0, 10)}</p>
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
                        disabled={!!isApplied && proj.myApplicationStatus !== 'pending'}
                        className={`px-4 py-1.5 rounded-xl text-xs transition-colors ${
                          isApplied ? 'bg-muted text-muted-foreground cursor-default' :
                          proj.status === 'registrationOpen' ? 'bg-[oklch(0.488_0.243_264.376)] text-white hover:opacity-90' :
                          'bg-muted text-muted-foreground cursor-not-allowed'
                        }`}>
                        {btnText}
                      </button>
                      {proj.myApplicationStatus && ['pending', 'confirmed', 'waitlisted'].includes(proj.myApplicationStatus) && (
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
          <PageControls page={page} onPageChange={setPage} />
        </div>
      </div>
    </div>
  );
}
