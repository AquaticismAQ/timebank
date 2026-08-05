import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { X, Check, XCircle, CheckSquare } from 'lucide-react';
import { toast } from 'sonner';
import { api } from '../../api/client';
import { ApiError, mapApiError } from '../../api/http';
import { usePermissions } from '../../App';
import { usePagedApi } from '../../api/useApi';
import { PageControls } from '../PageControls';
import { ListStateBanner } from '../ListStateBanner';
import { type ApplicationStatus } from '../../api/types';

const STATUS_LABEL_KEY: Record<ApplicationStatus, string> = {
  pending: 'admin.projects.regStatusPending',
  confirmed: 'admin.projects.regStatusConfirmed',
  waitlisted: 'admin.projects.regStatusWaitlisted',
  rejected: 'admin.projects.regStatusRejected',
  completed: 'admin.projects.regStatusCompleted',
  cancelled: 'admin.projects.regStatusCancelled',
};

const STATUS_COLOR: Record<ApplicationStatus, string> = {
  pending: 'bg-yellow-100 dark:bg-yellow-900/30 text-yellow-700 dark:text-yellow-400',
  confirmed: 'bg-green-100 dark:bg-green-900/30 text-green-700 dark:text-green-400',
  waitlisted: 'bg-blue-100 dark:bg-blue-900/30 text-blue-700 dark:text-blue-400',
  rejected: 'bg-red-100 dark:bg-red-900/30 text-red-700 dark:text-red-400',
  completed: 'bg-muted text-muted-foreground',
  cancelled: 'bg-muted text-muted-foreground',
};

/**
 * `applications.list`/`.confirm`/`.reject`/`.bulkActions` were fully wired
 * in `client.ts` (I2-d6) but had zero UI consumers — the only place
 * `api.applications` was ever called from was the student's own
 * register/cancel flow, so staff had no way to see or act on who
 * registered for a project at all. Found and closed during I2 hardening,
 * `00-Status.md` §25.
 */
export function ProjectRegistrants({ projectId, projectTitle, onClose }: { projectId: string; projectTitle: string; onClose: () => void }) {
  const { t } = useTranslation();
  const { hasPermission } = usePermissions();
  const canManage = hasPermission('applications.manage');
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState(false);

  const { items, page, loading, error, setPage, refetch } = usePagedApi(
    (params) => api.applications.list({ ...params, filter: { ...params?.filter, projectId } }),
    {},
  );

  const toggleSelect = (id: string) => {
    setSelected(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  const handleAction = async (id: string, action: 'confirm' | 'reject') => {
    try {
      await (action === 'confirm' ? api.applications.confirm(id) : api.applications.reject(id));
      toast.success(action === 'confirm' ? t('admin.projects.regConfirmed') : t('admin.projects.regRejected'));
      refetch();
    } catch (err) {
      if (err instanceof ApiError) toast.error(mapApiError(err).message);
      else toast.error(t('common.unknownError'));
    }
  };

  const handleBulk = async (action: 'confirm' | 'reject') => {
    if (selected.size === 0) return;
    setBusy(true);
    try {
      const results = await api.applications.bulkActions([...selected], action, action.toUpperCase());
      const failures = results.filter(r => !r.success);
      if (failures.length === 0) {
        toast.success(t('admin.projects.regBulkSuccess', { count: results.length }));
      } else {
        toast.warning(t('admin.projects.regBulkPartial', { succeeded: results.length - failures.length, failed: failures.length }));
      }
      setSelected(new Set());
      refetch();
    } catch (err) {
      if (err instanceof ApiError) toast.error(mapApiError(err).message);
      else toast.error(t('common.unknownError'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center" onClick={onClose}>
      <div className="absolute inset-0 bg-foreground/20 backdrop-blur-sm" />
      <div className="relative bg-card border border-border rounded-2xl p-6 w-full max-w-2xl mx-4 mb-4 sm:mb-0 z-50 max-h-[85vh] overflow-y-auto" onClick={e => e.stopPropagation()}>
        <div className="flex items-center justify-between mb-4">
          <div>
            <h3 className="text-foreground">{t('admin.projects.registrants')}</h3>
            <p className="text-xs text-muted-foreground">{projectTitle}</p>
          </div>
          <button onClick={onClose} className="text-muted-foreground hover:text-foreground"><X className="w-4 h-4" /></button>
        </div>

        {canManage && selected.size > 0 && (
          <div className="flex items-center gap-2 mb-3 px-3 py-2 rounded-xl bg-muted text-xs">
            <span className="text-foreground">{t('admin.projects.regSelectedCount', { count: selected.size })}</span>
            <button disabled={busy} onClick={() => handleBulk('confirm')}
              className="ml-auto px-2.5 py-1 rounded-lg bg-green-600 text-white hover:opacity-90 disabled:opacity-50 flex items-center gap-1">
              <CheckSquare className="w-3 h-3" /> {t('admin.projects.regConfirm')}
            </button>
            <button disabled={busy} onClick={() => handleBulk('reject')}
              className="px-2.5 py-1 rounded-lg bg-destructive text-destructive-foreground hover:opacity-90 disabled:opacity-50 flex items-center gap-1">
              <XCircle className="w-3 h-3" /> {t('admin.projects.regReject')}
            </button>
          </div>
        )}

        <ListStateBanner error={error} empty={!loading && items.length === 0} emptyLabel={t('admin.projects.regNoRegistrants')} />

        <div className="space-y-2">
          {items.map(app => (
            <div key={app.id} className="flex items-center gap-3 px-3 py-2.5 rounded-xl border border-border">
              {canManage && (app.status === 'pending' || app.status === 'waitlisted') && (
                <input type="checkbox" checked={selected.has(app.id)} onChange={() => toggleSelect(app.id)} className="shrink-0" />
              )}
              <div className="min-w-0 flex-1">
                <div className="text-sm text-foreground truncate">{app.studentName}</div>
                <div className="text-xs text-muted-foreground">{app.appliedAt?.slice(0, 10)}</div>
              </div>
              <span className={`text-xs px-2 py-0.5 rounded-full shrink-0 ${STATUS_COLOR[app.status]}`}>{t(STATUS_LABEL_KEY[app.status])}</span>
              {canManage && (app.status === 'pending' || app.status === 'waitlisted') && (
                <div className="flex gap-1 shrink-0">
                  <button onClick={() => handleAction(app.id, 'confirm')} title={t('admin.projects.regConfirm')}
                    className="p-1.5 rounded-lg text-green-600 hover:bg-green-50 dark:hover:bg-green-900/20"><Check className="w-3.5 h-3.5" /></button>
                  <button onClick={() => handleAction(app.id, 'reject')} title={t('admin.projects.regReject')}
                    className="p-1.5 rounded-lg text-red-600 hover:bg-red-50 dark:hover:bg-red-900/20"><XCircle className="w-3.5 h-3.5" /></button>
                </div>
              )}
            </div>
          ))}
        </div>

        <PageControls page={page} onPageChange={setPage} />
      </div>
    </div>
  );
}
