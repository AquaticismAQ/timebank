import { useState, useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { Users, Shield, FileText, Settings, Download, Upload, ChevronRight, AlertTriangle, Plus, X, Eye, EyeOff, Pencil, Trash2, Lock, Building2, KeyRound } from 'lucide-react';
import { toast } from 'sonner';
import { useAuth, useLogs, usePermissions } from '../../App';
import { api, type ImportJob } from '../../api/client';
import { ApiError, mapApiError, newIdempotencyKey } from '../../api/http';
import { OrganizationManagement } from './OrganizationManagement';
import { RoleManagement } from './RoleManagement';
import { GradePicker } from '../GradePicker';
import { useDictionaries } from '../../api/useDictionaries';
import { SearchPicker, type SearchPickerOption } from '../SearchPicker';
import { formatGradeClass } from '../../lib/format';
import type { RoleSummary } from '../../api/types';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../ui/select';

/**
 * Row-action button shape shared by every list/table in the admin portal
 * (Users, Roles, Rule Settings) — icon + text, bordered, colored per
 * action but never filled, matching Student Management's row-action shape.
 */
const ACTION_BTN_BLUE = 'inline-flex items-center gap-1 text-xs px-2 py-1 rounded-lg border border-blue-200 dark:border-blue-800 text-blue-600 dark:text-blue-400 hover:bg-blue-50 dark:hover:bg-blue-900/20 transition-colors';

// ── Rule config types ──────────────────────────────────────────────────────

interface RuleItem {
  label: string;
  value: string;
  field: string;
  unit?: string;
}

interface RuleSection {
  id: string;
  title: string;
  items: RuleItem[];
}

const INITIAL_RULES: RuleSection[] = [
  {
    id: 'high',
    title: 'admin.system.rules.highSection',
    items: [
      { label: 'admin.system.rules.highAnnual', value: '75', field: 'highAnnual', unit: 'admin.system.rules.highAnnualUnit' },
      { label: 'admin.system.rules.highTotal', value: '300', field: 'highTotal', unit: 'admin.system.rules.highTotalUnit' },
      { label: 'admin.system.rules.highMin', value: '0.5', field: 'highMin', unit: 'admin.system.rules.highMinUnit' },
    ],
  },
  {
    id: 'middle',
    title: 'admin.system.rules.middleSection',
    items: [
      { label: 'admin.system.rules.middleAnnual', value: '25', field: 'middleAnnual', unit: 'admin.system.rules.middleAnnualUnit' },
      { label: 'admin.system.rules.middleTotal', value: '75', field: 'middleTotal', unit: 'admin.system.rules.middleTotalUnit' },
    ],
  },
  {
    id: 'coin',
    title: 'admin.system.rules.coinSection',
    items: [
      { label: 'admin.system.rules.coinRate', value: '1', field: 'coinRate', unit: 'admin.system.rules.coinRateUnit' },
      { label: 'admin.system.rules.coinMin', value: '0.5', field: 'coinMin', unit: 'admin.system.rules.coinMinUnit' },
    ],
  },
];

interface RuleModalProps {
  section: RuleSection;
  onClose: () => void;
  onSave: (updated: RuleSection) => void;
}

function RuleModal({ section, onClose, onSave }: RuleModalProps) {
  const { t } = useTranslation();
  const [items, setItems] = useState<RuleItem[]>(section.items.map(i => ({ ...i })));
  const [confirmed, setConfirmed] = useState(false);

  const updateValue = (field: string, val: string) => {
    setItems(prev => prev.map(i => i.field === field ? { ...i, value: val } : i));
  };

  const handleSave = () => {
    for (const item of items) {
      if (item.value.trim() === '' || isNaN(Number(item.value))) {
        toast.error(t('admin.system.ruleRequiresNumber', { label: t(item.label) }));
        return;
      }
      if (Number(item.value) <= 0) {
        toast.error(t('admin.system.ruleMustBePositive', { label: t(item.label) }));
        return;
      }
    }
    if (!confirmed) {
      toast.error(t('admin.system.checkConfirmationBox'));
      return;
    }
    onSave({ ...section, items });
  };

  const hasChanges = items.some((item, i) => item.value !== section.items[i].value);

  return (
    <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4">
      <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" onClick={onClose} />
      <div className="relative bg-card w-full sm:max-w-md sm:rounded-2xl rounded-t-2xl shadow-xl flex flex-col max-h-[92vh]">

        {/* Header */}
        <div className="flex items-center gap-3 px-5 py-4 border-b border-border shrink-0">
          <Shield className="w-5 h-5 text-[oklch(0.488_0.243_264.376)] shrink-0" />
          <h3 className="flex-1 text-foreground text-sm">{t('admin.system.editRule')}: {t(section.title)}</h3>
          <button onClick={onClose} className="p-1.5 rounded-lg hover:bg-accent text-muted-foreground">
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Warning banner */}
        <div className="mx-5 mt-4 bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-700 rounded-xl p-3 flex gap-2.5">
          <AlertTriangle className="w-4 h-4 text-amber-500 shrink-0 mt-0.5" />
          <p className="text-xs text-amber-800 dark:text-amber-300 leading-relaxed" dangerouslySetInnerHTML={{ __html: t('admin.system.ruleWarning') }} />
        </div>

        {/* Fields */}
        <div className="overflow-y-auto px-5 py-4 space-y-4 text-sm">
          {items.map(item => (
            <div key={item.field}>
              <label className="block text-xs text-muted-foreground mb-1.5">{t(item.label)}</label>
              <div className="flex items-center gap-2">
                <input
                  type="number"
                  min="0"
                  step="0.5"
                  value={item.value}
                  onChange={e => updateValue(item.field, e.target.value)}
                  className="flex-1 px-4 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-[oklch(0.488_0.243_264.376)]/40"
                />
                {item.unit && (
                  <span className="text-xs text-muted-foreground whitespace-nowrap shrink-0">{t(item.unit)}</span>
                )}
              </div>
            </div>
          ))}

          {/* Confirm checkbox */}
          {hasChanges && (
            <label className="flex items-start gap-2.5 pt-1 cursor-pointer">
              <input
                type="checkbox"
                checked={confirmed}
                onChange={e => setConfirmed(e.target.checked)}
                className="mt-0.5 accent-[oklch(0.488_0.243_264.376)]"
              />
              <span className="text-xs text-muted-foreground leading-relaxed">
                {t('admin.system.ruleConfirmText')}
              </span>
            </label>
          )}
        </div>

        {/* Footer */}
        <div className="px-5 py-4 border-t border-border shrink-0 flex gap-3">
          <button type="button" onClick={onClose}
            className="flex-1 py-2.5 rounded-xl border border-border text-sm text-foreground hover:bg-accent">
            {t('common.cancel')}
          </button>
          <button type="button" onClick={handleSave}
            disabled={!hasChanges}
            className="flex-1 py-2.5 rounded-xl bg-[oklch(0.488_0.243_264.376)] text-white text-sm hover:opacity-90 transition-opacity disabled:opacity-40 disabled:cursor-not-allowed">
            {t('admin.system.saveRule')}
          </button>
        </div>
      </div>
    </div>
  );
}

type Tab = 'users' | 'roles' | 'rules' | 'import' | 'logs' | 'organizations' | 'settings';
type UserStatus = 'active' | 'inactive';

interface AdminUser {
  id: string;
  name: string;
  email: string;
  roleCode: string;
  status: UserStatus;
  lastLogin: string;
  version: number;
  scopeGradeIds: string[];
  studentId?: string;
  staffId?: string;
}

/**
 * 07-RoleFinetune.md §2.2/§4: `sysadmin` is deliberately excluded from both
 * the Roles tab's list and this picker — it stays protected and fully
 * functional, just invisible to this round's UI (created only via direct
 * SQL, as today). Roles are otherwise server data now, not a fixed set.
 */
const HIDDEN_ROLE_CODES = new Set(['sysadmin']);

/**
 * 07-RoleFinetune.md §2.6.2: grade assignment applies to any role except
 * these three — admin (and sysadmin) cover every grade by design, student
 * gets their grade from the linked roster row. A structural rule, not a
 * per-role flag, so a newly created custom role gets the grade picker for
 * free without any extra configuration.
 */
const NO_GRADE_SCOPE_ROLES = new Set(['admin', 'student', 'sysadmin']);

/** The three built-in, non-hidden roles keep their bilingual i18n labels; any custom role's `name` (as typed by whoever created it) is shown as-is — there's no per-locale name column to translate a custom role into. */
const KNOWN_ROLE_LABEL_KEYS: Record<string, string> = {
  admin: 'labels.roles.admin', teacher: 'labels.roles.teacher', student: 'labels.roles.student',
};

function roleDisplayName(role: Pick<RoleSummary, 'code' | 'name'>, t: (key: string) => string): string {
  const key = KNOWN_ROLE_LABEL_KEYS[role.code];
  return key ? t(key) : role.name;
}

function roleColor(roleCode: string) {
  if (roleCode === 'admin' || roleCode === 'sysadmin') return 'bg-blue-100 text-blue-700 dark:bg-blue-500/20 dark:text-blue-300';
  if (roleCode === 'teacher') return 'bg-green-100 text-green-700 dark:bg-green-500/20 dark:text-green-300';
  return 'bg-muted text-muted-foreground';
}

interface UserFormState {
  name: string;
  email: string;
  roleCode: string;
  status: UserStatus;
  password: string;
  confirmPassword: string;
  showResetPwd: boolean;
  scopeGradeIds: string[];
  studentId?: string;
  staffId: string;
}

const emptyForm = (): UserFormState => ({
  name: '', email: '', roleCode: '', status: 'active',
  password: '', confirmPassword: '', showResetPwd: false, scopeGradeIds: [],
  studentId: undefined, staffId: '',
});

function userToForm(u: AdminUser): UserFormState {
  return {
    name: u.name, email: u.email, roleCode: u.roleCode, status: u.status,
    password: '', confirmPassword: '', showResetPwd: false, scopeGradeIds: [...u.scopeGradeIds],
    studentId: u.studentId, staffId: u.staffId ?? '',
  };
}

interface UserModalProps {
  mode: 'create' | 'edit';
  initial: UserFormState;
  roles: RoleSummary[];
  onClose: () => void;
  onSave: (form: UserFormState) => void;
  onDelete?: () => void;
}

function UserModal({ mode, initial, roles, onClose, onSave, onDelete }: UserModalProps) {
  const { t } = useTranslation();
  const [form, setForm] = useState<UserFormState>(initial);
  const [showPwd, setShowPwd] = useState(false);
  const [showConfirmPwd, setShowConfirmPwd] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [selectedStudent, setSelectedStudent] = useState<SearchPickerOption[]>([]);
  const { grades } = useDictionaries();

  // Editing an existing student-linked account: the picker needs a
  // friendly label (name/grade/class), which the account list response
  // doesn't carry (only the opaque studentId) — one lookup to seed it.
  useEffect(() => {
    if (initial.studentId) {
      api.students.get(initial.studentId)
        .then(s => setSelectedStudent([{ id: s.id, label: s.name, sublabel: formatGradeClass(s.gradeLevel, s.className) }]))
        .catch(() => {});
    }
  }, [initial.studentId]);

  const set = (patch: Partial<UserFormState>) => setForm(prev => ({ ...prev, ...patch }));

  const selectRole = (roleCode: string) => set({ roleCode });

  const toggleScopeGrade = (gradeId: string) => {
    set({ scopeGradeIds: form.scopeGradeIds.includes(gradeId) ? form.scopeGradeIds.filter(g => g !== gradeId) : [...form.scopeGradeIds, gradeId] });
  };

  // 07-RoleFinetune.md §2.5/§2.6.2: one role per user. Student's linked-
  // roster search and the staffId requirement stay keyed to `isStudent`;
  // grade assignment is now structural (§2.6.2) — any role other than
  // admin/student/sysadmin needs it, not just literal "teacher".
  const isStudent = form.roleCode === 'student';
  const isAdmin = form.roleCode === 'admin';
  const needsGradeScope = form.roleCode !== '' && !NO_GRADE_SCOPE_ROLES.has(form.roleCode);
  const needsStaffId = form.roleCode !== '' && !isStudent;

  const handleSave = () => {
    if (!form.name.trim()) { toast.error(t('admin.system.errorNameRequired')); return; }
    if (!form.email.trim() || !form.email.includes('@')) { toast.error(t('admin.system.errorEmailInvalid')); return; }
    if (!form.roleCode) { toast.error(t('admin.system.errorRoleRequired')); return; }
    if (needsGradeScope && form.scopeGradeIds.length === 0) { toast.error(t('admin.system.errorGradeRequired')); return; }
    if (isStudent && !form.studentId) { toast.error(t('admin.system.errorStudentLinkRequired')); return; }
    if (needsStaffId && !form.staffId.trim()) { toast.error(t('admin.system.errorStaffIdRequired')); return; }
    if (mode === 'create') {
      if (!form.password) { toast.error(t('admin.system.errorPasswordRequired')); return; }
      if (form.password.length < 6) { toast.error(t('admin.system.errorPasswordTooShort')); return; }
      if (form.password !== form.confirmPassword) { toast.error(t('admin.system.errorPasswordMismatch')); return; }
    } else if (form.showResetPwd) {
      if (!form.password) { toast.error(t('admin.system.errorNewPasswordRequired')); return; }
      if (form.password.length < 6) { toast.error(t('admin.system.errorPasswordTooShort')); return; }
      if (form.password !== form.confirmPassword) { toast.error(t('admin.system.errorPasswordMismatch')); return; }
    }
    onSave(form);
  };

  return (
    <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4">
      <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" onClick={onClose} />
      <div className="relative bg-card w-full sm:max-w-md sm:rounded-2xl rounded-t-2xl shadow-xl flex flex-col max-h-[92vh]">

        {/* Header */}
        <div className="flex items-center gap-3 px-5 py-4 border-b border-border shrink-0">
          <Users className="w-5 h-5 text-[oklch(0.488_0.243_264.376)] shrink-0" />
          <h3 className="flex-1 text-foreground">{mode === 'create' ? t('admin.system.createUser') : t('admin.system.editUser')}</h3>
          <button onClick={onClose} className="p-1.5 rounded-lg hover:bg-accent text-muted-foreground">
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Body */}
        <div className="overflow-y-auto px-5 py-4 space-y-4 text-sm">

          {/* Name */}
          <div>
            <label className="block text-xs text-muted-foreground mb-1.5">{t('admin.system.nameLabel')} *</label>
            <input type="text" value={form.name} onChange={e => set({ name: e.target.value })}
              placeholder={t('admin.system.namePlaceholder')}
              className="w-full px-4 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-[oklch(0.488_0.243_264.376)]/40" />
          </div>

          {/* Email */}
          <div>
            <label className="block text-xs text-muted-foreground mb-1.5">{t('admin.system.emailLabel')} *</label>
            <input type="email" value={form.email} onChange={e => set({ email: e.target.value })}
              placeholder={t('admin.system.emailPlaceholder')}
              className="w-full px-4 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-[oklch(0.488_0.243_264.376)]/40" />
          </div>

          {/* Role (single-select dropdown — 07-RoleFinetune.md §2.5/§2.6.3: a
              button row doesn't scale once custom roles are added) */}
          <div>
            <label className="block text-xs text-muted-foreground mb-2">{t('admin.system.rolesLabel')} *</label>
            <Select value={form.roleCode || undefined} onValueChange={selectRole}>
              <SelectTrigger className="w-full">
                <SelectValue placeholder={t('admin.system.selectRolePlaceholder')} />
              </SelectTrigger>
              <SelectContent>
                {roles.map(role => (
                  <SelectItem key={role.id} value={role.code}>{roleDisplayName(role, t)}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          {/* Grade bonding — required for any role except admin/student/
              sysadmin (§2.6.2): admin covers every grade by design, student
              gets theirs from the linked roster row, informational metadata
              post-§2.1, not a visibility restriction. */}
          {needsGradeScope && (
            <div>
              <label className="block text-xs text-muted-foreground mb-2">
                {t('admin.system.scopeGradesLabel')} * ({t('admin.system.multiSelect')})
              </label>
              <GradePicker grades={grades} selected={form.scopeGradeIds} onToggle={toggleScopeGrade} />
            </div>
          )}

          {isAdmin && (
            <p className="text-xs text-muted-foreground bg-muted rounded-xl px-3 py-2.5">
              {t('admin.system.scopeGradesAdminNote')}
            </p>
          )}

          {/* Linked student (role = student): search-and-link to an existing
              roster row rather than free-typing a second copy of grade/class/
              student-number — the roster (CSV-imported) already owns those. */}
          {isStudent && (
            <div>
              <label className="block text-xs text-muted-foreground mb-1.5">
                {t('admin.system.linkedStudentLabel')} *
              </label>
              <SearchPicker
                selected={selectedStudent}
                onChange={opts => { setSelectedStudent(opts); set({ studentId: opts[0]?.id }); }}
                fetchOptions={async search => (await api.students.directory(search))
                  .map(s => ({ id: s.id, label: s.displayName, sublabel: formatGradeClass(s.gradeLevel, s.className) }))}
                placeholder={t('admin.system.linkedStudentPlaceholder')}
                searchPlaceholder={t('admin.system.linkedStudentSearchPlaceholder')}
                noResultsLabel={t('student.proposals.noResults')}
                loadingLabel={t('common.loading')}
              />
            </div>
          )}

          {/* Staff ID (any non-student role) */}
          {needsStaffId && (
            <div>
              <label className="block text-xs text-muted-foreground mb-1.5">{t('admin.system.staffIdLabel')} *</label>
              <input type="text" value={form.staffId} onChange={e => set({ staffId: e.target.value })}
                placeholder={t('admin.system.staffIdPlaceholder')}
                className="w-full px-4 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-[oklch(0.488_0.243_264.376)]/40" />
            </div>
          )}

          {/* Status (edit only) */}
          {mode === 'edit' && (
            <div className="flex items-center justify-between py-1">
              <div>
                <div className="text-sm text-foreground">{t('admin.system.accountStatus')}</div>
                <div className="text-xs text-muted-foreground">{t('admin.system.accountStatusDesc')}</div>
              </div>
              <button type="button" onClick={() => set({ status: form.status === 'active' ? 'inactive' : 'active' })}
                className={`relative w-10 h-6 rounded-full transition-colors duration-200 ${form.status === 'active' ? 'bg-[oklch(0.488_0.243_264.376)]' : 'bg-muted'}`}>
                <span className={`absolute top-1 left-1 w-4 h-4 bg-white rounded-full shadow transition-transform duration-200 ${form.status === 'active' ? 'translate-x-4' : 'translate-x-0'}`} />
              </button>
            </div>
          )}

          {/* Password (create) */}
          {mode === 'create' && (
            <div className="space-y-3">
              <div>
                <label className="block text-xs text-muted-foreground mb-1.5">{t('admin.system.initialPassword')} *</label>
                <div className="relative">
                  <input type={showPwd ? 'text' : 'password'} value={form.password} onChange={e => set({ password: e.target.value })}
                    placeholder={t('admin.system.passwordPlaceholder')}
                    className="w-full px-4 py-2.5 pr-10 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-[oklch(0.488_0.243_264.376)]/40" />
                  <button type="button" onClick={() => setShowPwd(!showPwd)}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground">
                    {showPwd ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                  </button>
                </div>
              </div>
              <div>
                <label className="block text-xs text-muted-foreground mb-1.5">{t('admin.system.confirmPassword')} *</label>
                <div className="relative">
                  <input type={showConfirmPwd ? 'text' : 'password'} value={form.confirmPassword} onChange={e => set({ confirmPassword: e.target.value })}
                    placeholder={t('admin.system.reenterPassword')}
                    className="w-full px-4 py-2.5 pr-10 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-[oklch(0.488_0.243_264.376)]/40" />
                  <button type="button" onClick={() => setShowConfirmPwd(!showConfirmPwd)}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground">
                    {showConfirmPwd ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                  </button>
                </div>
              </div>
            </div>
          )}

          {/* Reset password (edit) */}
          {mode === 'edit' && (
            <div className="border border-border rounded-xl overflow-hidden">
              <button type="button" onClick={() => set({ showResetPwd: !form.showResetPwd, password: '', confirmPassword: '' })}
                className="w-full flex items-center justify-between px-4 py-3 text-sm text-muted-foreground hover:bg-accent transition-colors">
                <span>{t('admin.system.resetPassword')}</span>
                <ChevronRight className={`w-4 h-4 transition-transform ${form.showResetPwd ? 'rotate-90' : ''}`} />
              </button>
              {form.showResetPwd && (
                <div className="px-4 pb-4 space-y-3 border-t border-border pt-3">
                  <div className="relative">
                    <input type={showPwd ? 'text' : 'password'} value={form.password} onChange={e => set({ password: e.target.value })}
                      placeholder={t('admin.system.newPasswordPlaceholder')}
                      className="w-full px-4 py-2.5 pr-10 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-[oklch(0.488_0.243_264.376)]/40" />
                    <button type="button" onClick={() => setShowPwd(!showPwd)}
                      className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground">
                      {showPwd ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                    </button>
                  </div>
                  <input type={showConfirmPwd ? 'text' : 'password'} value={form.confirmPassword} onChange={e => set({ confirmPassword: e.target.value })}
                    placeholder={t('admin.system.confirmNewPassword')}
                    className="w-full px-4 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-[oklch(0.488_0.243_264.376)]/40" />
                </div>
              )}
            </div>
          )}

          {/* Delete (edit only) */}
          {mode === 'edit' && onDelete && (
            <div className="pt-1">
              {!confirmDelete ? (
                <button type="button" onClick={() => setConfirmDelete(true)}
                  className="flex items-center gap-2 text-xs text-destructive hover:text-destructive/80 transition-colors">
                  <Trash2 className="w-3.5 h-3.5" /> {t('admin.system.deleteUser')}
                </button>
              ) : (
                <div className="bg-destructive/10 border border-destructive/30 rounded-xl p-3 space-y-2">
                  <p className="text-xs text-destructive">{t('admin.system.confirmDeleteUser')}</p>
                  <div className="flex gap-2">
                    <button type="button" onClick={() => setConfirmDelete(false)}
                      className="flex-1 py-1.5 rounded-lg border border-border text-xs text-muted-foreground hover:bg-accent">
                      {t('common.cancel')}
                    </button>
                    <button type="button" onClick={onDelete}
                      className="flex-1 py-1.5 rounded-lg bg-destructive text-destructive-foreground text-xs hover:opacity-90">
                      {t('admin.system.confirmDelete')}
                    </button>
                  </div>
                </div>
              )}
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="px-5 py-4 border-t border-border shrink-0 flex gap-3">
          <button type="button" onClick={onClose}
            className="flex-1 py-2.5 rounded-xl border border-border text-sm text-foreground hover:bg-accent">
            {t('common.cancel')}
          </button>
          <button type="button" onClick={handleSave}
            className="flex-1 py-2.5 rounded-xl bg-[oklch(0.488_0.243_264.376)] text-white text-sm hover:opacity-90 transition-opacity">
            {mode === 'create' ? t('admin.system.createUser') : t('admin.system.saveChanges')}
          </button>
        </div>
      </div>
    </div>
  );
}

export function SystemAdmin() {
  const { t } = useTranslation();
  const { user } = useAuth();
  const { hasPermission } = usePermissions();
  const canManageUsers = hasPermission('users.manage');
  // 07-RoleFinetune.md §2.3: was a literal `roles.includes('admin')` check,
  // so a permission-equivalent sysadmin account (post admin/sysadmin merge)
  // still couldn't edit rules from here — permission-based now, matching
  // the backend's `ProjectController.isAdmin` fix.
  const canManageRules = hasPermission('rules.manage');
  const canReadRoles = hasPermission('roles.read');
  const canManageRoles = hasPermission('roles.manage');
  // 07-RoleFinetune.md §2.7: template management is admin/sysadmin-only by
  // role code, not just roles.manage — narrower than ordinary role
  // management (enforced server-side too; this only hides the buttons
  // that would otherwise 403).
  const canManageTemplates = canManageRoles && (user?.roles.includes('admin') || user?.roles.includes('sysadmin')) === true;
  const { logs, addLog } = useLogs();
  const [schoolName, setSchoolName] = useState('');

  useEffect(() => {
    api.school.getInfo().then(info => setSchoolName(info.name)).catch(() => {});
  }, []);
  const [activeTab, setActiveTab] = useState<Tab>('users');
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [roles, setRoles] = useState<RoleSummary[]>([]);
  const [createOpen, setCreateOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<AdminUser | null>(null);
  const [rules, setRules] = useState<RuleSection[]>(INITIAL_RULES);
  const [editingRule, setEditingRule] = useState<RuleSection | null>(null);
  const [features, setFeatures] = useState({ pov: false, redemption: false, borrowing: false, appreciation: false });
  const [saving, setSaving] = useState(false);
  const importFileRef = useRef<HTMLInputElement>(null);
  const studentImportFileRef = useRef<HTMLInputElement>(null);
  const [importType, setImportType] = useState<string>('');

  const loadRoles = () => {
    api.roles.list().then(list => {
      setRoles(list.filter(r => !HIDDEN_ROLE_CODES.has(r.code)));
    }).catch(() => {});
  };

  useEffect(() => {
    loadRoles();
  }, []);

  useEffect(() => {
    api.users.list().then(result => {
      setUsers(result.data.map(u => ({
        id: u.id, name: u.displayName, email: u.email,
        roleCode: u.roleCode,
        status: (u.status as UserStatus) || 'active',
        lastLogin: u.lastLoginAt ? new Date(u.lastLoginAt).toLocaleDateString() : '—',
        version: u.version, scopeGradeIds: u.scopeGradeIds,
        studentId: u.studentId, staffId: u.staffId,
      })));
    }).catch(() => {});
  }, []);

  const roleName = (code: string): string => {
    const role = roles.find(r => r.code === code);
    return role ? roleDisplayName(role, t) : code;
  };

  const toggleFeature = (_key: keyof typeof features) => {
    toast.info(t('admin.system.featureToggleNotAvailable') || 'Feature flag management is not yet available');
  };

  const handleRuleSave = (updated: RuleSection) => {
    setRules(prev => prev.map(r => r.id === updated.id ? updated : r));
    addLog({ operator: user?.displayName ?? 'Admin', action: t('admin.system.rules.logModifyRule', { title: t(updated.title) }), target: t('admin.system.rules.logRuleTarget'), category: 'system' });
    setEditingRule(null);
    toast.success(t('admin.system.ruleUpdated', { title: t(updated.title) }));
  };

  const handleCreate = async (form: UserFormState) => {
    setSaving(true);
    try {
      const needsGradeScope = form.roleCode !== '' && !NO_GRADE_SCOPE_ROLES.has(form.roleCode);
      const isStudent = form.roleCode === 'student';
      const needsStaffId = form.roleCode !== '' && !isStudent;
      const result = await api.users.create({
        username: form.email.split('@')[0],
        initialPassword: form.password,
        displayName: form.name.trim(),
        email: form.email.trim(),
        roleCode: form.roleCode,
        scopeGradeIds: needsGradeScope ? form.scopeGradeIds : undefined,
        studentId: isStudent ? form.studentId : undefined,
        staffId: needsStaffId ? form.staffId.trim() : undefined,
      });
      setUsers(prev => [...prev, {
        id: result.id, name: form.name.trim(), email: form.email.trim(),
        roleCode: form.roleCode, status: 'active', lastLogin: '—', version: 1,
        scopeGradeIds: needsGradeScope ? form.scopeGradeIds : [],
        studentId: isStudent ? form.studentId : undefined,
        staffId: needsStaffId ? form.staffId.trim() : undefined,
      }]);
      addLog({ operator: user?.displayName ?? 'Admin', action: t('admin.system.users.logCreateUser'), target: `${form.name.trim()} (${form.email.trim()})`, category: 'system' });
      setCreateOpen(false);
      toast.success(t('admin.system.userCreated', { name: form.name.trim() }));
    } catch (err) {
      if (err instanceof ApiError) toast.error(mapApiError(err).message);
      else toast.error(String(err));
    } finally {
      setSaving(false);
    }
  };

  const handleEdit = async (form: UserFormState) => {
    if (!editTarget) return;
    setSaving(true);
    try {
      const needsGradeScope = form.roleCode !== '' && !NO_GRADE_SCOPE_ROLES.has(form.roleCode);
      const isStudent = form.roleCode === 'student';
      const needsStaffId = form.roleCode !== '' && !isStudent;
      await api.users.update(editTarget.id, {
        displayName: form.name.trim(),
        email: form.email.trim(),
        status: form.status,
        roleCode: form.roleCode,
        version: editTarget.version,
        scopeGradeIds: needsGradeScope ? form.scopeGradeIds : [],
        studentId: isStudent ? form.studentId : undefined,
        staffId: needsStaffId ? form.staffId.trim() : undefined,
      });
      if (form.showResetPwd && form.password) {
        const result = await api.users.resetPassword(editTarget.id);
        toast.success(t('admin.system.passwordReset') + (result.temporaryPassword ? `: ${result.temporaryPassword}` : ''));
      }
      setUsers(prev => prev.map(u => u.id === editTarget.id
        ? { ...u, name: form.name.trim(), email: form.email.trim(), roleCode: form.roleCode, status: form.status, version: u.version + 1,
            scopeGradeIds: needsGradeScope ? form.scopeGradeIds : [],
            studentId: isStudent ? form.studentId : undefined,
            staffId: needsStaffId ? form.staffId.trim() : undefined }
        : u
      ));
      addLog({ operator: user?.displayName ?? 'Admin', action: t('admin.system.users.logEditUser'), target: `${form.name.trim()} (${form.email.trim()})`, category: 'system' });
      setEditTarget(null);
      toast.success(t('admin.system.userUpdated'));
    } catch (err) {
      if (err instanceof ApiError) toast.error(mapApiError(err).message);
      else toast.error(String(err));
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async () => {
    if (!editTarget) return;
    setSaving(true);
    try {
      await api.users.update(editTarget.id, { status: 'inactive', version: editTarget.version });
      addLog({ operator: user?.displayName ?? 'Admin', action: t('admin.system.users.logDeleteUser'), target: `${editTarget.name} (${editTarget.email})`, category: 'system' });
      setUsers(prev => prev.map(u => u.id === editTarget.id ? { ...u, status: 'inactive' as UserStatus, version: u.version + 1 } : u));
      setEditTarget(null);
      toast.success(t('admin.system.userDeleted', { name: editTarget.name }));
    } catch (err) {
      if (err instanceof ApiError) toast.error(mapApiError(err).message);
      else toast.error(String(err));
    } finally {
      setSaving(false);
    }
  };

  const handleTemplateDownload = async (type: string) => {
    try {
      const blob = await api.imports.template(type);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url; a.download = `${type}-template.csv`;
      document.body.appendChild(a); a.click();
      document.body.removeChild(a); URL.revokeObjectURL(url);
      toast.success(t(type === 'students' ? 'admin.system.studentTemplateDownloaded' : 'admin.system.templateDownloaded'));
    } catch (err) {
      if (err instanceof ApiError) toast.error(mapApiError(err).message);
      else toast.error(t('common.unknownError'));
    }
  };

  // validate/commit are async (Job starts VALIDATING/COMMITTING and only
  // reaches a terminal status once ImportJobRunner finishes) — their own
  // immediate responses never carry totalRows/validRows yet. This used to
  // read those fields straight off the create-time response (always
  // undefined), so every upload silently took the "no valid rows" branch
  // and cancelled the job regardless of what was actually in the file.
  const pollImportJob = async (jobId: string, doneStatuses: string[]): Promise<ImportJob> => {
    for (;;) {
      const job = await api.imports.get(jobId);
      if (doneStatuses.includes(job.status)) return job;
      await new Promise(resolve => setTimeout(resolve, 1500));
    }
  };

  const handleImportUpload = async (file: File, type: string) => {
    setSaving(true);
    try {
      const job = await api.imports.create(type, file);
      await api.imports.validate(job.id);
      const validated = await pollImportJob(job.id, ['VALIDATED', 'VALIDATION_FAILED']);
      if (validated.status === 'VALIDATION_FAILED') {
        toast.error(t('common.unknownError'));
        return;
      }
      const totalRows = validated.totalRows ?? 0;
      const validRows = validated.validRows ?? 0;
      const invalidRows = totalRows - validRows;
      if (invalidRows > 0) {
        const errResult = await api.imports.errors(job.id);
        toast.error(`${invalidRows} invalid rows: ${errResult.errors.slice(0, 3).map(e => `Row ${e.rowNumber}: ${e.message}`).join('; ')}`);
      }
      if (validRows > 0) {
        await api.imports.commit(job.id, newIdempotencyKey());
        const committed = await pollImportJob(job.id, ['COMMITTED', 'COMMIT_FAILED']);
        if (committed.status === 'COMMIT_FAILED') {
          toast.error(t('common.unknownError'));
        } else {
          // `validRows` doubles as "rows committed" once status is COMMITTED (JobProgressWriter.succeed) — same field, different meaning post-commit.
          toast.success(`Import complete: ${committed.validRows ?? 0} rows committed`);
        }
      } else {
        await api.imports.cancel(job.id);
        toast.error('No valid rows to import');
      }
    } catch (err) {
      if (err instanceof ApiError) toast.error(mapApiError(err).message);
      else toast.error(t('common.unknownError'));
    } finally {
      setSaving(false);
    }
  };

  const handleExport = async (reportType: string) => {
    try {
      const job = await api.exports.create(reportType);
      const poll = async (): Promise<void> => {
        try {
          const status = await api.exports.get(job.jobId);
          if (status.status === 'SUCCEEDED' || status.status === 'succeeded') {
            const dl = await api.exports.download(job.jobId);
            window.open(dl.downloadUrl, '_blank');
            toast.success(t('admin.system.exportTaskCreated', { label: reportType }));
          } else if (status.status === 'FAILED' || status.status === 'failed') {
            toast.error(t('admin.system.exportFailed'));
          } else {
            setTimeout(poll, 2000);
          }
        } catch (err) {
          // The polling loop runs detached from the outer try/catch (it's
          // invoked via setTimeout, after the outer call already returned) —
          // an error here used to become an unhandled promise rejection: no
          // toast, no console-visible failure, just the "Export started..."
          // toast sitting there looking like nothing happened.
          if (err instanceof ApiError) toast.error(mapApiError(err).message);
          else toast.error(t('common.unknownError'));
        }
      };
      toast.info(t('admin.system.exportStarted'));
      setTimeout(poll, 1000);
    } catch (err) {
      if (err instanceof ApiError) toast.error(mapApiError(err).message);
      else toast.error(t('common.unknownError'));
    }
  };

  const tabs: { key: Tab; label: string; icon: typeof Users }[] = [
    { key: 'users', label: t('admin.system.tabUsers'), icon: Users },
    ...(canReadRoles ? [{ key: 'roles' as const, label: t('admin.system.tabRoles'), icon: KeyRound }] : []),
    { key: 'rules', label: t('admin.system.tabRules'), icon: Shield },
    { key: 'import', label: t('admin.system.tabImport'), icon: FileText },
    { key: 'logs', label: t('admin.system.tabLogs'), icon: FileText },
    { key: 'organizations', label: t('admin.system.tabOrganizations'), icon: Building2 },
    { key: 'settings', label: t('admin.system.tabSettings'), icon: Settings },
  ];

  return (
    <div className="p-4 lg:p-6 space-y-4">
      <div>
        <h1 className="text-foreground">{t('admin.system.title')}</h1>
        <p className="text-sm text-muted-foreground">{t('admin.system.subtitle')}</p>
      </div>

      {/* Tab navigation */}
      <div className="flex gap-1 bg-muted rounded-xl p-1 overflow-x-auto">
        {tabs.map(({ key, label, icon: Icon }) => (
          <button key={key} onClick={() => setActiveTab(key)}
            className={`flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm whitespace-nowrap transition-colors flex-shrink-0 ${
              activeTab === key ? 'bg-card text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground'
            }`}>
            <Icon className="w-4 h-4" />
            {label}
          </button>
        ))}
      </div>

      {/* ── Users tab ─────────────────────────────────────────────── */}
      {activeTab === 'users' && (
        <div className="space-y-3">
          <div className="flex items-center justify-between">
            <p className="text-xs text-muted-foreground">{t('admin.system.userCount', { count: users.length })}</p>
            {canManageUsers && (
            <button onClick={() => setCreateOpen(true)}
              className="flex items-center gap-1.5 px-4 py-2 bg-[oklch(0.488_0.243_264.376)] text-white rounded-xl text-sm hover:opacity-90 transition-opacity">
              <Plus className="w-4 h-4" /> {t('admin.system.createUser')}
            </button>
            )}
          </div>

          {/* Desktop table */}
          <div className="hidden sm:block bg-card border border-border rounded-2xl overflow-hidden">
            <table className="w-full">
              <thead>
                <tr className="border-b border-border">
                  {[t('admin.system.colName'), t('admin.system.colEmail'), t('admin.system.colRoles'), t('admin.system.colStatus'), t('admin.system.colLastLogin'), t('admin.system.colActions')].map(h => (
                    <th key={h} className="px-4 py-3 text-left text-xs text-muted-foreground font-medium">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {users.map(u => (
                  <tr key={u.id} className="border-b border-border last:border-b-0 hover:bg-muted/30">
                    <td className="px-4 py-3 text-sm text-foreground font-medium">{u.name}</td>
                    <td className="px-4 py-3 text-xs text-muted-foreground">{u.email}</td>
                    <td className="px-4 py-3">
                      <span className={`text-xs px-1.5 py-0.5 rounded-md ${roleColor(u.roleCode)}`}>{roleName(u.roleCode)}</span>
                    </td>
                    <td className="px-4 py-3">
                      <span className={`text-xs ${u.status === 'active' ? 'text-green-600 dark:text-green-400' : 'text-muted-foreground'}`}>
                        {u.status === 'active' ? t('admin.system.active') : t('admin.system.inactive')}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-xs text-muted-foreground">{u.lastLogin}</td>
                    <td className="px-4 py-3">
                      {canManageUsers && (
                      <button onClick={() => setEditTarget(u)} className={ACTION_BTN_BLUE}>
                        <Pencil className="w-3 h-3" /> {t('admin.system.edit')}
                      </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* Mobile cards */}
          <div className="sm:hidden space-y-2">
            {users.map(u => (
              <div key={u.id} className="bg-card border border-border rounded-2xl p-4">
                <div className="flex items-start justify-between gap-3">
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 mb-2">
                      <span className="text-sm text-foreground font-medium">{u.name}</span>
                      <span className={`text-xs ${u.status === 'active' ? 'text-green-600 dark:text-green-400' : 'text-muted-foreground'}`}>
                        {u.status === 'active' ? t('admin.system.active') : t('admin.system.inactive')}
                      </span>
                    </div>
                    <div className="text-xs text-muted-foreground mb-2">{u.email}</div>
                    <span className={`text-xs px-1.5 py-0.5 rounded-md ${roleColor(u.roleCode)}`}>{roleName(u.roleCode)}</span>
                  </div>
                  {canManageUsers && (
                  <button onClick={() => setEditTarget(u)} className={`${ACTION_BTN_BLUE} shrink-0`}>
                    <Pencil className="w-3 h-3" /> {t('admin.system.edit')}
                  </button>
                  )}
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* ── Rules tab ─────────────────────────────────────────────── */}
      {activeTab === 'rules' && (
        <div className="space-y-4">
          {!canManageRules && (
            <div className="bg-blue-50 dark:bg-blue-900/20 border border-blue-200 dark:border-blue-700 rounded-xl p-4 flex gap-3">
              <Lock className="w-4 h-4 text-blue-500 shrink-0 mt-0.5" />
              <div>
                <p className="text-sm text-blue-800 dark:text-blue-300 font-medium">{t('admin.system.adminOnlyRules')}</p>
                <p className="text-xs text-blue-700/70 dark:text-blue-400/70 mt-0.5">
                  {t('admin.system.adminOnlyRulesDesc')}
                </p>
              </div>
            </div>
          )}
          {rules.map(section => (
            <div key={section.id} className="bg-card border border-border rounded-2xl overflow-hidden">
              <div className="px-4 py-3 border-b border-border flex items-center justify-between">
                <h3 className="text-sm text-foreground">{t(section.title)}</h3>
                {canManageRules ? (
                  <button onClick={() => setEditingRule(section)} className={ACTION_BTN_BLUE}>
                    <Pencil className="w-3 h-3" /> {t('admin.system.edit')}
                  </button>
                ) : (
                  <Lock className="w-3.5 h-3.5 text-muted-foreground/50" />
                )}
              </div>
              <div className="divide-y divide-border">
                {section.items.map(item => (
                  <div key={item.field} className="flex items-center justify-between px-4 py-3">
                    <span className="text-xs text-muted-foreground">{t(item.label)}</span>
                    <span className="text-xs text-foreground font-medium tabular-nums">
                      {item.value} {item.unit ? t(item.unit) : ''}
                    </span>
                  </div>
                ))}
              </div>
            </div>
          ))}
        </div>
      )}

      {/* ── Roles tab ─────────────────────────────────────────────── */}
      {activeTab === 'roles' && (
        <RoleManagement roles={roles} canManage={canManageRoles} canManageTemplates={canManageTemplates} onChanged={loadRoles} />
      )}

      {/* ── Import/Export tab ─────────────────────────────────────── */}
      {activeTab === 'import' && (
        <div className="space-y-4">
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div className="bg-card border border-border rounded-2xl p-5">
              <Upload className="w-8 h-8 text-[oklch(0.488_0.243_264.376)] mb-3" />
              <h3 className="text-foreground mb-1">{t('admin.system.importHistoricalRecords')}</h3>
              <p className="text-xs text-muted-foreground mb-4">{t('admin.system.importHistoricalDesc')}</p>
              <div className="space-y-2">
                <button onClick={() => handleTemplateDownload('serviceRecordsHistorical')}
                  className="w-full py-2.5 border border-border rounded-xl text-sm text-muted-foreground hover:bg-accent text-center">
                  {t('admin.system.downloadTemplate')}
                </button>
                <input ref={importFileRef} type="file" accept=".csv,.xlsx" className="hidden"
                  onChange={e => { const f = e.target.files?.[0]; if (f) handleImportUpload(f, importType); e.target.value = ''; }} />
                <button onClick={() => { setImportType('serviceRecordsHistorical'); importFileRef.current?.click(); }}
                  disabled={saving}
                  className="w-full py-2.5 bg-[oklch(0.488_0.243_264.376)] text-white rounded-xl text-sm hover:opacity-90 disabled:opacity-40">
                  {t('admin.system.uploadDataFile')}
                </button>
              </div>
            </div>

            <div className="bg-card border border-border rounded-2xl p-5">
              <Upload className="w-8 h-8 text-indigo-500 mb-3" />
              <h3 className="text-foreground mb-1">{t('admin.system.importStudentInfo')}</h3>
              <p className="text-xs text-muted-foreground mb-4">{t('admin.system.importStudentDesc')}</p>
              <div className="space-y-2">
                <button onClick={() => handleTemplateDownload('students')}
                  className="w-full py-2.5 border border-border rounded-xl text-sm text-muted-foreground hover:bg-accent text-center">
                  {t('admin.system.downloadStudentTemplate')}
                </button>
                <input ref={studentImportFileRef} type="file" accept=".csv,.xlsx" className="hidden"
                  onChange={e => { const f = e.target.files?.[0]; if (f) handleImportUpload(f, 'students'); e.target.value = ''; }} />
                <button onClick={() => studentImportFileRef.current?.click()}
                  disabled={saving}
                  className="w-full py-2.5 bg-[oklch(0.488_0.243_264.376)] text-white rounded-xl text-sm hover:opacity-90 disabled:opacity-40">
                  {t('admin.system.uploadStudentFile')}
                </button>
              </div>
            </div>

            <div className="bg-card border border-border rounded-2xl p-5 sm:col-span-2">
              <Download className="w-8 h-8 text-green-500 mb-3" />
              <h3 className="text-foreground mb-1">{t('admin.system.exportData')}</h3>
              <p className="text-xs text-muted-foreground mb-4">{t('admin.system.exportDesc')}</p>
              <div className="grid grid-cols-2 sm:grid-cols-5 gap-2">
                {([
                  { label: t('admin.system.exportStudents'), type: 'students' },
                  { label: t('admin.system.exportAllRecords'), type: 'serviceRecords' },
                  { label: t('admin.system.exportTransactions'), type: 'serviceHours' },
                  { label: t('admin.system.exportReports'), type: 'participation' },
                  { label: t('admin.system.exportAuditLogs'), type: 'completion' },
                ] as const).map(item => (
                  <button key={item.type} onClick={() => handleExport(item.type)}
                    className="py-2 border border-border rounded-xl text-sm text-foreground hover:bg-accent flex items-center justify-between px-4">
                    {item.label}
                    <ChevronRight className="w-4 h-4 text-muted-foreground shrink-0" />
                  </button>
                ))}
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ── Logs tab ──────────────────────────────────────────────── */}
      {activeTab === 'logs' && (
        <div className="bg-card border border-border rounded-2xl overflow-hidden">
          <div className="px-4 py-3 border-b border-border">
            <h3 className="text-foreground">{t('admin.system.auditLogs')}</h3>
            <p className="text-xs text-muted-foreground">{t('admin.system.logsDesc', { count: logs.length })}</p>
          </div>
          <div className="divide-y divide-border">
            {logs.map(log => (
              <div key={log.id} className="px-4 py-3 flex items-start gap-3">
                <div className={`w-2 h-2 rounded-full mt-1.5 shrink-0 ${
                  log.category === 'review'  ? 'bg-blue-500'   :
                  log.category === 'project' ? 'bg-green-500'  :
                  log.category === 'import'  ? 'bg-purple-500' :
                  log.category === 'student' ? 'bg-orange-400' : 'bg-gray-400'
                }`} />
                <div className="flex-1 min-w-0">
                  <div className="text-sm text-foreground">{log.action}</div>
                  <div className="text-xs text-muted-foreground">{log.target}</div>
                  {log.memo && (
                    <div className="mt-1 text-xs text-muted-foreground/70 bg-muted/50 rounded-md px-2 py-1 italic">
                      {t('admin.system.memoLabel')}: {log.memo}
                    </div>
                  )}
                </div>
                <div className="text-right shrink-0">
                  <div className="text-xs text-foreground">{log.operator}</div>
                  <div className="text-xs text-muted-foreground">{log.time}</div>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* ── Partner Organizations tab ────────────────────────────────── */}
      {activeTab === 'organizations' && <OrganizationManagement />}

      {/* ── Settings tab ──────────────────────────────────────────── */}
      {activeTab === 'settings' && (
        <div className="space-y-4">
          <div className="bg-card border border-border rounded-2xl overflow-hidden">
            <div className="px-4 py-3 border-b border-border">
              <h3 className="text-foreground">{t('admin.system.featureToggles')}</h3>
              <p className="text-xs text-muted-foreground">{t('admin.system.featureTogglesDesc')}</p>
            </div>
            {[
              { key: 'pov' as const, label: t('admin.system.featPov'), desc: t('admin.system.featPovDesc') },
              { key: 'redemption' as const, label: t('admin.system.featRedemption'), desc: t('admin.system.featRedemptionDesc') },
              { key: 'borrowing' as const, label: t('admin.system.featBorrowing'), desc: t('admin.system.featBorrowingDesc') },
              { key: 'appreciation' as const, label: t('admin.system.featAppreciation'), desc: t('admin.system.featAppreciationDesc') },
            ].map(item => (
              <div key={item.key} className="flex items-center gap-4 px-4 py-4 border-b border-border last:border-b-0">
                <div className="flex-1 min-w-0">
                  <div className="text-sm text-foreground">{item.label}</div>
                  <div className="text-xs text-muted-foreground">{item.desc}</div>
                </div>
                <button onClick={() => toggleFeature(item.key)}
                  className={`relative w-10 h-6 rounded-full transition-colors duration-200 ${features[item.key] ? 'bg-[oklch(0.488_0.243_264.376)]' : 'bg-muted'}`}>
                  <span className={`absolute top-1 left-1 w-4 h-4 bg-white rounded-full shadow transition-transform duration-200 ${features[item.key] ? 'translate-x-4' : 'translate-x-0'}`} />
                </button>
              </div>
            ))}
          </div>

          <div className="bg-card border border-border rounded-2xl overflow-hidden">
            <div className="px-4 py-3 border-b border-border">
              <h3 className="text-foreground">{t('admin.system.systemInfo')}</h3>
            </div>
            {[
              { label: t('admin.system.sysName'), value: `TimeBank · ${schoolName}` },
              { label: t('admin.system.sysVersion'), value: 'v2.0.0-beta (Phase 2)' },
              { label: t('admin.system.adminContact'), value: 'Wei Chen / Mei Zhao' },
              { label: t('admin.system.officeLocation'), value: 'H300 / H504' },
              { label: t('admin.system.apiVersion'), value: '/api/v1' },
            ].map(item => (
              <div key={item.label} className="flex items-center justify-between px-4 py-3 border-b border-border last:border-b-0">
                <span className="text-xs text-muted-foreground">{item.label}</span>
                <span className="text-xs text-foreground">{item.value}</span>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Create modal */}
      {createOpen && (
        <UserModal
          mode="create"
          initial={emptyForm()}
          roles={roles}
          onClose={() => setCreateOpen(false)}
          onSave={handleCreate}
        />
      )}

      {/* Edit user modal */}
      {editTarget && (
        <UserModal
          mode="edit"
          initial={userToForm(editTarget)}
          roles={roles}
          onClose={() => setEditTarget(null)}
          onSave={handleEdit}
          onDelete={canManageUsers ? handleDelete : undefined}
        />
      )}

      {/* Edit rule modal */}
      {editingRule && (
        <RuleModal
          section={editingRule}
          onClose={() => setEditingRule(null)}
          onSave={handleRuleSave}
        />
      )}
    </div>
  );
}
