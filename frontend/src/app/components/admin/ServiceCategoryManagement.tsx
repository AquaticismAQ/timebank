import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Plus, Edit, Trash2, Tags, AlertTriangle } from 'lucide-react';
import { toast } from 'sonner';
import { api } from '../../api/client';
import { ApiError, mapApiError, type ErrorUiMapping } from '../../api/http';
import { type ServiceCategory } from '../../api/types';
import { clearDictionaryCache } from '../../api/useDictionaries';
import { useFieldErrors, FieldError, handleMutationError } from '../../api/useFieldErrors';
import { usePermissions } from '../../App';
import { ListStateBanner } from '../ListStateBanner';
import { useEscapeToClose } from '../../lib/useEscapeToClose';

const INPUT = 'w-full px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-[oklch(0.488_0.243_264.376)]/40';
const LABEL = 'block text-xs text-muted-foreground mb-1.5';

interface CategoryForm {
  nameEn: string;
  nameZh: string;
  active: boolean;
}

const EMPTY_FORM: CategoryForm = { nameEn: '', nameZh: '', active: true };

/** Same rule as the backend's: either name matching, ignoring case and padding. */
function findByEitherName(items: ServiceCategory[], nameEn: string, nameZh: string, excludeId?: string) {
  const en = nameEn.trim().toLowerCase();
  const zh = nameZh.trim().toLowerCase();
  return items.find(c => c.id !== excludeId
    && (c.nameEn.trim().toLowerCase() === en || c.nameZh.trim().toLowerCase() === zh));
}

/**
 * Service Category management (issue 078) — a System Admin tab after Audit
 * Logs. Both names are required on create: the picker everywhere else shows
 * whichever one matches the caller's language, so a category missing one of
 * them would render blank for half the users.
 *
 * There is no code field: a category's code is only ever an internal handle
 * (records reference the opaque id), so the backend derives it from the
 * English name — the admin is not asked for something they cannot meaningfully
 * choose. Duplicate names are refused, counting *either* name matching, and
 * that refusal is a dialog rather than a toast because it is the answer to the
 * button the admin just pressed.
 *
 * Delete is a real delete, but the backend refuses it when any record,
 * project or proposal references the category (an FK violation translated
 * into a readable conflict) — deactivating is the designed way to retire a
 * category that already has history, which is why `active` is editable here.
 *
 * The list is small and unpaginated (`GET /service-categories` returns the
 * whole vocabulary), so this screen fetches it directly instead of through
 * `usePagedApi`, and drops the shared dictionary cache after every mutation
 * so the pickers on other screens pick the change up.
 */
export function ServiceCategoryManagement() {
  const { t } = useTranslation();
  const { hasPermission } = usePermissions();
  const canManage = hasPermission('rules.manage');
  const fieldErrors = useFieldErrors();

  const [items, setItems] = useState<ServiceCategory[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<ErrorUiMapping | null>(null);

  const refetch = useCallback(async () => {
    setLoading(true);
    try {
      setItems(await api.serviceCategories.list());
      setError(null);
    } catch (err) {
      setError(err instanceof ApiError ? mapApiError(err) : { kind: 'unknown', message: 'Request failed.' });
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void refetch(); }, [refetch]);

  const [showForm, setShowForm] = useState(false);
  const [createForm, setCreateForm] = useState<CategoryForm>(EMPTY_FORM);
  const [createSaving, setCreateSaving] = useState(false);

  const [editCategory, setEditCategory] = useState<ServiceCategory | null>(null);
  const [editForm, setEditForm] = useState<CategoryForm | null>(null);
  const [editSaving, setEditSaving] = useState(false);

  const [deleteTarget, setDeleteTarget] = useState<ServiceCategory | null>(null);
  const [deleteSaving, setDeleteSaving] = useState(false);

  /** The existing category a create/rename collided with, shown as a dialog. */
  const [duplicateOf, setDuplicateOf] = useState<ServiceCategory | null>(null);

  // Escape closes the topmost open dialog (issue 102 §5).
  useEscapeToClose(() => setShowForm(false), Boolean(showForm));
  useEscapeToClose(() => setDuplicateOf(null), Boolean(duplicateOf));
  useEscapeToClose(() => setDeleteTarget(null), Boolean(deleteTarget));
  useEscapeToClose(() => setEditCategory(null), Boolean(editCategory));

  const afterMutation = async () => { clearDictionaryCache(); await refetch(); };

  const submitCreate = async () => {
    if (!createForm.nameEn.trim() || !createForm.nameZh.trim()) {
      toast.error(t('admin.categories.errorRequired'));
      return;
    }
    // Checked here as well as server-side so the admin gets the existing
    // category named back at them immediately; the backend stays authoritative
    // (a category created by someone else meanwhile still lands as a 409 below).
    const clash = findByEitherName(items, createForm.nameEn, createForm.nameZh);
    if (clash) { setDuplicateOf(clash); return; }
    setCreateSaving(true);
    try {
      await api.serviceCategories.create({
        nameEn: createForm.nameEn.trim(), nameZh: createForm.nameZh.trim(),
      });
      fieldErrors.clearAll();
      toast.success(t('admin.categories.toastCreated', { name: createForm.nameEn }));
      setShowForm(false);
      setCreateForm(EMPTY_FORM);
      await afterMutation();
    } catch (err) {
      if (err instanceof ApiError && mapApiError(err).kind === 'conflict') {
        // Someone else created the clashing category since this screen loaded:
        // re-read (using the returned list, not the state we just set) so the
        // dialog can still name it.
        const fresh = await api.serviceCategories.list().catch(() => items);
        setItems(fresh);
        const found = findByEitherName(fresh, createForm.nameEn, createForm.nameZh);
        if (found) setDuplicateOf(found);
        else toast.error(mapApiError(err).message);
        return;
      }
      handleMutationError(err, { fieldErrors, refetch, fallbackMessage: t('common.unknownError') });
    } finally {
      setCreateSaving(false);
    }
  };

  const submitEdit = async () => {
    if (!editCategory || !editForm) return;
    if (!editForm.nameEn.trim() || !editForm.nameZh.trim()) {
      toast.error(t('admin.categories.errorRequired'));
      return;
    }
    const clash = findByEitherName(items, editForm.nameEn, editForm.nameZh, editCategory.id);
    if (clash) { setDuplicateOf(clash); return; }
    setEditSaving(true);
    try {
      await api.serviceCategories.update(editCategory.id, {
        nameEn: editForm.nameEn.trim(), nameZh: editForm.nameZh.trim(), active: editForm.active,
      });
      fieldErrors.clearAll();
      toast.success(t('admin.categories.toastUpdated', { name: editForm.nameEn }));
      setEditCategory(null);
      setEditForm(null);
      await afterMutation();
    } catch (err) {
      if (err instanceof ApiError && mapApiError(err).kind === 'conflict') {
        toast.error(mapApiError(err).message);
        return;
      }
      handleMutationError(err, { fieldErrors, refetch, fallbackMessage: t('common.unknownError') });
    } finally {
      setEditSaving(false);
    }
  };

  const submitDelete = async () => {
    if (!deleteTarget) return;
    setDeleteSaving(true);
    try {
      await api.serviceCategories.remove(deleteTarget.id);
      toast.success(t('admin.categories.toastDeleted', { name: deleteTarget.name }));
      setDeleteTarget(null);
      await afterMutation();
    } catch (err) {
      // A conflict on delete means exactly one thing — some record, project or
      // proposal still references the category. The backend's own explanation
      // says so, but only in English, so translate it here rather than showing
      // an English sentence inside a Chinese screen.
      if (err instanceof ApiError && mapApiError(err).kind === 'conflict') {
        toast.error(t('admin.categories.deleteInUse'));
      } else {
        handleMutationError(err, { refetch, fallbackMessage: t('common.unknownError') });
      }
    } finally {
      setDeleteSaving(false);
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-3">
        <p className="text-xs text-muted-foreground">{t('admin.categories.count', { count: items.length })}</p>
        {canManage && (
          <button onClick={() => { fieldErrors.clearAll(); setCreateForm(EMPTY_FORM); setShowForm(true); }}
            className="flex items-center gap-1.5 px-4 py-2 bg-[oklch(0.488_0.243_264.376)] text-white rounded-xl text-sm hover:opacity-90">
            <Plus className="w-4 h-4" /> {t('admin.categories.newCategory')}
          </button>
        )}
      </div>

      <p className="text-xs text-muted-foreground">{t('admin.categories.hint')}</p>

      <ListStateBanner error={error} empty={!loading && items.length === 0} emptyLabel={t('admin.categories.empty')} />

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-3">
        {items.map(cat => (
          <div key={cat.id} className="bg-card border border-border rounded-2xl p-4 flex items-start gap-3">
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-2 mb-1 flex-wrap">
                <span className="text-xs px-2 py-0.5 rounded-full bg-muted text-muted-foreground font-mono">{cat.code}</span>
                {!cat.active && (
                  <span className="text-xs px-2 py-0.5 rounded-full bg-red-100 dark:bg-red-900/30 text-red-700 dark:text-red-400">
                    {t('admin.categories.inactive')}
                  </span>
                )}
              </div>
              <div className="text-sm text-foreground font-medium break-words">{cat.nameEn}</div>
              <div className="text-sm text-muted-foreground break-words">{cat.nameZh}</div>
            </div>
            {canManage && (
              <div className="flex flex-col gap-1 shrink-0">
                <button onClick={() => { fieldErrors.clearAll(); setEditCategory(cat); setEditForm({ nameEn: cat.nameEn, nameZh: cat.nameZh, active: cat.active }); }}
                  className="p-2 rounded-lg hover:bg-accent text-muted-foreground hover:text-foreground" title={t('admin.categories.edit')}>
                  <Edit className="w-3.5 h-3.5" />
                </button>
                <button onClick={() => setDeleteTarget(cat)}
                  className="p-2 rounded-lg hover:bg-red-50 dark:hover:bg-red-900/20 text-muted-foreground hover:text-red-600" title={t('admin.categories.delete')}>
                  <Trash2 className="w-3.5 h-3.5" />
                </button>
              </div>
            )}
          </div>
        ))}
      </div>

      {/* Create modal */}
      {showForm && (
        <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4">
          <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" />
          <div className="relative bg-card w-full sm:max-w-lg sm:rounded-2xl rounded-t-2xl shadow-xl flex flex-col max-h-[90vh]">
            <div className="flex items-center gap-3 px-5 py-4 border-b border-border shrink-0">
              <Tags className="w-5 h-5 text-[oklch(0.488_0.243_264.376)] shrink-0" />
              <h3 className="text-foreground leading-tight flex-1">{t('admin.categories.newCategory')}</h3>
            </div>
            <div className="overflow-y-auto px-5 py-5 space-y-3 text-sm overscroll-contain">
              <CategoryNameFields form={createForm} onChange={patch => setCreateForm(f => ({ ...f, ...patch }))}
                getFieldError={fieldErrors.getError} onFieldEdit={fieldErrors.clearField} />
            </div>
            <div className="px-5 py-4 border-t border-border shrink-0 flex gap-3">
              <button onClick={() => setShowForm(false)} disabled={createSaving} className="flex-1 py-2.5 rounded-xl border border-border text-sm text-foreground hover:bg-accent disabled:opacity-50">{t('common.cancel')}</button>
              <button onClick={submitCreate} disabled={createSaving} className="flex-1 py-2.5 rounded-xl bg-[oklch(0.488_0.243_264.376)] text-white text-sm hover:opacity-90 disabled:opacity-50">
                {createSaving ? t('common.saving', { defaultValue: 'Saving...' }) : t('admin.categories.create')}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Edit modal — the code is immutable (it is what every stored record references). */}
      {editCategory && editForm && (
        <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4">
          <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" />
          <div className="relative bg-card w-full sm:max-w-lg sm:rounded-2xl rounded-t-2xl shadow-xl flex flex-col max-h-[90vh]">
            <div className="flex items-center gap-3 px-5 py-4 border-b border-border shrink-0">
              <Edit className="w-5 h-5 text-[oklch(0.488_0.243_264.376)] shrink-0" />
              <div className="flex-1 min-w-0">
                <h3 className="text-foreground leading-tight">{t('admin.categories.edit')}</h3>
                <p className="text-xs text-muted-foreground truncate font-mono">{editCategory.code}</p>
              </div>
            </div>
            <div className="overflow-y-auto px-5 py-5 space-y-3 text-sm overscroll-contain">
              <CategoryNameFields form={editForm} onChange={patch => setEditForm(f => f && ({ ...f, ...patch }))}
                getFieldError={fieldErrors.getError} onFieldEdit={fieldErrors.clearField} />
              <label className="flex items-center gap-2 text-sm text-foreground">
                <input type="checkbox" checked={editForm.active} onChange={e => setEditForm(f => f && ({ ...f, active: e.target.checked }))} className="rounded border-border" />
                {t('admin.categories.activeLabel')}
              </label>
              <p className="text-xs text-muted-foreground">{t('admin.categories.activeHint')}</p>
            </div>
            <div className="px-5 py-4 border-t border-border shrink-0 flex gap-3">
              <button onClick={() => setEditCategory(null)} disabled={editSaving} className="flex-1 py-2.5 rounded-xl border border-border text-sm text-foreground hover:bg-accent disabled:opacity-50">{t('common.cancel')}</button>
              <button onClick={submitEdit} disabled={editSaving} className="flex-1 py-2.5 rounded-xl bg-[oklch(0.488_0.243_264.376)] text-white text-sm hover:opacity-90 disabled:opacity-50">
                {editSaving ? t('common.saving', { defaultValue: 'Saving...' }) : t('admin.projects.saveChanges')}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Duplicate-name refusal — the answer to the Create/Save press. */}
      {duplicateOf && (
        <div className="fixed inset-0 z-[60] flex items-end sm:items-center justify-center p-0 sm:p-4">
          <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" />
          <div className="relative bg-card w-full sm:max-w-sm sm:rounded-2xl rounded-t-2xl shadow-xl p-5 space-y-4">
            <div className="flex items-center gap-3">
              <AlertTriangle className="w-5 h-5 text-amber-600 shrink-0" />
              <h2 className="text-foreground font-medium">{t('admin.categories.duplicateTitle')}</h2>
            </div>
            <p className="text-sm text-muted-foreground">{t('admin.categories.duplicateBody')}</p>
            <div className="rounded-xl border border-border bg-muted/50 px-3 py-2 text-sm">
              <div className="text-foreground break-words">{duplicateOf.nameEn}</div>
              <div className="text-muted-foreground break-words">{duplicateOf.nameZh}</div>
              {!duplicateOf.active && <div className="text-xs text-red-600 mt-1">{t('admin.categories.duplicateInactive')}</div>}
            </div>
            <button onClick={() => setDuplicateOf(null)}
              className="w-full py-2.5 rounded-xl bg-[oklch(0.488_0.243_264.376)] text-white text-sm hover:opacity-90">
              {t('common.ok', { defaultValue: 'OK' })}
            </button>
          </div>
        </div>
      )}

      {/* Delete confirmation */}
      {deleteTarget && (
        <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4">
          <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" />
          <div className="relative bg-card w-full sm:max-w-sm sm:rounded-2xl rounded-t-2xl shadow-xl p-5 space-y-4">
            <div className="flex items-center gap-3">
              <Trash2 className="w-5 h-5 text-red-600 shrink-0" />
              <div className="flex-1 min-w-0">
                <h2 className="text-foreground font-medium">{t('admin.categories.delete')}</h2>
                <p className="text-xs text-muted-foreground truncate">{deleteTarget.name}</p>
              </div>
            </div>
            <p className="text-sm text-muted-foreground">{t('admin.categories.deleteWarning')}</p>
            <div className="flex gap-2">
              <button onClick={() => setDeleteTarget(null)} disabled={deleteSaving} className="flex-1 py-2.5 rounded-xl border border-border text-foreground hover:bg-accent text-sm disabled:opacity-50">{t('common.cancel')}</button>
              <button onClick={submitDelete} disabled={deleteSaving} className="flex-1 py-2.5 rounded-xl bg-red-600 text-white hover:opacity-90 text-sm disabled:opacity-50">
                {deleteSaving ? t('common.saving', { defaultValue: 'Saving...' }) : t('admin.categories.delete')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function CategoryNameFields({ form, onChange, getFieldError, onFieldEdit }: {
  form: CategoryForm;
  onChange: (patch: Partial<CategoryForm>) => void;
  getFieldError: (...fields: string[]) => string | undefined;
  onFieldEdit: (field: string) => void;
}) {
  const { t } = useTranslation();
  return (
    <>
      <div>
        <label className={LABEL}>{t('admin.categories.nameEn')}</label>
        <input type="text" value={form.nameEn} maxLength={100}
          onChange={e => { onFieldEdit('nameEn'); onChange({ nameEn: e.target.value }); }} className={INPUT} />
        <FieldError message={getFieldError('nameEn')} />
      </div>
      <div>
        <label className={LABEL}>{t('admin.categories.nameZh')}</label>
        <input type="text" value={form.nameZh} maxLength={100}
          onChange={e => { onFieldEdit('nameZh'); onChange({ nameZh: e.target.value }); }} className={INPUT} />
        <FieldError message={getFieldError('nameZh')} />
      </div>
    </>
  );
}
