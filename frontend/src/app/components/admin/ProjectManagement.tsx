import { useState, useEffect, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { Plus, Search, MapPin, Clock, Users, MoreHorizontal, CheckCircle, X, Edit, Archive } from 'lucide-react';
import { PROJECT_STATUS_LABELS, SERVICE_CATEGORIES, GRADES, GRADE_LABEL_KEYS, resolveCategoryName } from '../../mock/data';
import { type Project, type ProjectStatus, type Organization } from '../../api/types';
import { toast } from 'sonner';
import { api } from '../../api/client';

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

interface EditForm {
  title: string;
  summary: string;
  description: string;
  categoryId: string;
  organizationId: string;
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
  status: ProjectStatus;
}

function toForm(p: Project): EditForm {
  return {
    title: p.title,
    summary: p.summary,
    description: p.description,
    categoryId: p.categoryId,
    organizationId: p.organizationId,
    ownerName: p.ownerName,
    location: p.location,
    capacity: p.capacity,
    recognizedMinutes: p.recognizedMinutes,
    startAt: p.startAt.slice(0, 16),
    endAt: p.endAt.slice(0, 16),
    registrationOpenAt: p.registrationOpenAt.slice(0, 16),
    registrationCloseAt: p.registrationCloseAt.slice(0, 16),
    eligibleGrades: [...p.eligibleGrades],
    evidenceRequirements: p.evidenceRequirements,
    status: p.status,
  };
}

interface EditModalProps {
  project: Project;
  form: EditForm;
  onChange: (patch: Partial<EditForm>) => void;
  onClose: () => void;
  onSave: () => void;
  organizations: Organization[];
}

function EditModal({ project, form, onChange, onClose, onSave, organizations }: EditModalProps) {
  const { t, i18n } = useTranslation();
  const set = (patch: Partial<EditForm>) => onChange(patch);

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
              <input type="text" value={form.title} onChange={e => set({ title: e.target.value })} className={INPUT} />
            </div>

            <div>
              <label className={LABEL}>{t('admin.projects.projectSummary')}</label>
              <textarea rows={2} value={form.summary} onChange={e => set({ summary: e.target.value })}
                className={INPUT + ' resize-none'} />
            </div>

            <div>
              <label className={LABEL}>{t('admin.projects.projectDescription')}</label>
              <textarea rows={3} value={form.description} onChange={e => set({ description: e.target.value })}
                className={INPUT + ' resize-none'} />
            </div>

            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className={LABEL}>{t('admin.projects.serviceCategory')}</label>
                <select value={form.categoryId} onChange={e => set({ categoryId: e.target.value })} className={INPUT}>
                  {SERVICE_CATEGORIES.map(c => <option key={c.id} value={c.id}>{resolveCategoryName(c.id, i18n.language)}</option>)}
                </select>
              </div>
              <div>
                <label className={LABEL}>{t('admin.projects.partnerOrg')}</label>
                <select value={form.organizationId} onChange={e => set({ organizationId: e.target.value })} className={INPUT}>
                  {organizations.map(o => <option key={o.id} value={o.id}>{o.name}</option>)}
                </select>
              </div>
            </div>

            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className={LABEL}>{t('admin.projects.location')}</label>
                <input type="text" value={form.location} onChange={e => set({ location: e.target.value })} className={INPUT} />
              </div>
              <div>
                <label className={LABEL}>{t('admin.projects.ownerName')}</label>
                <input type="text" value={form.ownerName} onChange={e => set({ ownerName: e.target.value })} className={INPUT} />
              </div>
            </div>
          </section>

          {/* Time & capacity */}
          <section className="space-y-3 border-t border-border pt-4">
            <p className="text-xs font-medium text-muted-foreground uppercase tracking-wide">{t('admin.projects.timeCapacity')}</p>

            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className={LABEL}>{t('admin.projects.startAt')}</label>
                <input type="datetime-local" value={form.startAt} onChange={e => set({ startAt: e.target.value })} className={INPUT} />
              </div>
              <div>
                <label className={LABEL}>{t('admin.projects.endAt')}</label>
                <input type="datetime-local" value={form.endAt} onChange={e => set({ endAt: e.target.value })} className={INPUT} />
              </div>
            </div>

            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className={LABEL}>{t('admin.projects.regOpenAt')}</label>
                <input type="datetime-local" value={form.registrationOpenAt} onChange={e => set({ registrationOpenAt: e.target.value })} className={INPUT} />
              </div>
              <div>
                <label className={LABEL}>{t('admin.projects.regCloseAt')}</label>
                <input type="datetime-local" value={form.registrationCloseAt} onChange={e => set({ registrationCloseAt: e.target.value })} className={INPUT} />
              </div>
            </div>

            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className={LABEL}>{t('admin.projects.capacity')}</label>
                <input type="number" min={1} value={form.capacity} onChange={e => set({ capacity: Number(e.target.value) })} className={INPUT} />
              </div>
              <div>
                <label className={LABEL}>{t('admin.projects.verifiedHours')}</label>
                <input type="number" min={0.5} step={0.5} value={form.recognizedMinutes / 60}
                  onChange={e => set({ recognizedMinutes: Math.round(Number(e.target.value) * 60) })} className={INPUT} />
              </div>
            </div>
          </section>

          {/* Eligible grades */}
          <section className="space-y-3 border-t border-border pt-4">
            <p className="text-xs font-medium text-muted-foreground uppercase tracking-wide">{t('admin.projects.eligibleGrades')}</p>
            <div className="flex flex-wrap gap-2">
              {GRADES.map(g => (
                <button key={g} type="button" onClick={() => toggleGrade(g)}
                  className={`px-3 py-1.5 rounded-lg text-xs border transition-colors ${
                    form.eligibleGrades.includes(g)
                      ? 'bg-[oklch(0.488_0.243_264.376)] text-white border-[oklch(0.488_0.243_264.376)]'
                      : 'border-border text-muted-foreground hover:bg-accent'
                  }`}>
                  {t(GRADE_LABEL_KEYS[g])}
                </button>
              ))}
            </div>
          </section>

          {/* Evidence & status */}
          <section className="space-y-3 border-t border-border pt-4">
            <p className="text-xs font-medium text-muted-foreground uppercase tracking-wide">{t('admin.projects.evidenceStatus')}</p>

            <div>
              <label className={LABEL}>{t('admin.projects.evidenceReq')}</label>
              <textarea rows={2} value={form.evidenceRequirements} onChange={e => set({ evidenceRequirements: e.target.value })}
                className={INPUT + ' resize-none'} />
            </div>

            <div>
              <label className={LABEL}>{t('admin.projects.projectStatus')}</label>
              <select value={form.status} onChange={e => set({ status: e.target.value as ProjectStatus })} className={INPUT}>
                {Object.entries(PROJECT_STATUS_LABELS).map(([k, v]) => (
                  <option key={k} value={k}>{t(v.label)}</option>
                ))}
              </select>
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

export function ProjectManagement() {
  const { t, i18n } = useTranslation();
  const [projects, setProjects] = useState<Project[]>([]);
  const [organizations, setOrganizations] = useState<Organization[]>([]);
  const [schoolName, setSchoolName] = useState('');
  useEffect(() => {
    api.projects.list().then(setProjects).catch(() => {});
    api.organizations.list().then(setOrganizations).catch(() => {});
    api.school.getInfo().then(info => setSchoolName(info.name)).catch(() => {});
  }, []);
  const [search, setSearch] = useState('');
  const [filterStatus, setFilterStatus] = useState<ProjectStatus | 'all'>('all');
  const [filterCategory, setFilterCategory] = useState('all');
  const [sortKey, setSortKey] = useState<'createdAt' | 'startAt' | 'enrolledCount'>('createdAt');
  const [sortDesc, setSortDesc] = useState(true);
  const [showForm, setShowForm] = useState(false);
  const [menuOpenId, setMenuOpenId] = useState<string | null>(null);
  const [editProject, setEditProject] = useState<Project | null>(null);
  const [editForm, setEditForm] = useState<EditForm | null>(null);

  const filtered = useMemo(() => {
    let items = projects.filter(p => {
      const matchSearch = !search || p.title.includes(search) || p.organizationName.includes(search);
      const matchStatus = filterStatus === 'all' || p.status === filterStatus;
      const matchCat = filterCategory === 'all' || p.categoryId === filterCategory;
      return matchSearch && matchStatus && matchCat;
    });
    return [...items].sort((a, b) => {
      const av = a[sortKey] as string | number;
      const bv = b[sortKey] as string | number;
      return sortDesc ? (bv > av ? 1 : -1) : (av > bv ? 1 : -1);
    });
  }, [projects, search, filterStatus, filterCategory, sortKey, sortDesc]);

  const openEdit = (proj: Project) => {
    setMenuOpenId(null);
    setEditProject(proj);
    setEditForm(toForm(proj));
  };

  const saveEdit = () => {
    if (!editProject || !editForm) return;
    if (!editForm.title.trim()) { toast.error(t('admin.projects.errorNameEmpty')); return; }
    if (editForm.capacity < 1) { toast.error(t('admin.projects.errorCapacityMin')); return; }
    if (editForm.eligibleGrades.length === 0) { toast.error(t('admin.projects.errorNoGrade')); return; }

    const category = SERVICE_CATEGORIES.find(c => c.id === editForm.categoryId);
    const org = organizations.find(o => o.id === editForm.organizationId);

    setProjects(prev => prev.map(p => p.id === editProject.id ? {
      ...p,
      ...editForm,
      categoryName: category ? resolveCategoryName(category.id, i18n.language) : p.categoryName,
      organizationName: org?.name ?? p.organizationName,
      startAt: editForm.startAt + ':00',
      endAt: editForm.endAt + ':00',
      registrationOpenAt: editForm.registrationOpenAt + ':00',
      registrationCloseAt: editForm.registrationCloseAt + ':00',
      updatedAt: new Date().toISOString(),
    } : p));

    toast.success(t('admin.projects.toastUpdated', { title: editForm.title }));
    setEditProject(null);
    setEditForm(null);
  };

  const handleAction = (proj: Project, action: string) => {
    setMenuOpenId(null);
    if (action === 'edit') { openEdit(proj); return; }
    const statusMap: Partial<Record<string, ProjectStatus>> = { publish: 'published', close: 'closed', archive: 'archived' };
    const newStatus = statusMap[action];
    if (newStatus) {
      setProjects(prev => prev.map(p => p.id === proj.id ? { ...p, status: newStatus } : p));
    }
    const labels: Record<string, string> = { publish: t('admin.projects.published'), close: t('admin.projects.closed'), archive: t('admin.projects.archived') };
    toast.success(t('admin.projects.toastAction', { action: labels[action] || action, title: proj.title }));
  };

  return (
    <div className="p-4 lg:p-6 space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-foreground">{t('admin.projects.title')}</h1>
          <p className="text-sm text-muted-foreground">{t('admin.projects.count', { count: projects.length })}</p>
        </div>
        <button onClick={() => setShowForm(true)}
          className="flex items-center gap-1.5 px-4 py-2 bg-[oklch(0.488_0.243_264.376)] text-white rounded-xl text-sm hover:opacity-90">
          <Plus className="w-4 h-4" /> {t('admin.projects.newProject')}
        </button>
      </div>

      {/* Search & filters */}
      <div className="flex gap-2 flex-wrap">
        <div className="relative flex-1 min-w-48">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground" />
          <input type="text" value={search} onChange={e => setSearch(e.target.value)}          placeholder={t('admin.projects.searchPlaceholder')}
            className="w-full pl-9 pr-4 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-primary/50" />
        </div>
        <select value={filterStatus} onChange={e => setFilterStatus(e.target.value as ProjectStatus | 'all')}
          className="px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none">
          <option value="all">{t('admin.projects.allStatuses')}</option>
          {Object.entries(PROJECT_STATUS_LABELS).map(([k, v]) => <option key={k} value={k}>{t(v.label)}</option>)}
        </select>
        <select value={filterCategory} onChange={e => setFilterCategory(e.target.value)}
          className="px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none">
          <option value="all">{t('admin.projects.allCategories')}</option>
          {SERVICE_CATEGORIES.map(c => <option key={c.id} value={c.id}>{resolveCategoryName(c.id, i18n.language)}</option>)}
        </select>
      </div>

      {/* Sort */}
      <div className="flex gap-2">
        {[
          { key: 'createdAt', label: t('admin.projects.sortCreated') },
          { key: 'startAt', label: t('admin.projects.sortStartAt') },
          { key: 'enrolledCount', label: t('admin.projects.sortEnrolled') },
        ].map(({ key, label }) => (
          <button key={key} onClick={() => { if (sortKey === key) setSortDesc(!sortDesc); else { setSortKey(key as any); setSortDesc(true); } }}
            className={`px-3 py-1.5 rounded-lg text-xs flex items-center gap-1 transition-colors ${sortKey === key ? 'bg-blue-100 text-blue-700 dark:bg-blue-500/20 dark:text-blue-300' : 'bg-muted text-muted-foreground hover:bg-accent'}`}>
            {label} {sortKey === key && (sortDesc ? '↓' : '↑')}
          </button>
        ))}
      </div>

      {/* Project grid */}
      <div className="grid grid-cols-1 xl:grid-cols-2 gap-3">
        {filtered.map(proj => {
          const status = PROJECT_STATUS_LABELS[proj.status];
          const fillPct = (proj.enrolledCount / proj.capacity) * 100;

          return (
            <div key={proj.id} className="bg-card border border-border rounded-2xl p-4">
              <div className="flex items-start gap-3">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 mb-1 flex-wrap">
                    <span className={`text-xs px-2 py-0.5 rounded-full ${colorMap[status.color]}`}>{t(status.label)}</span>
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
                      <span>{proj.recognizedMinutes / 60}h</span>
                    </div>
                    <div className="flex items-center gap-1.5">
                      <Users className="w-3 h-3" />
                      <span>{proj.enrolledCount}/{proj.capacity} {t('common.people')}</span>
                    </div>
                    <div>{proj.startAt.slice(0, 10)} → {proj.endAt.slice(0, 10)}</div>
                  </div>

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
                    <div className="absolute right-0 top-8 bg-card border border-border rounded-xl shadow-lg z-10 w-40 overflow-hidden">
                      <button onClick={() => handleAction(proj, 'edit')}
                        className="w-full flex items-center gap-2 px-4 py-2.5 text-sm text-foreground hover:bg-accent">
                        <Edit className="w-3.5 h-3.5" /> {t('admin.projects.edit')}
                      </button>
                      {proj.status === 'draft' && (
                        <button onClick={() => handleAction(proj, 'publish')}
                          className="w-full flex items-center gap-2 px-4 py-2.5 text-sm text-green-600 hover:bg-green-50 dark:hover:bg-green-900/20">
                          <CheckCircle className="w-3.5 h-3.5" /> {t('admin.projects.publish')}
                        </button>
                      )}
                      {['published', 'registrationOpen', 'inProgress'].includes(proj.status) && (
                        <button onClick={() => handleAction(proj, 'close')}
                          className="w-full flex items-center gap-2 px-4 py-2.5 text-sm text-red-600 hover:bg-red-50 dark:hover:bg-red-900/20">
                          <X className="w-3.5 h-3.5" /> {t('admin.projects.closeEarly')}
                        </button>
                      )}
                      {proj.status === 'completed' && (
                        <button onClick={() => handleAction(proj, 'archive')}
                          className="w-full flex items-center gap-2 px-4 py-2.5 text-sm text-muted-foreground hover:bg-accent">
                          <Archive className="w-3.5 h-3.5" /> {t('admin.projects.archive')}
                        </button>
                      )}
                    </div>
                  )}
                </div>
              </div>

              {/* Quick info footer */}
              <div className="flex items-center gap-2 text-xs text-muted-foreground border-t border-border pt-3">
                <span>{t('admin.projects.owner')}: {proj.ownerName}</span>
                <span>·</span>
                <span>{t('admin.projects.regCloses')}: {proj.registrationCloseAt.slice(0, 10)}</span>
              </div>
            </div>
          );
        })}
      </div>

      {/* Edit modal */}
      {editProject && editForm && (
        <EditModal
          project={editProject}
          form={editForm}
          onChange={patch => setEditForm(f => f && ({ ...f, ...patch }))}
          onClose={() => { setEditProject(null); setEditForm(null); }}
          onSave={saveEdit}
          organizations={organizations}
        />
      )}

      {/* New project modal */}
      {showForm && (
        <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center">
          <div className="absolute inset-0 bg-foreground/20 backdrop-blur-sm" onClick={() => setShowForm(false)} />
          <div className="relative bg-card border border-border rounded-2xl p-6 w-full max-w-lg mx-4 mb-4 sm:mb-0 z-50 max-h-[80vh] overflow-y-auto">
            <h3 className="text-foreground mb-4">{t('admin.projects.newProjectTitle')}</h3>
            <div className="space-y-3">
              <div>
                <label className={LABEL}>{t('admin.projects.projectName')}</label>
                <input type="text" placeholder={t('admin.projects.newProjectPlaceholder', { school: schoolName })} className={INPUT} />
              </div>
              <div>
                <label className={LABEL}>{t('admin.projects.projectSummary')}</label>
                <textarea rows={3} placeholder={t('admin.projects.summaryPlaceholder')} className={INPUT + ' resize-none'} />
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className={LABEL}>{t('admin.projects.serviceCategory')}</label>
                  <select className={INPUT}>
                    {SERVICE_CATEGORIES.map(c => <option key={c.id} value={c.id}>{resolveCategoryName(c.id, i18n.language)}</option>)}
                  </select>
                </div>
                <div>
                  <label className={LABEL}>{t('admin.projects.verifiedHours')}</label>
                  <input type="number" min="0.5" step="0.5" placeholder="2" className={INPUT} />
                </div>
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className={LABEL}>{t('admin.projects.startAt')}</label>
                  <input type="datetime-local" className={INPUT} />
                </div>
                <div>
                  <label className={LABEL}>{t('admin.projects.endAt')}</label>
                  <input type="datetime-local" className={INPUT} />
                </div>
              </div>
              <div>
                <label className={LABEL}>{t('admin.projects.location')}</label>
                <input type="text" placeholder={t('admin.projects.locationPlaceholder')} className={INPUT} />
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className={LABEL}>{t('admin.projects.capacity')}</label>
                  <input type="number" min="1" placeholder="20" className={INPUT} />
                </div>
                <div>
                  <label className={LABEL}>{t('admin.projects.partnerOrg')}</label>
                  <select className={INPUT}>
                    {organizations.map(o => <option key={o.id} value={o.id}>{o.name}</option>)}
                  </select>
                </div>
              </div>
            </div>
            <div className="flex gap-3 mt-5">
              <button onClick={() => setShowForm(false)}
                className="flex-1 py-3 rounded-xl border border-border text-foreground hover:bg-accent text-sm">{t('common.cancel')}</button>
              <button onClick={() => { toast.success(t('admin.projects.toastDraftCreated')); setShowForm(false); }}
                className="flex-1 py-3 rounded-xl border border-border text-foreground hover:bg-accent text-sm">{t('admin.projects.saveDraft')}</button>
              <button onClick={() => { toast.success(t('admin.projects.toastPublished')); setShowForm(false); }}
                className="flex-1 py-3 rounded-xl bg-[oklch(0.488_0.243_264.376)] text-white hover:opacity-90 text-sm">{t('admin.projects.publish')}</button>
            </div>
          </div>
        </div>
      )}

      {menuOpenId && <div className="fixed inset-0 z-5" onClick={() => setMenuOpenId(null)} />}
    </div>
  );
}
