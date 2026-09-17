import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Plus, Edit, Archive, Building2 } from 'lucide-react';
import { toast } from 'sonner';
import { api } from '../../api/client';
import { type Organization } from '../../api/types';
import { usePagedApi } from '../../api/useApi';
import { useFieldErrors, FieldError, handleMutationError } from '../../api/useFieldErrors';
import { usePermissions } from '../../App';
import { PageControls } from '../PageControls';
import { ListStateBanner } from '../ListStateBanner';
import { ListSearchInput } from '../ListSearchInput';
import { useEscapeToClose } from '../../lib/useEscapeToClose';

const INPUT = 'w-full px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-[oklch(0.488_0.243_264.376)]/40';
const LABEL = 'block text-xs text-muted-foreground mb-1.5';

const TYPE_OPTIONS: Organization['type'][] = ['school', 'community', 'nonprofit', 'government', 'other'];

interface OrgForm {
  name: string;
  type: Organization['type'];
  description: string;
  contactName: string;
  contactEmail: string;
  contactPhone: string;
  address: string;
}

const EMPTY_FORM: OrgForm = { name: '', type: 'community', description: '', contactName: '', contactEmail: '', contactPhone: '', address: '' };

function toForm(o: Organization): OrgForm {
  return {
    name: o.name, type: o.type, description: o.description,
    contactName: o.contactName, contactEmail: o.contactEmail, contactPhone: o.contactPhone, address: o.address,
  };
}

/**
 * Partner Organization management (02-PorjectOrganization.md §4) — a new
 * tab in System Admin, before Settings. `organizations.read` (list/get) is
 * already granted to every role; `organizations.manage` (create/update/
 * archive) is admin + teacher only, per that doc's decision. There is no
 * hard-delete endpoint by design — "archive" is the only removal action,
 * matching what the backend actually supports (an org id stays valid for
 * any project that already references it).
 */
export function OrganizationManagement() {
  const { t } = useTranslation();
  const { hasPermission } = usePermissions();
  const canManage = hasPermission('organizations.manage');
  const fieldErrors = useFieldErrors();

  const { items, page, loading, error, setSearch: setPagedSearch, setFilter, setPage, refetch } =
    usePagedApi<Organization>(api.organizations.list, {});

  const [search, setSearch] = useState('');
  const [filterStatus, setFilterStatus] = useState<Organization['status'] | 'all'>('all');
  const [filterType, setFilterType] = useState<Organization['type'] | 'all'>('all');

  const [showForm, setShowForm] = useState(false);
  const [createForm, setCreateForm] = useState<OrgForm>(EMPTY_FORM);
  const [createSaving, setCreateSaving] = useState(false);

  const [editOrg, setEditOrg] = useState<Organization | null>(null);
  const [editForm, setEditForm] = useState<OrgForm | null>(null);
  const [editSaving, setEditSaving] = useState(false);

  const [archiveTarget, setArchiveTarget] = useState<Organization | null>(null);
  const [archiveSaving, setArchiveSaving] = useState(false);

  // Escape closes the topmost open dialog (issue 102 §5).
  useEscapeToClose(() => setShowForm(false), Boolean(showForm));
  useEscapeToClose(() => setArchiveTarget(null), Boolean(archiveTarget));
  useEscapeToClose(() => setEditOrg(null), Boolean(editOrg));

  const handleSearchChange = (value: string) => { setSearch(value); setPagedSearch(value); };
  const handleStatusChange = (value: Organization['status'] | 'all') => { setFilterStatus(value); setFilter('status', value === 'all' ? undefined : value); };
  const handleTypeChange = (value: Organization['type'] | 'all') => { setFilterType(value); setFilter('type', value === 'all' ? undefined : value); };

  const openEdit = (org: Organization) => { fieldErrors.clearAll(); setEditOrg(org); setEditForm(toForm(org)); };

  const submitCreate = async () => {
    if (!createForm.name.trim()) { toast.error(t('admin.organizations.errorNameEmpty')); return; }
    setCreateSaving(true);
    try {
      await api.organizations.create(createForm);
      fieldErrors.clearAll();
      toast.success(t('admin.organizations.toastCreated', { name: createForm.name }));
      setShowForm(false);
      setCreateForm(EMPTY_FORM);
      refetch();
    } catch (err) {
      handleMutationError(err, { fieldErrors, refetch, fallbackMessage: t('common.unknownError') });
    } finally {
      setCreateSaving(false);
    }
  };

  const submitEdit = async () => {
    if (!editOrg || !editForm) return;
    if (!editForm.name.trim()) { toast.error(t('admin.organizations.errorNameEmpty')); return; }
    setEditSaving(true);
    try {
      await api.organizations.update(editOrg.id, { ...editForm, version: editOrg.version });
      fieldErrors.clearAll();
      toast.success(t('admin.organizations.toastUpdated', { name: editForm.name }));
      setEditOrg(null);
      setEditForm(null);
      refetch();
    } catch (err) {
      handleMutationError(err, { fieldErrors, refetch, fallbackMessage: t('common.unknownError') });
    } finally {
      setEditSaving(false);
    }
  };

  const submitArchive = async () => {
    if (!archiveTarget) return;
    setArchiveSaving(true);
    try {
      await api.organizations.archive(archiveTarget.id);
      toast.success(t('admin.organizations.toastArchived', { name: archiveTarget.name }));
      setArchiveTarget(null);
      refetch();
    } catch (err) {
      handleMutationError(err, { refetch, fallbackMessage: t('common.unknownError') });
    } finally {
      setArchiveSaving(false);
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <p className="text-xs text-muted-foreground">{t('admin.organizations.count', { count: items.length })}</p>
        {canManage && (
          <button onClick={() => { fieldErrors.clearAll(); setCreateForm(EMPTY_FORM); setShowForm(true); }}
            className="flex items-center gap-1.5 px-4 py-2 bg-[oklch(0.488_0.243_264.376)] text-white rounded-xl text-sm hover:opacity-90">
            <Plus className="w-4 h-4" /> {t('admin.organizations.newOrg')}
          </button>
        )}
      </div>

      <div className="flex gap-2 flex-wrap">
        <div className="relative flex-1 min-w-48">
          <ListSearchInput
            value={search}
            onCommit={handleSearchChange}
            placeholder={t('admin.organizations.searchPlaceholder')}
          />
        </div>
        <select value={filterType} onChange={e => handleTypeChange(e.target.value as Organization['type'] | 'all')}
          className="px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none">
          <option value="all">{t('admin.organizations.allTypes')}</option>
          {TYPE_OPTIONS.map(ty => <option key={ty} value={ty}>{t(`admin.organizations.type.${ty}`)}</option>)}
        </select>
        <select value={filterStatus} onChange={e => handleStatusChange(e.target.value as Organization['status'] | 'all')}
          className="px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none">
          <option value="all">{t('admin.organizations.allStatuses')}</option>
          <option value="active">{t('admin.organizations.statusActive')}</option>
          <option value="inactive">{t('admin.organizations.statusInactive')}</option>
          <option value="archived">{t('admin.organizations.statusArchived')}</option>
        </select>
      </div>

      <ListStateBanner error={error} empty={!loading && items.length === 0} emptyLabel={t('admin.organizations.empty')} />

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-3">
        {items.map(org => (
          <div key={org.id} className="bg-card border border-border rounded-2xl p-4">
            <div className="flex items-start gap-3">
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2 mb-1 flex-wrap">
                  <span className="text-xs px-2 py-0.5 rounded-full whitespace-nowrap bg-muted text-muted-foreground">{t(`admin.organizations.type.${org.type}`)}</span>
                  {org.status !== 'active' && (
                    <span className="text-xs px-2 py-0.5 rounded-full whitespace-nowrap bg-red-100 dark:bg-red-900/30 text-red-700 dark:text-red-400">
                      {t(`admin.organizations.status${org.status.charAt(0).toUpperCase()}${org.status.slice(1)}`)}
                    </span>
                  )}
                </div>
                <div className="text-sm text-foreground font-medium mb-1">{org.name}</div>
                {org.description && <div className="text-xs text-muted-foreground mb-2 line-clamp-2">{org.description}</div>}
                <div className="text-xs text-muted-foreground space-y-0.5">
                  {org.contactName && <div>{t('admin.organizations.contact')}: {org.contactName}{org.contactPhone ? ` · ${org.contactPhone}` : ''}</div>}
                  {org.contactEmail && <div>{org.contactEmail}</div>}
                  <div>{t('admin.organizations.projectCount', { count: org.projectCount })}</div>
                </div>
              </div>
              {canManage && org.status === 'active' && (
                <div className="flex flex-col gap-1 shrink-0">
                  <button onClick={() => openEdit(org)} className="p-2 rounded-lg hover:bg-accent text-muted-foreground hover:text-foreground" title={t('admin.organizations.edit')}>
                    <Edit className="w-3.5 h-3.5" />
                  </button>
                  <button onClick={() => setArchiveTarget(org)} className="p-2 rounded-lg hover:bg-red-50 dark:hover:bg-red-900/20 text-muted-foreground hover:text-red-600" title={t('admin.organizations.archive')}>
                    <Archive className="w-3.5 h-3.5" />
                  </button>
                </div>
              )}
            </div>
          </div>
        ))}
      </div>
      <PageControls page={page} onPageChange={setPage} />

      {/* Create modal */}
      {showForm && (
        <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4">
          <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" />
          <div className="relative bg-card w-full sm:max-w-lg sm:rounded-2xl rounded-t-2xl shadow-xl flex flex-col max-h-[90vh]">
            <div className="flex items-center gap-3 px-5 py-4 border-b border-border shrink-0">
              <Building2 className="w-5 h-5 text-[oklch(0.488_0.243_264.376)] shrink-0" />
              <h3 className="text-foreground leading-tight flex-1">{t('admin.organizations.newOrg')}</h3>
            </div>
            <div className="overflow-y-auto px-5 py-5 space-y-3 text-sm">
              <OrgFormFields form={createForm} onChange={patch => setCreateForm(f => ({ ...f, ...patch }))} getFieldError={fieldErrors.getError} onFieldEdit={fieldErrors.clearField} />
            </div>
            <div className="px-5 py-4 border-t border-border shrink-0 flex gap-3">
              <button onClick={() => setShowForm(false)} disabled={createSaving} className="flex-1 py-2.5 rounded-xl border border-border text-sm text-foreground hover:bg-accent disabled:opacity-50">{t('common.cancel')}</button>
              <button onClick={submitCreate} disabled={createSaving} className="flex-1 py-2.5 rounded-xl bg-[oklch(0.488_0.243_264.376)] text-white text-sm hover:opacity-90 disabled:opacity-50">
                {createSaving ? t('common.saving', { defaultValue: 'Saving...' }) : t('admin.organizations.create')}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Edit modal */}
      {editOrg && editForm && (
        <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4">
          <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" />
          <div className="relative bg-card w-full sm:max-w-lg sm:rounded-2xl rounded-t-2xl shadow-xl flex flex-col max-h-[90vh]">
            <div className="flex items-center gap-3 px-5 py-4 border-b border-border shrink-0">
              <Edit className="w-5 h-5 text-[oklch(0.488_0.243_264.376)] shrink-0" />
              <div className="flex-1 min-w-0">
                <h3 className="text-foreground leading-tight">{t('admin.organizations.edit')}</h3>
                <p className="text-xs text-muted-foreground truncate">{editOrg.name}</p>
              </div>
            </div>
            <div className="overflow-y-auto px-5 py-5 space-y-3 text-sm">
              <OrgFormFields form={editForm} onChange={patch => setEditForm(f => f && ({ ...f, ...patch }))} getFieldError={fieldErrors.getError} onFieldEdit={fieldErrors.clearField} />
            </div>
            <div className="px-5 py-4 border-t border-border shrink-0 flex gap-3">
              <button onClick={() => setEditOrg(null)} disabled={editSaving} className="flex-1 py-2.5 rounded-xl border border-border text-sm text-foreground hover:bg-accent disabled:opacity-50">{t('common.cancel')}</button>
              <button onClick={submitEdit} disabled={editSaving} className="flex-1 py-2.5 rounded-xl bg-[oklch(0.488_0.243_264.376)] text-white text-sm hover:opacity-90 disabled:opacity-50">
                {editSaving ? t('common.saving', { defaultValue: 'Saving...' }) : t('admin.projects.saveChanges')}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Archive confirmation */}
      {archiveTarget && (
        <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4">
          <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" />
          <div className="relative bg-card w-full sm:max-w-sm sm:rounded-2xl rounded-t-2xl shadow-xl p-5 space-y-4">
            <div className="flex items-center gap-3">
              <Archive className="w-5 h-5 text-red-600 shrink-0" />
              <div className="flex-1 min-w-0">
                <h2 className="text-foreground font-medium">{t('admin.organizations.archive')}</h2>
                <p className="text-xs text-muted-foreground truncate">{archiveTarget.name}</p>
              </div>
            </div>
            <p className="text-sm text-muted-foreground">{t('admin.organizations.archiveWarning')}</p>
            <div className="flex gap-2">
              <button onClick={() => setArchiveTarget(null)} disabled={archiveSaving} className="flex-1 py-2.5 rounded-xl border border-border text-foreground hover:bg-accent text-sm disabled:opacity-50">{t('common.cancel')}</button>
              <button onClick={submitArchive} disabled={archiveSaving} className="flex-1 py-2.5 rounded-xl bg-red-600 text-white hover:opacity-90 text-sm disabled:opacity-50">
                {archiveSaving ? t('common.saving', { defaultValue: 'Saving...' }) : t('admin.organizations.archive')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function OrgFormFields({ form, onChange, getFieldError, onFieldEdit }: {
  form: OrgForm;
  onChange: (patch: Partial<OrgForm>) => void;
  getFieldError: (...fields: string[]) => string | undefined;
  onFieldEdit: (field: string) => void;
}) {
  const { t } = useTranslation();
  const edit = (field: string, patch: Partial<OrgForm>) => { onFieldEdit(field); onChange(patch); };
  return (
    <>
      <div>
        <label className={LABEL}>{t('admin.organizations.name')}</label>
        <input type="text" value={form.name} onChange={e => edit('name', { name: e.target.value })} maxLength={255} className={INPUT} />
        <FieldError message={getFieldError('name')} />
      </div>
      <div>
        <label className={LABEL}>{t('admin.organizations.type.label')}</label>
        <select value={form.type} onChange={e => edit('type', { type: e.target.value as Organization['type'] })} className={INPUT}>
          {TYPE_OPTIONS.map(ty => <option key={ty} value={ty}>{t(`admin.organizations.type.${ty}`)}</option>)}
        </select>
        <FieldError message={getFieldError('type')} />
      </div>
      <div>
        <label className={LABEL}>{t('admin.organizations.description')}</label>
        <textarea rows={3} value={form.description} onChange={e => edit('description', { description: e.target.value })} className={INPUT + ' resize-none'} />
        <FieldError message={getFieldError('description')} />
      </div>
      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className={LABEL}>{t('admin.organizations.contactName')}</label>
          <input type="text" value={form.contactName} onChange={e => edit('contactName', { contactName: e.target.value })} maxLength={255} className={INPUT} />
          <FieldError message={getFieldError('contactName')} />
        </div>
        <div>
          <label className={LABEL}>{t('admin.organizations.contactPhone')}</label>
          <input type="text" value={form.contactPhone} onChange={e => edit('contactPhone', { contactPhone: e.target.value })} maxLength={255} className={INPUT} />
          <FieldError message={getFieldError('contactPhone')} />
        </div>
      </div>
      <div>
        <label className={LABEL}>{t('admin.organizations.contactEmail')}</label>
        <input type="email" value={form.contactEmail} onChange={e => edit('contactEmail', { contactEmail: e.target.value })} maxLength={255} className={INPUT} />
        <FieldError message={getFieldError('contactEmail')} />
      </div>
      <div>
        <label className={LABEL}>{t('admin.organizations.address')}</label>
        <input type="text" value={form.address} onChange={e => edit('address', { address: e.target.value })} maxLength={255} className={INPUT} />
        <FieldError message={getFieldError('address')} />
      </div>
    </>
  );
}
