import { useState, useEffect, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { Plus, Search, MapPin, Clock, Users, MoreHorizontal, CheckCircle, X, Edit, Archive } from 'lucide-react';
import { PROJECT_STATUS_LABELS } from '../../lib/statusLabels';
import { type Project, type ProjectStatus, type Organization, type Grade } from '../../api/types';
import { toast } from 'sonner';
import { api } from '../../api/client';
import { useFieldErrors, FieldError, handleMutationError } from '../../api/useFieldErrors';
import { usePermissions } from '../../App';
import { useDictionaries } from '../../api/useDictionaries';
import { localDatetimeLocalToISO, isoToLocalDatetimeLocal, isoToLocalDate } from '../../lib/datetime';
import { usePagedApi } from '../../api/useApi';
import { PageControls } from '../PageControls';
import { ListStateBanner } from '../ListStateBanner';
import { ProjectRegistrants } from './ProjectRegistrants';
import { SearchPicker, type SearchPickerOption } from '../SearchPicker';
import { GradePicker } from '../GradePicker';
import { minutesToHours } from '../../lib/format';
import { ListSearchInput } from '../ListSearchInput';

const colorMap: Record<string, string> = {
  gray:   'bg-muted text-muted-foreground',
  blue:   'bg-blue-100 dark:bg-blue-900/30 text-blue-700 dark:text-blue-400',
  yellow: 'bg-yellow-100 dark:bg-yellow-900/30 text-yellow-700 dark:text-yellow-400',
  green:  'bg-green-100 dark:bg-green-900/30 text-green-700 dark:text-green-400',
  orange: 'bg-orange-100 dark:bg-orange-900/30 text-orange-700 dark:text-orange-400',
  red:    'bg-red-100 dark:bg-red-900/30 text-red-700 dark:text-red-400',
  purple: 'bg-purple-100 dark:bg-purple-900/30 text-purple-700 dark:text-purple-400',
};

const INPUT = 'w-full px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-[oklch(0.488_0.243_264.376)]/40';
const LABEL = 'block text-xs text-muted-foreground mb-1.5';

// `/users/directory` (api.users.directory) only takes one role at a time — the
// project owner picker needs teacher-or-admin, so this fires both and merges,
// deduping by id (02-PorjectOrganization.md §3's implementation note: reuse
// the existing directory endpoint rather than building a new one).
async function fetchOwnerOptions(search: string): Promise<SearchPickerOption[]> {
  const [teachers, admins] = await Promise.all([
    api.users.directory('teacher', search),
    api.users.directory('admin', search),
  ]);
  const byId = new Map<string, SearchPickerOption>();
  for (const u of [...teachers, ...admins]) byId.set(u.id, { id: u.id, label: u.displayName });
  return [...byId.values()];
}

interface EditForm {
  title: string;
  summary: string;
  description: string;
  categoryId: string;
  organizationId: string;
  deliveryScope: 'onCampus' | 'offCampus' | 'hybrid';
  ownerId: string;
  ownerName: string;
  location: string;
  capacity: number;
  recognizedMinutes: number;
  startAt: string;
  endAt: string;
  registrationOpenAt: string;
  registrationCloseAt: string;
  eligibleGrades: string[];
  evidenceRequirements: string;
}

// `Project.eligibleGrades` (read) is a list of server-localized grade *names* (ProjectServiceImpl.toDto's
// `eligibleGradeNames`), but `UpdateProjectRequest.eligibleGradeIds` (write) expects real grade ids — convert
// on the way into the form so the checkboxes (keyed by id) and the save path (which sends ids straight
// through) agree; round-tripping names back as "ids" would silently corrupt the stored eligible-grade list.
function toForm(p: Project, grades: Grade[]): EditForm {
  return {
    title: p.title,
    summary: p.summary,
    description: p.description,
    categoryId: p.categoryId,
    organizationId: p.organizationId,
    deliveryScope: p.deliveryScope,
    ownerId: p.ownerId,
    ownerName: p.ownerName,
    location: p.location,
    capacity: p.capacity,
    recognizedMinutes: p.recognizedMinutes,
    startAt: isoToLocalDatetimeLocal(p.startAt),
    endAt: isoToLocalDatetimeLocal(p.endAt),
    registrationOpenAt: isoToLocalDatetimeLocal(p.registrationOpenAt),
    registrationCloseAt: isoToLocalDatetimeLocal(p.registrationCloseAt),
    eligibleGrades: p.eligibleGrades.map(name => grades.find(g => g.name === name)?.id ?? name),
    evidenceRequirements: p.evidenceRequirements,
  };
}

interface EditModalProps {
  project: Project;
  form: EditForm;
  onChange: (patch: Partial<EditForm>) => void;
  onClose: () => void;
  onSave: () => void;
  organizations: Organization[];
  getFieldError?: (...fields: string[]) => string | undefined;
  onFieldEdit?: (field: string) => void;
}

function EditModal({ project, form, onChange, onClose, onSave, organizations, getFieldError, onFieldEdit }: EditModalProps) {
  const { t } = useTranslation();
  const { grades, serviceCategories } = useDictionaries();
  const { hasPermission } = usePermissions();
  // Same admin-only gate the backend enforces (ownerId is silently ignored
  // from anyone else) — projects.archive/.cancel are admin-only permissions
  // role_teacher never holds, reused here rather than inventing a dedicated
  // "isAdmin" signal (02-PorjectOrganization.md §3).
  const canReassignOwner = hasPermission('projects.archive');
  const set = (patch: Partial<EditForm>) => onChange(patch);
  const fe = (field: string, ...aliases: string[]) => getFieldError?.(field, ...aliases);
  const edit = (field: string, patch: Partial<EditForm>) => { onFieldEdit?.(field); set(patch); };

  const toggleGrade = (g: string) => {
    set({ eligibleGrades: form.eligibleGrades.includes(g) ? form.eligibleGrades.filter(x => x !== g) : [...form.eligibleGrades, g] });
  };

  return (
    <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4">
      <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" onClick={onClose} />
      <div className="relative bg-card w-full sm:max-w-2xl sm:rounded-2xl rounded-t-2xl shadow-xl flex flex-col max-h-[92vh]">

        {/* Header */}
        <div className="flex items-center gap-3 px-5 py-4 border-b border-border shrink-0">
          <Edit className="w-5 h-5 text-[oklch(0.488_0.243_264.376)] shrink-0" />
          <div className="flex-1 min-w-0">
            <h3 className="text-foreground leading-tight">{t('admin.projects.editTitle')}</h3>
            <p className="text-xs text-muted-foreground truncate">{project.title}</p>
          </div>
          <button onClick={onClose} className="p-1.5 rounded-lg hover:bg-accent text-muted-foreground">
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Body */}
        <div className="overflow-y-auto px-5 py-5 space-y-5 text-sm">

          {/* Basic info */}
          <section className="space-y-3">
            <p className="text-xs font-medium text-muted-foreground uppercase tracking-wide">{t('admin.projects.basicInfo')}</p>

            <div>
              <label className={LABEL}>{t('admin.projects.projectName')}</label>
              <input type="text" value={form.title} onChange={e => edit('title', { title: e.target.value })} maxLength={255} className={INPUT} />
              <FieldError message={fe('title')} />
            </div>

            <div>
              <label className={LABEL}>{t('admin.projects.projectSummary')}</label>
              <textarea rows={2} value={form.summary} onChange={e => edit('summary', { summary: e.target.value })}
                maxLength={255} className={INPUT + ' resize-none'} />
              <FieldError message={fe('summary')} />
            </div>

            <div>
              <label className={LABEL}>{t('admin.projects.projectDescription')}</label>
              <textarea rows={3} value={form.description} onChange={e => edit('description', { description: e.target.value })}
                maxLength={4000} className={INPUT + ' resize-none'} />
              <FieldError message={fe('description')} />
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div>
                <label className={LABEL}>{t('admin.projects.serviceCategory')}</label>
                <select value={form.categoryId} onChange={e => edit('categoryId', { categoryId: e.target.value })} className={INPUT}>
                  {serviceCategories.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
                </select>
                <FieldError message={fe('categoryId')} />
              </div>
              <div>
                <label className={LABEL}>{t('admin.projects.partnerOrg')}</label>
                <select value={form.organizationId} onChange={e => edit('organizationId', { organizationId: e.target.value })} className={INPUT}>
                  <option value="">{t('admin.projects.selectOrg')}</option>
                  {organizations.filter(o => !o.isSchoolOrganization).map(o => <option key={o.id} value={o.id}>{o.name}</option>)}
                </select>
                <FieldError message={fe('organizationId')} />
              </div>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div>
                <label className={LABEL}>{t('admin.projects.deliveryScope')}</label>
                <select value={form.deliveryScope} onChange={e => edit('deliveryScope', { deliveryScope: e.target.value as EditForm['deliveryScope'] })} className={INPUT}>
                  <option value="onCampus">{t('admin.projects.deliveryScopeOnCampus')}</option>
                  <option value="offCampus">{t('admin.projects.deliveryScopeOffCampus')}</option>
                  <option value="hybrid">{t('admin.projects.deliveryScopeHybrid')}</option>
                </select>
                <FieldError message={fe('deliveryScope')} />
              </div>
            </div>

            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className={LABEL}>{t('admin.projects.location')}</label>
                <input type="text" value={form.location} onChange={e => edit('location', { location: e.target.value })} maxLength={255} className={INPUT} />
                <FieldError message={fe('location')} />
              </div>
              <div>
                <label className={LABEL}>{t('admin.projects.ownerName')}</label>
                {canReassignOwner ? (
                  <SearchPicker
                    selected={form.ownerId ? [{ id: form.ownerId, label: form.ownerName }] : []}
                    onChange={opts => { onFieldEdit?.('ownerId'); set(opts[0] ? { ownerId: opts[0].id, ownerName: opts[0].label } : { ownerId: '', ownerName: '' }); }}
                    fetchOptions={fetchOwnerOptions}
                    placeholder={t('admin.projects.ownerPlaceholder')}
                    searchPlaceholder={t('admin.projects.ownerSearchPlaceholder')}
                    noResultsLabel={t('student.proposals.noResults')}
                    loadingLabel={t('common.loading')}
                  />
                ) : (
                  <input type="text" value={form.ownerName} disabled className={INPUT + ' opacity-70'} />
                )}
                <FieldError message={fe('ownerId', 'ownerName')} />
              </div>
            </div>
          </section>

          {/* Time & capacity */}
          <section className="space-y-3 border-t border-border pt-4">
            <p className="text-xs font-medium text-muted-foreground uppercase tracking-wide">{t('admin.projects.timeCapacity')}</p>

            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className={LABEL}>{t('admin.projects.startAt')}</label>
                <input type="datetime-local" value={form.startAt} onChange={e => edit('startAt', { startAt: e.target.value })} className={INPUT} />
                <FieldError message={fe('startAt')} />
              </div>
              <div>
                <label className={LABEL}>{t('admin.projects.endAt')}</label>
                <input type="datetime-local" value={form.endAt} onChange={e => edit('endAt', { endAt: e.target.value })} className={INPUT} />
                <FieldError message={fe('endAt')} />
              </div>
            </div>

            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className={LABEL}>{t('admin.projects.regOpenAt')}</label>
                <input type="datetime-local" value={form.registrationOpenAt} onChange={e => edit('registrationOpenAt', { registrationOpenAt: e.target.value })} className={INPUT} />
                <FieldError message={fe('registrationOpenAt')} />
              </div>
              <div>
                <label className={LABEL}>{t('admin.projects.regCloseAt')}</label>
                <input type="datetime-local" value={form.registrationCloseAt} onChange={e => edit('registrationCloseAt', { registrationCloseAt: e.target.value })} className={INPUT} />
                <FieldError message={fe('registrationCloseAt')} />
              </div>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div>
                <label className={LABEL}>{t('admin.projects.capacity')}</label>
                <input type="number" min={1} value={form.capacity} onChange={e => edit('capacity', { capacity: Number(e.target.value) })} className={INPUT} />
                <FieldError message={fe('capacity')} />
              </div>
              <div>
                <label className={LABEL}>{t('admin.projects.verifiedHours')}</label>
                <input type="number" min={0.5} step={0.5} value={Math.round((form.recognizedMinutes / 60) * 100) / 100}
                  onChange={e => edit('recognizedMinutes', { recognizedMinutes: Math.round(Number(e.target.value) * 60) })} className={INPUT} />
                <FieldError message={fe('recognizedMinutes')} />
              </div>
            </div>
          </section>

          {/* Eligible grades */}
          <section className="space-y-3 border-t border-border pt-4">
            <p className="text-xs font-medium text-muted-foreground uppercase tracking-wide">{t('admin.projects.eligibleGrades')}</p>
            <GradePicker grades={grades} selected={form.eligibleGrades} onToggle={toggleGrade} />
          </section>

          {/* Evidence & status */}
          <section className="space-y-3 border-t border-border pt-4">
            <p className="text-xs font-medium text-muted-foreground uppercase tracking-wide">{t('admin.projects.evidenceStatus')}</p>

            <div>
              <label className={LABEL}>{t('admin.projects.evidenceReq')}</label>
              <textarea rows={2} value={form.evidenceRequirements} onChange={e => set({ evidenceRequirements: e.target.value })}
                maxLength={1000} className={INPUT + ' resize-none'} />
            </div>

            <div>
              <label className={LABEL}>{t('admin.projects.projectStatus')}</label>
              <div className="flex items-center gap-2">
                <span className={`text-xs px-2 py-0.5 rounded-full whitespace-nowrap ${colorMap[PROJECT_STATUS_LABELS[project.status].color]}`}>
                  {t(PROJECT_STATUS_LABELS[project.status].label)}
                </span>
                <span className="text-xs text-muted-foreground">{t('admin.projects.statusChangeHint')}</span>
              </div>
            </div>
          </section>
        </div>

        {/* Footer */}
        <div className="px-5 py-4 border-t border-border shrink-0 flex gap-3">
          <button onClick={onClose} className="flex-1 py-2.5 rounded-xl border border-border text-sm text-foreground hover:bg-accent">
            {t('common.cancel')}
          </button>
          <button onClick={onSave} className="flex-1 py-2.5 rounded-xl bg-[oklch(0.488_0.243_264.376)] text-white text-sm hover:opacity-90 transition-opacity">
            {t('admin.projects.saveChanges')}
          </button>
        </div>
      </div>
    </div>
  );
}

type SortKey = 'createdAt' | 'startAt' | 'enrolledCount';
// `enrolledCount` is a computed field with no backend column to sort on (ProjectController's SORTABLE
// set only allows id/startAt/registrationOpenAt/status/createdAt), so it stays client-side, current-page-only.
const CLIENT_ONLY_SORT_KEYS: SortKey[] = ['enrolledCount'];

export function ProjectManagement() {
  const { t } = useTranslation();
  const { grades, serviceCategories } = useDictionaries();
  const { hasPermission } = usePermissions();
  // Backend gates create/update/publish/archive/cancel on five distinct
  // permissions (ProjectController's @PreAuthorize) — role_teacher's seed
  // grant has create/update/publish but not archive/cancel, so a single
  // combined flag showed a teacher "Archive"/"Cancel" menu items that would
  // 403 on click. Split to match. (`projects.close` is retired — CLOSED is
  // system-only now, 02-PorjectOrganization.md §2.)
  const canManage = hasPermission('projects.create');
  const canUpdate = hasPermission('projects.update');
  const canPublish = hasPermission('projects.publish');
  const canArchive = hasPermission('projects.archive');
  const canCancel = hasPermission('projects.cancel');
  const canViewRegistrants = hasPermission('applications.read');
  const fieldErrors = useFieldErrors();
  const { items, page, loading, error, params, setFilter, setSearch: setPagedSearch, setSort, setPage, setPageSize, refetch } =
    usePagedApi<Project>(api.projects.list, {});

  const [projects, setProjects] = useState<Project[]>([]);
  // keep a local editable copy of the current page so admin edits/status actions reflect immediately without a refetch
  useEffect(() => { setProjects(items); }, [items]);

  const [organizations, setOrganizations] = useState<Organization[]>([]);
  const [schoolName, setSchoolName] = useState('');
  useEffect(() => {
    api.organizations.list({ pageSize: 100 }).then(result => setOrganizations(result.data)).catch(() => {});
    api.school.getInfo().then(info => setSchoolName(info.name)).catch(() => {});
  }, []);
  const [search, setSearch] = useState('');
  const [filterStatus, setFilterStatus] = useState<ProjectStatus | 'all'>('all');
  const [filterCategory, setFilterCategory] = useState('all');
  const [filterGrade, setFilterGrade] = useState('all');
  const [sortKey, setSortKey] = useState<SortKey>('createdAt');
  const [sortDesc, setSortDesc] = useState(true);
  const [showForm, setShowForm] = useState(false);
  const [newForm, setNewForm] = useState({
    title: '', summary: '', description: '', categoryId: 'cat_community', recognizedMinutes: 120,
    startAt: '', endAt: '', location: '', capacity: 20, organizationId: '', ownerId: '', ownerName: '',
    deliveryScope: 'onCampus' as 'onCampus' | 'offCampus' | 'hybrid',
    registrationOpenAt: '', registrationCloseAt: '', eligibleGrades: [] as string[], evidenceRequirements: '',
  });
  const [newFormSaving, setNewFormSaving] = useState(false);
  const [menuOpenId, setMenuOpenId] = useState<string | null>(null);
  const [editProject, setEditProject] = useState<Project | null>(null);
  const [editForm, setEditForm] = useState<EditForm | null>(null);
  const [registrantsProject, setRegistrantsProject] = useState<Project | null>(null);
  const [cancelProject, setCancelProject] = useState<Project | null>(null);
  const [cancelReason, setCancelReason] = useState('');
  const [cancelSaving, setCancelSaving] = useState(false);

  const handleSearchChange = (value: string) => {
    setSearch(value);
    setPagedSearch(value);
  };

  const handleStatusChange = (value: ProjectStatus | 'all') => {
    setFilterStatus(value);
    setFilter('status', value === 'all' ? undefined : value);
  };

  const handleCategoryChange = (value: string) => {
    setFilterCategory(value);
    setFilter('categoryId', value === 'all' ? undefined : value);
  };

  // Unlike Audit Center/Case Review/Proposal Review, projects have no
  // teacher-grade data scope at all (visibilitySpec on the backend is
  // draft-ownership only) — so unlike those pages, this dropdown is never
  // narrowed to the teacher's own grades; any teacher can filter to any
  // grade's projects.
  const handleGradeChange = (value: string) => {
    setFilterGrade(value);
    setFilter('gradeId', value === 'all' ? undefined : value);
  };

  const handleSortClick = (key: SortKey) => {
    const nextDesc = sortKey === key ? !sortDesc : true;
    setSortKey(key);
    setSortDesc(nextDesc);
    if (!CLIENT_ONLY_SORT_KEYS.includes(key)) setSort(nextDesc ? `-${key}` : key);
    else setSort(undefined);
  };

  // Search/status/category filtering happen server-side (see handle*Change above). Server-sortable keys
  // arrive pre-sorted in `projects`; enrolledCount has no backend column, so it's sorted locally, page-only.
  const sorted = useMemo(() => {
    if (!CLIENT_ONLY_SORT_KEYS.includes(sortKey)) return projects;
    return [...projects].sort((a, b) => {
      const av = a[sortKey] as number;
      const bv = b[sortKey] as number;
      return sortDesc ? bv - av : av - bv;
    });
  }, [projects, sortKey, sortDesc]);

  const openEdit = (proj: Project) => {
    setMenuOpenId(null);
    fieldErrors.clearAll();
    setEditProject(proj);
    setEditForm(toForm(proj, grades));
  };

  const buildNewProjectPayload = () => ({
    title: newForm.title, summary: newForm.summary, description: newForm.description,
    categoryId: newForm.categoryId, recognizedMinutes: newForm.recognizedMinutes,
    startAt: newForm.startAt ? localDatetimeLocalToISO(newForm.startAt) : '',
    endAt: newForm.endAt ? localDatetimeLocalToISO(newForm.endAt) : '',
    location: newForm.location, capacity: newForm.capacity, organizationId: newForm.organizationId || undefined,
    deliveryScope: newForm.deliveryScope,
    ownerId: newForm.ownerId || undefined,
    registrationOpenAt: newForm.registrationOpenAt ? localDatetimeLocalToISO(newForm.registrationOpenAt) : '',
    registrationCloseAt: newForm.registrationCloseAt ? localDatetimeLocalToISO(newForm.registrationCloseAt) : '',
    eligibleGrades: newForm.eligibleGrades, evidenceRequirements: newForm.evidenceRequirements,
  });

  const validateNewForm = () => {
    if (!newForm.title.trim()) { toast.error(t('admin.projects.errorNameEmpty')); return false; }
    if (newForm.capacity < 1) { toast.error(t('admin.projects.errorCapacityMin')); return false; }
    if (newForm.eligibleGrades.length === 0) { toast.error(t('admin.projects.errorNoGrade')); return false; }
    return true;
  };

  const saveEdit = async () => {
    if (!editProject || !editForm) return;
    if (!editForm.title.trim()) { toast.error(t('admin.projects.errorNameEmpty')); return; }
    if (editForm.capacity < 1) { toast.error(t('admin.projects.errorCapacityMin')); return; }
    if (editForm.eligibleGrades.length === 0) { toast.error(t('admin.projects.errorNoGrade')); return; }

    try {
      await api.projects.update(editProject.id, {
        title: editForm.title,
        summary: editForm.summary,
        description: editForm.description,
        categoryId: editForm.categoryId,
        organizationId: editForm.organizationId,
        deliveryScope: editForm.deliveryScope,
        ownerId: editForm.ownerId,
        location: editForm.location,
        capacity: editForm.capacity,
        recognizedMinutes: editForm.recognizedMinutes,
        startAt: editForm.startAt ? localDatetimeLocalToISO(editForm.startAt) : editProject.startAt,
        endAt: editForm.endAt ? localDatetimeLocalToISO(editForm.endAt) : editProject.endAt,
        registrationOpenAt: editForm.registrationOpenAt ? localDatetimeLocalToISO(editForm.registrationOpenAt) : editProject.registrationOpenAt,
        registrationCloseAt: editForm.registrationCloseAt ? localDatetimeLocalToISO(editForm.registrationCloseAt) : editProject.registrationCloseAt,
        eligibleGrades: editForm.eligibleGrades,
        evidenceRequirements: editForm.evidenceRequirements,
        version: editProject.version,
      });
      toast.success(t('admin.projects.toastUpdated', { title: editForm.title }));
      fieldErrors.clearAll();
      setEditProject(null);
      setEditForm(null);
      refetch();
      return;
    } catch (err) {
      handleMutationError(err, { fieldErrors, refetch, fallbackMessage: t('common.unknownError') });
    }
  };

  const handleAction = async (proj: Project, action: string) => {
    setMenuOpenId(null);
    if (action === 'edit') { openEdit(proj); return; }
    const labels: Record<string, string> = { publish: t('admin.projects.published'), archive: t('admin.projects.archived') };
    try {
      if (action === 'publish') await api.projects.publish(proj.id, proj.version);
      else if (action === 'archive') await api.projects.archive(proj.id, proj.version);
      toast.success(t('admin.projects.toastAction', { action: labels[action] || action, title: proj.title }));
      refetch();
    } catch (err) {
      handleMutationError(err, { refetch, fallbackMessage: t('common.unknownError') });
    }
  };

  const openCancel = (proj: Project) => {
    setMenuOpenId(null);
    setCancelReason('');
    setCancelProject(proj);
  };

  const submitCancel = async () => {
    if (!cancelProject || !cancelReason.trim()) { toast.error(t('admin.projects.cancelReasonRequired')); return; }
    setCancelSaving(true);
    try {
      await api.projects.cancel(cancelProject.id, cancelReason.trim(), cancelProject.version);
      toast.success(t('admin.projects.toastCancelled', { title: cancelProject.title }));
      setCancelProject(null);
      refetch();
    } catch (err) {
      handleMutationError(err, { refetch, fallbackMessage: t('common.unknownError') });
    } finally {
      setCancelSaving(false);
    }
  };

  if (loading && items.length === 0) return <div className="p-4 lg:p-6 text-muted-foreground">{t('common.loading')}</div>;

  return (
    <div className="p-4 lg:p-6 space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-foreground">{t('admin.projects.title')}</h1>
          <p className="text-sm text-muted-foreground">{t('admin.projects.count', { count: projects.length })}</p>
        </div>
        {canManage && (
          <button onClick={() => { fieldErrors.clearAll(); setShowForm(true); }}
            className="flex items-center gap-1.5 px-4 py-2 bg-[oklch(0.488_0.243_264.376)] text-white rounded-xl text-sm hover:opacity-90">
            <Plus className="w-4 h-4" /> {t('admin.projects.newProject')}
          </button>
        )}
      </div>

      {/* Search & filters — 05-SearchFilterSortConsistency.md §2.4: search alone below lg:, filters as an
          Nx1 grid alongside it, merged into one row at lg: and up. */}
      <div className="flex flex-col gap-2 lg:flex-row lg:flex-wrap">
        <div className="relative lg:flex-1 lg:min-w-48">
          <ListSearchInput
            value={search}
            onCommit={handleSearchChange}
            placeholder={t('admin.projects.searchPlaceholder')}
          />
        </div>
        <div className="grid grid-cols-3 gap-2 lg:contents">
          <select value={filterStatus} onChange={e => handleStatusChange(e.target.value as ProjectStatus | 'all')}
            className="px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none">
            <option value="all">{t('admin.projects.allStatuses')}</option>
            {Object.entries(PROJECT_STATUS_LABELS).map(([k, v]) => <option key={k} value={k}>{t(v.label)}</option>)}
          </select>
          <select value={filterCategory} onChange={e => handleCategoryChange(e.target.value)}
            className="px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none">
            <option value="all">{t('admin.projects.allCategories')}</option>
            {serviceCategories.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
          <select value={filterGrade} onChange={e => handleGradeChange(e.target.value)}
            className="px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none">
            <option value="all">{t('admin.projects.allGrades')}</option>
            {grades.map(g => <option key={g.id} value={g.id}>{g.name}</option>)}
          </select>
        </div>
      </div>

      {/* Sort — always an Nx1 grid, no breakpoint condition (§2.5). */}
      <div className="grid grid-cols-3 gap-2">
        {(
          [
            { key: 'createdAt' as SortKey, label: t('admin.projects.sortCreated') },
            { key: 'startAt' as SortKey, label: t('admin.projects.sortStartAt') },
            { key: 'enrolledCount' as SortKey, label: t('admin.projects.sortEnrolled') },
          ]
        ).map(({ key, label }) => (
          <button key={key} onClick={() => handleSortClick(key)}
            className={`px-3 py-1.5 rounded-lg text-xs flex items-center justify-center gap-1 transition-colors ${sortKey === key ? 'bg-blue-100 text-blue-700 dark:bg-blue-500/20 dark:text-blue-300' : 'bg-muted text-muted-foreground hover:bg-accent'}`}>
            {label} {sortKey === key && (sortDesc ? '↓' : '↑')}
          </button>
        ))}
      </div>

      <ListStateBanner error={error} empty={!loading && items.length === 0} emptyLabel="No projects" />

      {/* Project grid */}
      <div className="grid grid-cols-1 xl:grid-cols-2 gap-3">
        {sorted.map(proj => {
          const status = PROJECT_STATUS_LABELS[proj.status];
          const fillPct = (proj.enrolledCount / proj.capacity) * 100;

          return (
            <div key={proj.id} className="bg-card border border-border rounded-2xl p-4">
              <div className="flex items-start gap-3">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 mb-1 flex-wrap">
                    <span className={`text-xs px-2 py-0.5 rounded-full whitespace-nowrap ${colorMap[status.color]}`}>{t(status.label)}</span>
                    <span className="text-xs text-muted-foreground">{proj.categoryName}</span>
                  </div>
                  <div className="text-sm text-foreground font-medium mb-1">{proj.title}</div>
                  <div className="text-xs text-muted-foreground mb-2">{proj.summary}</div>

                  <div className="grid grid-cols-2 gap-2 text-xs text-muted-foreground mb-3">
                    <div className="flex items-center gap-1.5">
                      <MapPin className="w-3 h-3" />
                      <span className="truncate">{proj.location}</span>
                    </div>
                    <div className="flex items-center gap-1.5">
                      <Clock className="w-3 h-3" />
                      <span>{minutesToHours(proj.recognizedMinutes)}h</span>
                    </div>
                    <div className="flex items-center gap-1.5">
                      <Users className="w-3 h-3" />
                      <span>{proj.enrolledCount}/{proj.capacity} {t('common.people')}</span>
                    </div>
                    <div>{isoToLocalDate(proj.startAt)} → {isoToLocalDate(proj.endAt)}</div>
                  </div>

                  {proj.eligibleGrades.length > 0 && (
                    <div className="text-xs text-muted-foreground mb-3">
                      <span className="font-medium">{t('admin.projects.eligibleGrades')}:</span> {proj.eligibleGrades.join(', ')}
                    </div>
                  )}

                  <div className="mb-3">
                    <div className="flex justify-between text-xs text-muted-foreground mb-1">
                      <span>{t('admin.projects.registrationProgress')}</span>
                      <span>{Math.round(fillPct)}%</span>
                    </div>
                    <div className="h-1.5 bg-muted rounded-full overflow-hidden">
                      <div className="h-full bg-[oklch(0.488_0.243_264.376)] rounded-full" style={{ width: `${Math.min(fillPct, 100)}%` }} />
                    </div>
                  </div>
                </div>

                {/* Actions menu */}
                <div className="relative shrink-0">
                  <button onClick={() => setMenuOpenId(menuOpenId === proj.id ? null : proj.id)}
                    className="p-2 rounded-lg hover:bg-accent text-muted-foreground hover:text-foreground transition-colors">
                    <MoreHorizontal className="w-4 h-4" />
                  </button>
                  {menuOpenId === proj.id && (
                    <div className="absolute right-0 top-8 bg-card border border-border rounded-xl shadow-lg z-10 w-48 overflow-hidden">
                      {canViewRegistrants && proj.status !== 'draft' && (
                        <button onClick={() => { setMenuOpenId(null); setRegistrantsProject(proj); }}
                          className="w-full flex items-center gap-2 px-4 py-2.5 text-sm text-foreground hover:bg-accent">
                          <Users className="w-3.5 h-3.5" /> {t('admin.projects.registrants')}
                        </button>
                      )}
                      {canUpdate && (
                        <button onClick={() => handleAction(proj, 'edit')}
                          className="w-full flex items-center gap-2 px-4 py-2.5 text-sm text-foreground hover:bg-accent">
                          <Edit className="w-3.5 h-3.5" /> {t('admin.projects.edit')}
                        </button>
                      )}
                      {canPublish && proj.status === 'draft' && (
                        <button onClick={() => handleAction(proj, 'publish')}
                          className="w-full flex items-center gap-2 px-4 py-2.5 text-sm text-green-600 hover:bg-green-50 dark:hover:bg-green-900/20">
                          <CheckCircle className="w-3.5 h-3.5" /> {t('admin.projects.publish')}
                        </button>
                      )}
                      {canArchive && proj.status === 'completed' && (
                        <button onClick={() => handleAction(proj, 'archive')}
                          className="w-full flex items-center gap-2 px-4 py-2.5 text-sm text-muted-foreground hover:bg-accent">
                          <Archive className="w-3.5 h-3.5" /> {t('admin.projects.archive')}
                        </button>
                      )}
                      {canCancel && proj.status !== 'archived' && (
                        <button onClick={() => openCancel(proj)}
                          className="w-full flex items-center gap-2 px-4 py-2.5 text-sm text-red-600 hover:bg-red-50 dark:hover:bg-red-900/20">
                          <X className="w-3.5 h-3.5" /> {t('admin.projects.cancelAndArchive')}
                        </button>
                      )}
                    </div>
                  )}
                </div>
              </div>

              {/* Quick info footer */}
              <div className="flex items-center gap-2 text-xs text-muted-foreground border-t border-border pt-3">
                <span>{t('admin.projects.owner')}{proj.ownerName}</span>
                <span>·</span>
                <span>{t('admin.projects.regCloses')}: {isoToLocalDate(proj.registrationCloseAt)}</span>
              </div>
            </div>
          );
        })}
      </div>
      <PageControls page={page} onPageChange={setPage} pageSize={params.pageSize ?? 20} onPageSizeChange={setPageSize} />

      {/* Edit modal */}
      {editProject && editForm && (
        <EditModal
          project={editProject}
          form={editForm}
          onChange={patch => setEditForm(f => f && ({ ...f, ...patch }))}
          onClose={() => { setEditProject(null); setEditForm(null); }}
          onSave={saveEdit}
          organizations={organizations}
          getFieldError={fieldErrors.getError}
          onFieldEdit={fieldErrors.clearField}
        />
      )}

      {/* New project modal */}
      {showForm && (
        <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center">
          <div className="absolute inset-0 bg-foreground/20 backdrop-blur-sm" onClick={() => setShowForm(false)} />
          <div className="relative bg-card border border-border rounded-2xl p-6 w-full sm:max-w-2xl max-w-[calc(100%-2rem)] mx-4 mb-4 sm:mb-0 z-50 max-h-[80vh] overflow-y-auto">
            <h3 className="text-foreground mb-4">{t('admin.projects.newProjectTitle')}</h3>
            <div className="space-y-3">
              <div>
                <label className={LABEL}>{t('admin.projects.projectName')}</label>
                <input type="text" value={newForm.title} onChange={e => { fieldErrors.clearField('title'); setNewForm(f => ({ ...f, title: e.target.value })); }} placeholder={t('admin.projects.newProjectPlaceholder', { school: schoolName })} maxLength={255} className={INPUT} />
                <FieldError message={fieldErrors.getError('title')} />
              </div>
              <div>
                <label className={LABEL}>{t('admin.projects.projectSummary')}</label>
                <textarea rows={3} value={newForm.summary} onChange={e => { fieldErrors.clearField('summary'); setNewForm(f => ({ ...f, summary: e.target.value })); }} placeholder={t('admin.projects.summaryPlaceholder')} maxLength={255} className={INPUT + ' resize-none'} />
                <FieldError message={fieldErrors.getError('summary')} />
              </div>
              <div>
                <label className={LABEL}>{t('admin.projects.projectDescription')}</label>
                <textarea rows={3} value={newForm.description} onChange={e => { fieldErrors.clearField('description'); setNewForm(f => ({ ...f, description: e.target.value })); }} maxLength={4000} className={INPUT + ' resize-none'} />
                <FieldError message={fieldErrors.getError('description')} />
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className={LABEL}>{t('admin.projects.serviceCategory')}</label>
                  <select value={newForm.categoryId} onChange={e => setNewForm(f => ({ ...f, categoryId: e.target.value }))} className={INPUT}>
                    {serviceCategories.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
                  </select>
                </div>
                <div>
                  <label className={LABEL}>{t('admin.projects.verifiedHours')}</label>
                  <input type="number" min="0.5" step="0.5" value={Math.round((newForm.recognizedMinutes / 60) * 100) / 100} onChange={e => setNewForm(f => ({ ...f, recognizedMinutes: Number(e.target.value) * 60 }))} placeholder="2" className={INPUT} />
                </div>
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className={LABEL}>{t('admin.projects.startAt')}</label>
                  <input type="datetime-local" value={newForm.startAt} onChange={e => setNewForm(f => ({ ...f, startAt: e.target.value }))} className={INPUT} />
                </div>
                <div>
                  <label className={LABEL}>{t('admin.projects.endAt')}</label>
                  <input type="datetime-local" value={newForm.endAt} onChange={e => setNewForm(f => ({ ...f, endAt: e.target.value }))} className={INPUT} />
                </div>
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className={LABEL}>{t('admin.projects.regOpenAt')}</label>
                  <input type="datetime-local" value={newForm.registrationOpenAt} onChange={e => setNewForm(f => ({ ...f, registrationOpenAt: e.target.value }))} className={INPUT} />
                </div>
                <div>
                  <label className={LABEL}>{t('admin.projects.regCloseAt')}</label>
                  <input type="datetime-local" value={newForm.registrationCloseAt} onChange={e => setNewForm(f => ({ ...f, registrationCloseAt: e.target.value }))} className={INPUT} />
                </div>
              </div>
              <div>
                <label className={LABEL}>{t('admin.projects.location')}</label>
                <input type="text" value={newForm.location} onChange={e => setNewForm(f => ({ ...f, location: e.target.value }))} placeholder={t('admin.projects.locationPlaceholder')} maxLength={255} className={INPUT} />
              </div>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div>
                  <label className={LABEL}>{t('admin.projects.capacity')}</label>
                  <input type="number" min="1" value={newForm.capacity} onChange={e => setNewForm(f => ({ ...f, capacity: Number(e.target.value) }))} placeholder="20" className={INPUT} />
                </div>
                <div>
                  <label className={LABEL}>{t('admin.projects.partnerOrg')}</label>
                  <select value={newForm.organizationId} onChange={e => setNewForm(f => ({ ...f, organizationId: e.target.value }))} className={INPUT}>
                    <option value="">{t('admin.projects.selectOrg')}</option>
                    {organizations.filter(o => !o.isSchoolOrganization).map(o => <option key={o.id} value={o.id}>{o.name}</option>)}
                  </select>
                </div>
              </div>
              <div>
                <label className={LABEL}>{t('admin.projects.deliveryScope')}</label>
                <select value={newForm.deliveryScope} onChange={e => setNewForm(f => ({ ...f, deliveryScope: e.target.value as typeof newForm.deliveryScope }))} className={INPUT}>
                  <option value="onCampus">{t('admin.projects.deliveryScopeOnCampus')}</option>
                  <option value="offCampus">{t('admin.projects.deliveryScopeOffCampus')}</option>
                  <option value="hybrid">{t('admin.projects.deliveryScopeHybrid')}</option>
                </select>
              </div>
              {canArchive && (
                <div>
                  <label className={LABEL}>{t('admin.projects.ownerName')}</label>
                  <SearchPicker
                    selected={newForm.ownerId ? [{ id: newForm.ownerId, label: newForm.ownerName }] : []}
                    onChange={opts => setNewForm(f => ({ ...f, ownerId: opts[0]?.id ?? '', ownerName: opts[0]?.label ?? '' }))}
                    fetchOptions={fetchOwnerOptions}
                    placeholder={t('admin.projects.ownerPlaceholderSelf')}
                    searchPlaceholder={t('admin.projects.ownerSearchPlaceholder')}
                    noResultsLabel={t('student.proposals.noResults')}
                    loadingLabel={t('common.loading')}
                  />
                </div>
              )}
              <div>
                <label className={LABEL}>{t('admin.projects.eligibleGrades')}</label>
                <GradePicker grades={grades} selected={newForm.eligibleGrades}
                  onToggle={g => setNewForm(f => ({ ...f, eligibleGrades: f.eligibleGrades.includes(g) ? f.eligibleGrades.filter(x => x !== g) : [...f.eligibleGrades, g] }))} />
              </div>
              <div>
                <label className={LABEL}>{t('admin.projects.evidenceReq')}</label>
                <textarea rows={2} value={newForm.evidenceRequirements} onChange={e => setNewForm(f => ({ ...f, evidenceRequirements: e.target.value }))} maxLength={1000} className={INPUT + ' resize-none'} />
              </div>
            </div>
            <div className="flex gap-3 mt-5">
              <button onClick={() => setShowForm(false)} disabled={newFormSaving}
                className="flex-1 py-3 rounded-xl border border-border text-foreground hover:bg-accent text-sm">{t('common.cancel')}</button>
              <button disabled={newFormSaving} onClick={async () => {
                if (!validateNewForm()) return;
                setNewFormSaving(true);
                try {
                  await api.projects.create(buildNewProjectPayload());
                  fieldErrors.clearAll();
                  toast.success(t('admin.projects.toastDraftCreated'));
                  setShowForm(false);
                  refetch();
                } catch (err) { handleMutationError(err, { fieldErrors, refetch, fallbackMessage: t('common.unknownError') }); }
                finally { setNewFormSaving(false); }
              }}
                className="flex-1 py-3 rounded-xl border border-border text-foreground hover:bg-accent text-sm disabled:opacity-50">{newFormSaving ? t('common.saving', { defaultValue: 'Saving...' }) : t('admin.projects.saveDraft')}</button>
              <button disabled={newFormSaving} onClick={async () => {
                if (!validateNewForm()) return;
                setNewFormSaving(true);
                try {
                  const proj = await api.projects.create(buildNewProjectPayload());
                  await api.projects.publish(proj.id, proj.version);
                  fieldErrors.clearAll();
                  toast.success(t('admin.projects.toastPublished'));
                  setShowForm(false);
                  refetch();
                } catch (err) { handleMutationError(err, { fieldErrors, refetch, fallbackMessage: t('common.unknownError') }); }
                finally { setNewFormSaving(false); }
              }}
                className="flex-1 py-3 rounded-xl bg-[oklch(0.488_0.243_264.376)] text-white hover:opacity-90 text-sm disabled:opacity-50">{newFormSaving ? t('common.publishing', { defaultValue: 'Publishing...' }) : t('admin.projects.publish')}</button>
            </div>
          </div>
        </div>
      )}

      {menuOpenId && <div className="fixed inset-0 z-5" onClick={() => setMenuOpenId(null)} />}

      {registrantsProject && (
        <ProjectRegistrants projectId={registrantsProject.id} projectTitle={registrantsProject.title} onClose={() => setRegistrantsProject(null)} />
      )}

      {cancelProject && (
        <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4">
          <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" onClick={() => !cancelSaving && setCancelProject(null)} />
          <div className="relative bg-card w-full sm:max-w-md sm:rounded-2xl rounded-t-2xl shadow-xl p-5 space-y-4">
            <div className="flex items-center gap-3">
              <X className="w-5 h-5 text-red-600 shrink-0" />
              <div className="flex-1 min-w-0">
                <h2 className="text-foreground font-medium">{t('admin.projects.cancelAndArchive')}</h2>
                <p className="text-xs text-muted-foreground truncate">{cancelProject.title}</p>
              </div>
            </div>
            <p className="text-sm text-muted-foreground">{t('admin.projects.cancelWarning')}</p>
            <div>
              <label className={LABEL}>{t('admin.projects.cancelReasonLabel')}</label>
              {/* Lands in audit_log.reason_code (VARCHAR(64)), not a project column — Docs/issues/036-*.md. */}
              <textarea rows={3} value={cancelReason} onChange={e => setCancelReason(e.target.value)}
                placeholder={t('admin.projects.cancelReasonPlaceholder')} maxLength={64} className={INPUT + ' resize-none'} />
            </div>
            <div className="flex gap-2">
              <button onClick={() => setCancelProject(null)} disabled={cancelSaving}
                className="flex-1 py-2.5 rounded-xl border border-border text-foreground hover:bg-accent text-sm disabled:opacity-50">
                {t('admin.projects.cancel')}
              </button>
              <button onClick={submitCancel} disabled={cancelSaving || !cancelReason.trim()}
                className="flex-1 py-2.5 rounded-xl bg-red-600 text-white hover:opacity-90 text-sm disabled:opacity-50">
                {cancelSaving ? t('common.saving', { defaultValue: 'Saving...' }) : t('admin.projects.cancelAndArchive')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
