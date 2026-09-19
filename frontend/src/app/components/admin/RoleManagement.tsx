import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Plus, Search, Lock, Pencil, Eye, Trash2, KeyRound, AlertTriangle, LayoutTemplate } from 'lucide-react';
import { toast } from 'sonner';
import { api } from '../../api/client';
import { ApiError, mapApiError } from '../../api/http';
import type { Permission, RoleSummary, RoleTemplateSummary } from '../../api/types';
import { Accordion, AccordionItem, AccordionTrigger, AccordionContent } from '../ui/accordion';
import { Checkbox } from '../ui/checkbox';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../ui/select';
import { ConfirmDeleteDialog } from '../shared/ConfirmDeleteDialog';
import { useEscapeToClose } from '../../lib/useEscapeToClose';

/**
 * Row-action button shape shared with the Users/Rule Settings tabs
 * (`SystemAdmin.tsx`'s `ACTION_BTN_BLUE`) — icon + text, bordered, colored
 * per action but never filled.
 */
const ACTION_BTN_BLUE = 'inline-flex items-center gap-1 text-xs px-2 py-1 rounded-lg border border-blue-200 dark:border-blue-800 text-blue-600 dark:text-blue-400 hover:bg-blue-50 dark:hover:bg-blue-900/20 transition-colors';
const ACTION_BTN_RED = 'inline-flex items-center gap-1 text-xs px-2 py-1 rounded-lg border border-red-200 dark:border-red-800 text-red-600 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-900/20 transition-colors';
const ACTION_BTN_RED_DISABLED = 'inline-flex items-center gap-1 text-xs px-2 py-1 rounded-lg border border-border text-muted-foreground/50 cursor-not-allowed';

// 07-RoleFinetune.md §2.3: reserved for the student role — never offered in
// the picker, not just disabled. `projects.close` is dead (retired by
// 02-PorjectOrganization.md §2), kept as an unused DB row, also hidden here.
const NEVER_ASSIGNABLE = new Set([
  'eventProposals.create', 'applications.create', 'serviceRecords.create', 'projects.close',
  // Retired by 12-DataExport.md §9.4 in favour of one permission per export
  // type, so that granting "may export" stops meaning "may export everything".
  // Same treatment as `projects.close`: the row stays for the audit trail of
  // who once held it, and granting it now would confer nothing.
  'exports.create',
]);

// 07-RoleFinetune.md §6: not an enforced ceiling, just a soft nudge next to
// the row for whoever's reviewing a role's grants.
const RISK_FLAGGED = new Set([
  'accounts.adjust', 'accounts.reverse', 'accounts.freeze', 'rules.publish',
  // Of the seven export permissions this is the one worth a second look:
  // every other export describes students, which is the job, while this one
  // describes colleagues. 12-DataExport.md §6.6 confines it to the SDC, and
  // the flag is here so granting it to a custom role is a deliberate act.
  'exports.reviewEfficiency',
  'imports.commit', 'users.manage', 'roles.manage', 'settings.manage', 'lifecycle.purge',
]);

function groupOf(code: string): string {
  return code.split('.')[0];
}

/**
 * Unlike a custom role's own free-text `name`, permission codes and their
 * resource-group prefixes come from a fixed, backend-owned catalog
 * (`R__seed_permissions_and_roles.sql`) — a closed, enumerable set, so it
 * *can* be translated via `admin.roles.permissionGroups`/
 * `permissionDescriptions`. `groupLabel` (camelCase prefix → spaced words)
 * is kept only as the `defaultValue` fallback for a permission added to the
 * backend before its translation ships.
 */
function groupLabel(prefix: string): string {
  const spaced = prefix.replace(/([A-Z])/g, ' $1');
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

/**
 * The grouped/searchable checkbox picker shared by role and role-template
 * forms (07-RoleFinetune.md §2.7) — a controlled component (`selected`/
 * `onChange`) so either caller's own form state stays the single source of
 * truth; this owns only its own search/accordion-open UI state.
 * `searchRowExtra` renders inline with the search box (used for the
 * create-role template-select dropdown, §2.7) without coupling this
 * component to the template feature itself.
 */
function PermissionPicker({ permissions, selected, onChange, disabled, searchRowExtra }: {
  permissions: Permission[];
  selected: Set<string>;
  onChange: (next: Set<string>) => void;
  disabled?: boolean;
  searchRowExtra?: React.ReactNode;
}) {
  const { t } = useTranslation();
  const [search, setSearch] = useState('');
  const [openGroups, setOpenGroups] = useState<string[]>(() => [...new Set([...selected].map(groupOf))]);

  const translateGroup = (group: string) =>
    t(`admin.roles.permissionGroups.${group}`, { defaultValue: groupLabel(group) });
  const translateDescription = (p: Permission) =>
    t(`admin.roles.permissionDescriptions.${p.code}`, { defaultValue: p.description });

  const assignable = useMemo(() => permissions.filter(p => !NEVER_ASSIGNABLE.has(p.code)), [permissions]);

  const grouped = useMemo(() => {
    const map = new Map<string, Permission[]>();
    for (const p of assignable) {
      const g = groupOf(p.code);
      if (!map.has(g)) map.set(g, []);
      map.get(g)!.push(p);
    }
    return map;
  }, [assignable]);

  const filteredGroups = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return grouped;
    const result = new Map<string, Permission[]>();
    for (const [group, perms] of grouped) {
      if (translateGroup(group).toLowerCase().includes(q)) {
        result.set(group, perms);
        continue;
      }
      const matches = perms.filter(p =>
        p.code.toLowerCase().includes(q) || translateDescription(p).toLowerCase().includes(q));
      if (matches.length > 0) result.set(group, matches);
    }
    return result;
    // eslint-disable-next-line
  }, [grouped, search, t]);

  useEffect(() => {
    if (search.trim()) setOpenGroups([...filteredGroups.keys()]);
  }, [search, filteredGroups]);

  const togglePermission = (code: string) => {
    const next = new Set(selected);
    if (next.has(code)) next.delete(code); else next.add(code);
    onChange(next);
  };

  const toggleGroup = (perms: Permission[]) => {
    const next = new Set(selected);
    const allSelected = perms.every(p => next.has(p.code));
    perms.forEach(p => (allSelected ? next.delete(p.code) : next.add(p.code)));
    onChange(next);
  };

  return (
    <div>
      <div className="flex flex-col sm:flex-row gap-2 mb-2">
        <div className="relative flex-1 min-w-0">
          <Search className="w-3.5 h-3.5 absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" />
          <input type="text" value={search} onChange={e => setSearch(e.target.value)}
            placeholder={t('admin.roles.searchPermissions')}
            className="w-full pl-9 pr-4 py-2 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-[oklch(0.488_0.243_264.376)]/40" />
        </div>
        {searchRowExtra}
      </div>
      <p className="text-xs text-muted-foreground mb-2">{t('admin.roles.studentOnlyDisclaimer')}</p>

      <Accordion type="multiple" value={openGroups} onValueChange={setOpenGroups} className="border border-border rounded-xl overflow-hidden px-3">
        {[...filteredGroups.entries()].map(([group, perms]) => {
          const selectedCount = perms.filter(p => selected.has(p.code)).length;
          const allSelected = selectedCount === perms.length;
          const someSelected = selectedCount > 0 && !allSelected;
          return (
            <AccordionItem key={group} value={group}>
              <div className="flex items-center gap-2">
                <Checkbox
                  checked={allSelected ? true : someSelected ? 'indeterminate' : false}
                  disabled={disabled}
                  onCheckedChange={() => toggleGroup(perms)}
                />
                <AccordionTrigger className="py-3 flex-1">
                  <span className="flex items-center gap-2 w-full pr-2">
                    <span className="flex-1 text-left">{translateGroup(group)}</span>
                    <span className="text-xs text-muted-foreground tabular-nums shrink-0">{selectedCount}/{perms.length}</span>
                  </span>
                </AccordionTrigger>
              </div>
              <AccordionContent>
                <div className="divide-y divide-border pl-6">
                  {perms.map(p => {
                    const risky = RISK_FLAGGED.has(p.code);
                    return (
                      <label key={p.code} className={`flex items-center justify-between gap-3 py-2.5 pr-1 ${disabled ? 'cursor-not-allowed' : 'cursor-pointer'}`}>
                        <span className="flex items-center gap-1.5 text-xs text-foreground min-w-0">
                          {risky && (
                            <span title={t('admin.roles.riskyPermissionHint')}>
                              <AlertTriangle className="w-3 h-3 text-amber-500 shrink-0" />
                            </span>
                          )}
                          <span className="min-w-0">{translateDescription(p)}</span>
                        </span>
                        <Checkbox checked={selected.has(p.code)} disabled={disabled} onCheckedChange={() => togglePermission(p.code)} className="shrink-0" />
                      </label>
                    );
                  })}
                </div>
              </AccordionContent>
            </AccordionItem>
          );
        })}
      </Accordion>
    </div>
  );
}

// ── Roles ────────────────────────────────────────────────────────────────

interface RoleFormState {
  code: string;
  name: string;
  sessionTimeoutMinutes: string;
  permissionCodes: Set<string>;
}

function roleToForm(role: RoleSummary): RoleFormState {
  return { code: role.code, name: role.name, sessionTimeoutMinutes: String(role.sessionTimeoutMinutes), permissionCodes: new Set(role.permissionCodes) };
}

const emptyRoleForm = (): RoleFormState => ({ code: '', name: '', sessionTimeoutMinutes: '480', permissionCodes: new Set() });

interface RoleFormModalProps {
  mode: 'create' | 'edit';
  role?: RoleSummary;
  permissions: Permission[];
  templates: RoleTemplateSummary[];
  onClose: () => void;
  onSave: (form: RoleFormState) => Promise<void>;
}

function RoleFormModal({ mode, role, permissions, templates, onClose, onSave }: RoleFormModalProps) {
  // Escape closes the topmost dialog (issue 102 §5).
  useEscapeToClose(onClose);
  const { t } = useTranslation();
  const [form, setForm] = useState<RoleFormState>(role ? roleToForm(role) : emptyRoleForm());
  const [selectedTemplateId, setSelectedTemplateId] = useState('');
  const [saving, setSaving] = useState(false);
  const isProtected = role?.isProtected ?? false;

  // 07-RoleFinetune.md §2.7: applying a template replaces the current
  // selection outright (a "start from this preset" action, not a merge) —
  // filtered through the assignable set defensively, in case a template
  // ever holds a code this picker wouldn't otherwise offer.
  const applyTemplate = (templateId: string) => {
    setSelectedTemplateId(templateId);
    const template = templates.find(tpl => tpl.id === templateId);
    if (!template) return;
    const assignableCodes = new Set(permissions.filter(p => !NEVER_ASSIGNABLE.has(p.code)).map(p => p.code));
    setForm(f => ({ ...f, permissionCodes: new Set(template.permissionCodes.filter(c => assignableCodes.has(c))) }));
  };

  const handleSave = async () => {
    if (mode === 'create' && !form.code.trim()) { toast.error(t('admin.roles.errorCodeRequired')); return; }
    if (!form.name.trim()) { toast.error(t('admin.roles.errorNameRequired')); return; }
    const timeout = Number(form.sessionTimeoutMinutes);
    if (!timeout || timeout <= 0) { toast.error(t('admin.roles.errorTimeoutInvalid')); return; }
    if (form.permissionCodes.size === 0) { toast.error(t('admin.roles.errorPermissionsRequired')); return; }
    setSaving(true);
    try {
      await onSave(form);
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4">
      <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" />
      <div className="relative bg-card w-full h-full sm:h-auto sm:max-w-2xl sm:max-h-[90vh] sm:rounded-2xl rounded-t-2xl shadow-xl flex flex-col">

        {/* Header */}
        <div className="flex items-center gap-3 px-5 py-4 border-b border-border shrink-0">
          <KeyRound className="w-5 h-5 text-[oklch(0.488_0.243_264.376)] shrink-0" />
          <h3 className="flex-1 text-foreground">
            {mode === 'create' ? t('admin.roles.createRole') : isProtected ? t('admin.roles.viewRole') : t('admin.roles.editRole')}
          </h3>
        </div>

        {/* Body */}
        <div className="flex-1 overflow-y-auto px-5 py-4 space-y-4 text-sm">
          {mode === 'create' && (
            <div>
              <label className="block text-xs text-muted-foreground mb-1.5">{t('admin.roles.codeLabel')} *</label>
              <input type="text" value={form.code} onChange={e => setForm(f => ({ ...f, code: e.target.value.trim() }))}
                placeholder={t('admin.roles.codePlaceholder')} maxLength={32}
                className="w-full px-4 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-[oklch(0.488_0.243_264.376)]/40" />
              <p className="text-xs text-muted-foreground mt-1">{t('admin.roles.codeHint')}</p>
            </div>
          )}

          <div>
            <label className="block text-xs text-muted-foreground mb-1.5">{t('admin.roles.nameLabel')} *</label>
            <input type="text" value={form.name} disabled={isProtected} onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
              maxLength={100}
              className="w-full px-4 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-[oklch(0.488_0.243_264.376)]/40 disabled:opacity-60 disabled:cursor-not-allowed" />
          </div>

          <div>
            <label className="block text-xs text-muted-foreground mb-1.5">{t('admin.roles.sessionTimeoutLabel')} * ({t('admin.roles.minutesUnit')})</label>
            <input type="number" min="1" value={form.sessionTimeoutMinutes} disabled={isProtected}
              onChange={e => setForm(f => ({ ...f, sessionTimeoutMinutes: e.target.value }))}
              className="w-full px-4 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-[oklch(0.488_0.243_264.376)]/40 disabled:opacity-60 disabled:cursor-not-allowed" />
          </div>

          {/* Permissions */}
          <div>
            <label className="block text-xs text-muted-foreground mb-2">{t('admin.roles.permissionsLabel')} *</label>
            <PermissionPicker
              permissions={permissions}
              selected={form.permissionCodes}
              onChange={codes => setForm(f => ({ ...f, permissionCodes: codes }))}
              disabled={isProtected}
              searchRowExtra={mode === 'create' && templates.length > 0 ? (
                <Select value={selectedTemplateId || undefined} onValueChange={applyTemplate}>
                  <SelectTrigger className="w-full sm:w-48 shrink-0">
                    <SelectValue placeholder={t('admin.roles.selectTemplatePlaceholder')} />
                  </SelectTrigger>
                  <SelectContent>
                    {templates.map(tpl => (
                      <SelectItem key={tpl.id} value={tpl.id}>{tpl.name}</SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              ) : undefined}
            />
          </div>

          {isProtected && (
            <p className="text-xs text-muted-foreground bg-muted rounded-xl px-3 py-2.5 flex items-center gap-2">
              <Lock className="w-3.5 h-3.5 shrink-0" /> {t('admin.roles.protectedNote')}
            </p>
          )}
        </div>

        {/* Footer */}
        <div className="px-5 py-4 border-t border-border shrink-0 flex gap-3">
          {isProtected ? (
            <button type="button" onClick={onClose}
              className="flex-1 py-2.5 rounded-xl border border-border text-sm text-foreground hover:bg-accent">
              {t('admin.roles.closeReadOnly')}
            </button>
          ) : (
            <>
              <button type="button" onClick={onClose}
                className="flex-1 py-2.5 rounded-xl border border-border text-sm text-foreground hover:bg-accent">
                {t('common.cancel')}
              </button>
              <button type="button" onClick={handleSave} disabled={saving}
                className="flex-1 py-2.5 rounded-xl bg-[oklch(0.488_0.243_264.376)] text-white text-sm hover:opacity-90 transition-opacity disabled:opacity-40">
                {mode === 'create' ? t('admin.roles.createRole') : t('admin.roles.saveChanges')}
              </button>
            </>
          )}
        </div>
      </div>
    </div>
  );
}

// ── Role templates ──────────────────────────────────────────────────────

interface RoleTemplateFormState {
  name: string;
  permissionCodes: Set<string>;
}

function templateToForm(template: RoleTemplateSummary): RoleTemplateFormState {
  return { name: template.name, permissionCodes: new Set(template.permissionCodes) };
}

const emptyTemplateForm = (): RoleTemplateFormState => ({ name: '', permissionCodes: new Set() });

interface RoleTemplateFormModalProps {
  mode: 'create' | 'edit';
  template?: RoleTemplateSummary;
  permissions: Permission[];
  onClose: () => void;
  onSave: (form: RoleTemplateFormState) => Promise<void>;
}

/** 07-RoleFinetune.md §2.7: name + permissions only — no code/session-timeout, unlike `RoleFormModal` (neither is meaningful for a template). */
function RoleTemplateFormModal({ mode, template, permissions, onClose, onSave }: RoleTemplateFormModalProps) {
  // Escape closes the topmost dialog (issue 102 §5).
  useEscapeToClose(onClose);
  const { t } = useTranslation();
  const [form, setForm] = useState<RoleTemplateFormState>(template ? templateToForm(template) : emptyTemplateForm());
  const [saving, setSaving] = useState(false);

  const handleSave = async () => {
    if (!form.name.trim()) { toast.error(t('admin.roles.errorNameRequired')); return; }
    if (form.permissionCodes.size === 0) { toast.error(t('admin.roles.errorPermissionsRequired')); return; }
    setSaving(true);
    try {
      await onSave(form);
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4">
      <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" />
      <div className="relative bg-card w-full h-full sm:h-auto sm:max-w-2xl sm:max-h-[90vh] sm:rounded-2xl rounded-t-2xl shadow-xl flex flex-col">

        <div className="flex items-center gap-3 px-5 py-4 border-b border-border shrink-0">
          <LayoutTemplate className="w-5 h-5 text-[oklch(0.488_0.243_264.376)] shrink-0" />
          <h3 className="flex-1 text-foreground">{mode === 'create' ? t('admin.roles.createTemplate') : t('admin.roles.editTemplate')}</h3>
        </div>

        <div className="flex-1 overflow-y-auto px-5 py-4 space-y-4 text-sm">
          <div>
            <label className="block text-xs text-muted-foreground mb-1.5">{t('admin.roles.nameLabel')} *</label>
            <input type="text" value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
              maxLength={100}
              className="w-full px-4 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-[oklch(0.488_0.243_264.376)]/40" />
          </div>

          <div>
            <label className="block text-xs text-muted-foreground mb-2">{t('admin.roles.permissionsLabel')} *</label>
            <PermissionPicker permissions={permissions} selected={form.permissionCodes}
              onChange={codes => setForm(f => ({ ...f, permissionCodes: codes }))} />
          </div>

        </div>

        <div className="px-5 py-4 border-t border-border shrink-0 flex gap-3">
          <button type="button" onClick={onClose}
            className="flex-1 py-2.5 rounded-xl border border-border text-sm text-foreground hover:bg-accent">
            {t('common.cancel')}
          </button>
          <button type="button" onClick={handleSave} disabled={saving}
            className="flex-1 py-2.5 rounded-xl bg-[oklch(0.488_0.243_264.376)] text-white text-sm hover:opacity-90 transition-opacity disabled:opacity-40">
            {mode === 'create' ? t('admin.roles.createTemplate') : t('admin.roles.saveChanges')}
          </button>
        </div>
      </div>
    </div>
  );
}

// ── Main component ──────────────────────────────────────────────────────

export function RoleManagement({ roles, canManage, canManageTemplates, onChanged }: {
  roles: RoleSummary[];
  canManage: boolean;
  /** 07-RoleFinetune.md §2.7: admin/sysadmin-only, enforced server-side too — this only hides buttons that would otherwise 403. */
  canManageTemplates: boolean;
  onChanged: () => void;
}) {
  const { t } = useTranslation();
  const [permissions, setPermissions] = useState<Permission[]>([]);
  const [templates, setTemplates] = useState<RoleTemplateSummary[]>([]);
  const [createOpen, setCreateOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<RoleSummary | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<RoleSummary | null>(null);
  const [templateCreateOpen, setTemplateCreateOpen] = useState(false);
  const [templateEditTarget, setTemplateEditTarget] = useState<RoleTemplateSummary | null>(null);
  const [templateDeleteTarget, setTemplateDeleteTarget] = useState<RoleTemplateSummary | null>(null);

  useEffect(() => {
    api.permissions.list().then(setPermissions).catch(() => {});
  }, []);

  const loadTemplates = () => {
    api.roleTemplates.list().then(setTemplates).catch(() => {});
  };
  useEffect(loadTemplates, []);

  const handleCreate = async (form: RoleFormState) => {
    try {
      await api.roles.create({
        code: form.code, name: form.name.trim(),
        sessionTimeoutMinutes: Number(form.sessionTimeoutMinutes),
        permissionCodes: [...form.permissionCodes],
      });
      setCreateOpen(false);
      onChanged();
      toast.success(t('admin.roles.roleCreated', { name: form.name.trim() }));
    } catch (err) {
      if (err instanceof ApiError) toast.error(mapApiError(err).message);
      else toast.error(String(err));
    }
  };

  const handleEdit = async (form: RoleFormState) => {
    if (!editTarget) return;
    try {
      await api.roles.update(editTarget.id, {
        name: form.name.trim(),
        sessionTimeoutMinutes: Number(form.sessionTimeoutMinutes),
        permissionCodes: [...form.permissionCodes],
        version: editTarget.version,
      });
      setEditTarget(null);
      onChanged();
      toast.success(t('admin.roles.roleUpdated'));
    } catch (err) {
      if (err instanceof ApiError) toast.error(mapApiError(err).message);
      else toast.error(String(err));
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    try {
      await api.roles.remove(deleteTarget.id);
      setDeleteTarget(null);
      onChanged();
      toast.success(t('admin.roles.roleDeleted', { name: deleteTarget.name }));
    } catch (err) {
      if (err instanceof ApiError) toast.error(mapApiError(err).message);
      else toast.error(String(err));
    }
  };

  const handleTemplateCreate = async (form: RoleTemplateFormState) => {
    try {
      await api.roleTemplates.create({ name: form.name.trim(), permissionCodes: [...form.permissionCodes] });
      setTemplateCreateOpen(false);
      loadTemplates();
      toast.success(t('admin.roles.templateCreated', { name: form.name.trim() }));
    } catch (err) {
      if (err instanceof ApiError) toast.error(mapApiError(err).message);
      else toast.error(String(err));
    }
  };

  const handleTemplateEdit = async (form: RoleTemplateFormState) => {
    if (!templateEditTarget) return;
    try {
      await api.roleTemplates.update(templateEditTarget.id, {
        name: form.name.trim(), permissionCodes: [...form.permissionCodes], version: templateEditTarget.version,
      });
      setTemplateEditTarget(null);
      loadTemplates();
      toast.success(t('admin.roles.templateUpdated'));
    } catch (err) {
      if (err instanceof ApiError) toast.error(mapApiError(err).message);
      else toast.error(String(err));
    }
  };

  const handleTemplateDelete = async () => {
    if (!templateDeleteTarget) return;
    try {
      await api.roleTemplates.remove(templateDeleteTarget.id);
      setTemplateDeleteTarget(null);
      loadTemplates();
      toast.success(t('admin.roles.templateDeleted', { name: templateDeleteTarget.name }));
    } catch (err) {
      if (err instanceof ApiError) toast.error(mapApiError(err).message);
      else toast.error(String(err));
    }
  };

  return (
    <div className="space-y-6">
      {/* Card 1: Role templates */}
      <div className="space-y-3">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-1.5">
            <LayoutTemplate className="w-4 h-4 text-muted-foreground" />
            <p className="text-xs text-muted-foreground">{t('admin.roles.templateCount', { count: templates.length })}</p>
          </div>
          {canManageTemplates && (
            <button onClick={() => setTemplateCreateOpen(true)}
              className="flex items-center gap-1.5 px-4 py-2 bg-[oklch(0.488_0.243_264.376)] text-white rounded-xl text-sm hover:opacity-90 transition-opacity">
              <Plus className="w-4 h-4" /> {t('admin.roles.createTemplate')}
            </button>
          )}
        </div>

        <div className="hidden sm:block bg-card border border-border rounded-2xl overflow-hidden">
          <table className="w-full">
            <thead>
              <tr className="border-b border-border">
                {[t('admin.roles.colName'), t('admin.roles.colPermissions'), t('admin.roles.colActions')].map(h => (
                  <th key={h} className="px-4 py-3 text-left text-xs text-muted-foreground font-medium">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {templates.map(tpl => (
                <tr key={tpl.id} className="border-b border-border last:border-b-0 hover:bg-muted/30">
                  <td className="px-4 py-3 text-sm text-foreground font-medium">{tpl.name}</td>
                  <td className="px-4 py-3 text-xs text-muted-foreground">{t('admin.roles.permissionCount', { count: tpl.permissionCodes.length })}</td>
                  <td className="px-4 py-3">
                    {canManageTemplates && (
                      <div className="flex items-center gap-2">
                        <button onClick={() => setTemplateEditTarget(tpl)} className={ACTION_BTN_BLUE}>
                          <Pencil className="w-3 h-3" /> {t('admin.system.edit')}
                        </button>
                        <button onClick={() => setTemplateDeleteTarget(tpl)} className={ACTION_BTN_RED}>
                          <Trash2 className="w-3 h-3" /> {t('common.delete')}
                        </button>
                      </div>
                    )}
                  </td>
                </tr>
              ))}
              {templates.length === 0 && (
                <tr><td colSpan={3} className="px-4 py-6 text-center text-xs text-muted-foreground">{t('admin.roles.noTemplates')}</td></tr>
              )}
            </tbody>
          </table>
        </div>

        <div className="sm:hidden space-y-2">
          {templates.map(tpl => (
            <div key={tpl.id} className="bg-card border border-border rounded-2xl p-4">
              <div className="flex items-start justify-between gap-3">
                <div className="flex-1 min-w-0">
                  <div className="text-sm text-foreground font-medium mb-1">{tpl.name}</div>
                  <div className="text-xs text-muted-foreground">{t('admin.roles.permissionCount', { count: tpl.permissionCodes.length })}</div>
                </div>
                {canManageTemplates && (
                  <div className="flex flex-col gap-1.5 shrink-0">
                    <button onClick={() => setTemplateEditTarget(tpl)} className={ACTION_BTN_BLUE}>
                      <Pencil className="w-3 h-3" /> {t('admin.system.edit')}
                    </button>
                    <button onClick={() => setTemplateDeleteTarget(tpl)} className={ACTION_BTN_RED}>
                      <Trash2 className="w-3 h-3" /> {t('common.delete')}
                    </button>
                  </div>
                )}
              </div>
            </div>
          ))}
          {templates.length === 0 && (
            <p className="text-center text-xs text-muted-foreground py-6">{t('admin.roles.noTemplates')}</p>
          )}
        </div>
      </div>

      {/* Card 2: Roles */}
      <div className="space-y-3">
        <div className="flex items-center justify-between">
          <p className="text-xs text-muted-foreground">{t('admin.roles.roleCount', { count: roles.length })}</p>
          {canManage && (
            <button onClick={() => setCreateOpen(true)}
              className="flex items-center gap-1.5 px-4 py-2 bg-[oklch(0.488_0.243_264.376)] text-white rounded-xl text-sm hover:opacity-90 transition-opacity">
              <Plus className="w-4 h-4" /> {t('admin.roles.createRole')}
            </button>
          )}
        </div>

        {/* Desktop table */}
        <div className="hidden sm:block bg-card border border-border rounded-2xl overflow-hidden">
          <table className="w-full">
            <thead>
              <tr className="border-b border-border">
                {[t('admin.roles.colName'), t('admin.roles.colCode'), t('admin.roles.colPermissions'), t('admin.roles.colUsers'), t('admin.roles.colTimeout'), t('admin.roles.colActions')].map(h => (
                  <th key={h} className="px-4 py-3 text-left text-xs text-muted-foreground font-medium">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {roles.map(r => {
                const deleteDisabled = r.userCount > 0;
                return (
                <tr key={r.id} className="border-b border-border last:border-b-0 hover:bg-muted/30">
                  <td className="px-4 py-3 text-sm text-foreground font-medium">
                    <span className="flex items-center gap-1.5">
                      {r.name}
                      {r.isProtected && <Lock className="w-3 h-3 text-muted-foreground/60" />}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-xs text-muted-foreground"><code>{r.code}</code></td>
                  <td className="px-4 py-3 text-xs text-muted-foreground">{t('admin.roles.permissionCount', { count: r.permissionCodes.length })}</td>
                  <td className="px-4 py-3 text-xs text-muted-foreground">{t('admin.roles.userCount', { count: r.userCount })}</td>
                  <td className="px-4 py-3 text-xs text-muted-foreground">{r.sessionTimeoutMinutes} {t('admin.roles.minutesUnit')}</td>
                  <td className="px-4 py-3">
                    {canManage && (
                      <div className="flex items-center gap-2">
                        <button onClick={() => setEditTarget(r)} className={ACTION_BTN_BLUE}>
                          {r.isProtected ? <Eye className="w-3 h-3" /> : <Pencil className="w-3 h-3" />}
                          {r.isProtected ? t('admin.system.view') : t('admin.system.edit')}
                        </button>
                        {!r.isProtected && (
                          <button onClick={() => !deleteDisabled && setDeleteTarget(r)}
                            disabled={deleteDisabled}
                            title={deleteDisabled ? t('admin.roles.deleteBlockedInUse', { count: r.userCount }) : undefined}
                            className={deleteDisabled ? ACTION_BTN_RED_DISABLED : ACTION_BTN_RED}>
                            <Trash2 className="w-3 h-3" /> {t('common.delete')}
                          </button>
                        )}
                      </div>
                    )}
                  </td>
                </tr>
                );
              })}
            </tbody>
          </table>
        </div>

        {/* Mobile cards */}
        <div className="sm:hidden space-y-2">
          {roles.map(r => {
            const deleteDisabled = r.userCount > 0;
            return (
            <div key={r.id} className="bg-card border border-border rounded-2xl p-4">
              <div className="flex items-start justify-between gap-3">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-1.5 mb-1">
                    <span className="text-sm text-foreground font-medium">{r.name}</span>
                    {r.isProtected && <Lock className="w-3 h-3 text-muted-foreground/60" />}
                  </div>
                  <div className="text-xs text-muted-foreground mb-1"><code>{r.code}</code></div>
                  <div className="text-xs text-muted-foreground">
                    {t('admin.roles.permissionCount', { count: r.permissionCodes.length })} · {t('admin.roles.userCount', { count: r.userCount })} · {r.sessionTimeoutMinutes} {t('admin.roles.minutesUnit')}
                  </div>
                </div>
                {canManage && (
                  <div className="flex flex-col gap-1.5 shrink-0">
                    <button onClick={() => setEditTarget(r)} className={ACTION_BTN_BLUE}>
                      {r.isProtected ? <Eye className="w-3 h-3" /> : <Pencil className="w-3 h-3" />}
                      {r.isProtected ? t('admin.system.view') : t('admin.system.edit')}
                    </button>
                    {!r.isProtected && (
                      <button onClick={() => !deleteDisabled && setDeleteTarget(r)}
                        disabled={deleteDisabled}
                        title={deleteDisabled ? t('admin.roles.deleteBlockedInUse', { count: r.userCount }) : undefined}
                        className={deleteDisabled ? ACTION_BTN_RED_DISABLED : ACTION_BTN_RED}>
                        <Trash2 className="w-3 h-3" /> {t('common.delete')}
                      </button>
                    )}
                  </div>
                )}
              </div>
            </div>
            );
          })}
        </div>
      </div>

      {createOpen && (
        <RoleFormModal mode="create" permissions={permissions} templates={templates} onClose={() => setCreateOpen(false)} onSave={handleCreate} />
      )}
      {editTarget && (
        <RoleFormModal mode="edit" role={editTarget} permissions={permissions} templates={templates}
          onClose={() => setEditTarget(null)} onSave={handleEdit} />
      )}
      {deleteTarget && (
        <ConfirmDeleteDialog
          title={t('admin.roles.deleteRole')}
          message={t('admin.roles.confirmDeleteRole')}
          onCancel={() => setDeleteTarget(null)}
          onConfirm={handleDelete}
        />
      )}

      {templateCreateOpen && (
        <RoleTemplateFormModal mode="create" permissions={permissions} onClose={() => setTemplateCreateOpen(false)} onSave={handleTemplateCreate} />
      )}
      {templateEditTarget && (
        <RoleTemplateFormModal mode="edit" template={templateEditTarget} permissions={permissions}
          onClose={() => setTemplateEditTarget(null)} onSave={handleTemplateEdit} />
      )}
      {templateDeleteTarget && (
        <ConfirmDeleteDialog
          title={t('admin.roles.deleteTemplate')}
          message={t('admin.roles.confirmDeleteTemplate')}
          onCancel={() => setTemplateDeleteTarget(null)}
          onConfirm={handleTemplateDelete}
        />
      )}
    </div>
  );
}
