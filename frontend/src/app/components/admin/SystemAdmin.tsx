import { useState, useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { Users, Shield, FileText, Settings, Download, Upload, ChevronRight, AlertTriangle, Plus, X, Eye, EyeOff, Pencil, Trash2, Lock, Building2, KeyRound, Check, Tags } from 'lucide-react';
import { toast } from 'sonner';
import { useAuth, usePermissions } from '../../App';
import { api, type ImportJob } from '../../api/client';
import { ApiError, mapApiError, newIdempotencyKey } from '../../api/http';
import { OrganizationManagement } from './OrganizationManagement';
import { RoleManagement } from './RoleManagement';
import { ServiceCategoryManagement } from './ServiceCategoryManagement';
import { GradePicker } from '../GradePicker';
import { useDictionaries } from '../../api/useDictionaries';
import { SearchPicker, type SearchPickerOption } from '../SearchPicker';
import { formatGradeClass, minutesToHours } from '../../lib/format';
import { localDateTimeToISO } from '../../lib/datetime';
import { STATUS_COLOR_CLASSES } from '../../lib/statusLabels';
import type { RoleSummary, AuditLogEntry, SchoolInfo, RuleVersion, RuleDraftFields, RuleValidationResult } from '../../api/types';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../ui/select';
import { usePagedApi } from '../../api/useApi';
import { PageControls } from '../PageControls';
import { ListStateBanner } from '../ListStateBanner';

/**
 * Row-action button shape shared by every list/table in the admin portal
 * (Users, Roles, Rule Settings) — icon + text, bordered, colored per
 * action but never filled, matching Student Management's row-action shape.
 */
const ACTION_BTN_BLUE = 'inline-flex items-center gap-1 text-xs px-2 py-1 rounded-lg border border-blue-200 dark:border-blue-800 text-blue-600 dark:text-blue-400 hover:bg-blue-50 dark:hover:bg-blue-900/20 transition-colors';

// ── Rule versions (contract §4.12) — real, versioned, immutable-once-published ──

/** `hourBased` fields are "required duration"/"threshold" fields — Design/chinese/01-TimeBankDesign.md's numbers are all specified in hours, so both the read-only display and the create/edit input work in hours (with the stored/submitted integer-minutes value shown as a conversion hint), unlike the rounding/conversion group which is genuinely minute-denominated. */
type RuleFieldMeta = { key: keyof RuleDraftFields; labelKey: string; unitKey: string; hourBased?: boolean };

const RULE_FIELD_GROUPS: { titleKey: string; fields: RuleFieldMeta[] }[] = [
  {
    titleKey: 'admin.system.rules.groupConversion',
    fields: [
      { key: 'roundingMinutes', labelKey: 'admin.system.rules.fieldRounding', unitKey: 'admin.system.rules.unitMinutes' },
      { key: 'minutesPerCoinUnit', labelKey: 'admin.system.rules.fieldMinutesPerCoinUnit', unitKey: 'admin.system.rules.unitMinutes' },
      { key: 'centiCoinsPerUnit', labelKey: 'admin.system.rules.fieldCentiCoinsPerUnit', unitKey: 'admin.system.rules.unitCentiCoins' },
    ],
  },
  {
    // Design/chinese/01-TimeBankDesign.md §6: grades 6-8 (middle school) and 9-11 (high school) have separate requirements — exactly two bands.
    titleKey: 'admin.system.rules.groupRequirementsMiddle',
    fields: [
      { key: 'middleAnnualRequiredMinutes', labelKey: 'admin.system.rules.fieldAnnualRequired', unitKey: 'admin.system.rules.unitMinutes', hourBased: true },
      { key: 'middleStageRequiredMinutes', labelKey: 'admin.system.rules.fieldStageRequired', unitKey: 'admin.system.rules.unitMinutes', hourBased: true },
    ],
  },
  {
    titleKey: 'admin.system.rules.groupRequirementsHigh',
    fields: [
      { key: 'highAnnualRequiredMinutes', labelKey: 'admin.system.rules.fieldAnnualRequired', unitKey: 'admin.system.rules.unitMinutes', hourBased: true },
      { key: 'highStageRequiredMinutes', labelKey: 'admin.system.rules.fieldStageRequired', unitKey: 'admin.system.rules.unitMinutes', hourBased: true },
    ],
  },
  {
    titleKey: 'admin.system.rules.groupHonour',
    fields: [
      { key: 'honourBronzeMinutes', labelKey: 'admin.system.rules.fieldHonourBronze', unitKey: 'admin.system.rules.unitMinutes', hourBased: true },
      { key: 'honourSilverMinutes', labelKey: 'admin.system.rules.fieldHonourSilver', unitKey: 'admin.system.rules.unitMinutes', hourBased: true },
      { key: 'honourGoldMinutes', labelKey: 'admin.system.rules.fieldHonourGold', unitKey: 'admin.system.rules.unitMinutes', hourBased: true },
      { key: 'honourExcellentMinutes', labelKey: 'admin.system.rules.fieldHonourExcellent', unitKey: 'admin.system.rules.unitMinutes', hourBased: true },
    ],
  },
];

const DEFAULT_DRAFT_FIELDS: RuleDraftFields = {
  roundingMinutes: 30, minutesPerCoinUnit: 60, centiCoinsPerUnit: 100,
  middleAnnualRequiredMinutes: 1500, middleStageRequiredMinutes: 4500,
  highAnnualRequiredMinutes: 4500, highStageRequiredMinutes: 18000,
  honourBronzeMinutes: 1500, honourSilverMinutes: 4500, honourGoldMinutes: 9000, honourExcellentMinutes: 18000,
};

/** Hours value for a minutes-based input — rounded to 2dp to avoid float noise from non-exact-hour minute values. */
function minutesToHourInputValue(minutes: number): number {
  return Math.round((minutes / 60) * 100) / 100;
}

/**
 * Hands a fetched file to the browser as a download. Shared by the import
 * template and the activation slip a password reset returns — the slip's bytes
 * are a live credential, so they are revoked from the object URL immediately
 * rather than left addressable for the life of the page.
 */
function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url; a.download = filename;
  document.body.appendChild(a); a.click();
  document.body.removeChild(a); URL.revokeObjectURL(url);
}

function ruleStatusBadgeClass(status: RuleVersion['status']): string {
  if (status === 'published') return 'bg-green-100 text-green-700 dark:bg-green-500/20 dark:text-green-300';
  if (status === 'draft') return 'bg-blue-100 text-blue-700 dark:bg-blue-500/20 dark:text-blue-300';
  return 'bg-muted text-muted-foreground';
}

interface RuleFormModalProps {
  mode: 'create' | 'edit';
  initial: RuleVersion | null;
  onClose: () => void;
  onSaved: (rule: RuleVersion) => void;
}

function RuleFormModal({ mode, initial, onClose, onSaved }: RuleFormModalProps) {
  const { t } = useTranslation();
  const [fields, setFields] = useState<RuleDraftFields>(initial ? {
    roundingMinutes: initial.roundingMinutes, minutesPerCoinUnit: initial.minutesPerCoinUnit, centiCoinsPerUnit: initial.centiCoinsPerUnit,
    middleAnnualRequiredMinutes: initial.middleAnnualRequiredMinutes, middleStageRequiredMinutes: initial.middleStageRequiredMinutes,
    highAnnualRequiredMinutes: initial.highAnnualRequiredMinutes, highStageRequiredMinutes: initial.highStageRequiredMinutes,
    honourBronzeMinutes: initial.honourBronzeMinutes, honourSilverMinutes: initial.honourSilverMinutes,
    honourGoldMinutes: initial.honourGoldMinutes, honourExcellentMinutes: initial.honourExcellentMinutes,
  } : DEFAULT_DRAFT_FIELDS);
  const [saving, setSaving] = useState(false);

  const setField = (key: keyof RuleDraftFields, raw: string) => {
    const n = Number(raw);
    setFields(prev => ({ ...prev, [key]: Number.isFinite(n) ? n : prev[key] }));
  };

  /** Hour-based fields are typed in hours; the stored/submitted value is always the converted integer minutes. */
  const setHourField = (key: keyof RuleDraftFields, rawHours: string) => {
    const hours = Number(rawHours);
    setFields(prev => ({ ...prev, [key]: Number.isFinite(hours) ? Math.round(hours * 60) : prev[key] }));
  };

  const handleSave = async () => {
    for (const group of RULE_FIELD_GROUPS) {
      for (const f of group.fields) {
        if (!(fields[f.key] > 0)) {
          toast.error(t('admin.system.rules.fieldMustBePositive', { label: t(f.labelKey) }));
          return;
        }
      }
    }
    setSaving(true);
    try {
      const saved = mode === 'create'
        ? await api.rules.createDraft(fields)
        : await api.rules.updateDraft(initial!.id, fields, initial!.version);
      toast.success(t(mode === 'create' ? 'admin.system.rules.ruleCreated' : 'admin.system.rules.ruleUpdatedToast'));
      onSaved(saved);
    } catch (err) {
      if (err instanceof ApiError) toast.error(mapApiError(err).message);
      else toast.error(String(err));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4">
      <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" onClick={onClose} />
      <div className="relative bg-card w-full sm:max-w-lg sm:rounded-2xl rounded-t-2xl shadow-xl flex flex-col max-h-[92vh]">

        <div className="flex items-center gap-3 px-5 py-4 border-b border-border shrink-0">
          <Shield className="w-5 h-5 text-[oklch(0.488_0.243_264.376)] shrink-0" />
          <h3 className="flex-1 text-foreground text-sm">{t(mode === 'create' ? 'admin.system.rules.createDraft' : 'admin.system.rules.editDraft')}</h3>
          <button onClick={onClose} className="p-1.5 rounded-lg hover:bg-accent text-muted-foreground">
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className="mx-5 mt-4 bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-700 rounded-xl p-3 flex gap-2.5">
          <AlertTriangle className="w-4 h-4 text-amber-500 shrink-0 mt-0.5" />
          <p className="text-xs text-amber-800 dark:text-amber-300 leading-relaxed">{t('admin.system.rules.draftWarning')}</p>
        </div>

        <div className="overflow-y-auto px-5 py-4 space-y-5 text-sm">
          {RULE_FIELD_GROUPS.map(group => (
            <div key={group.titleKey}>
              <h4 className="text-xs text-muted-foreground uppercase tracking-wide mb-2">{t(group.titleKey)}</h4>
              <div className="space-y-3">
                {group.fields.map(f => (
                  <div key={f.key}>
                    <label className="block text-xs text-muted-foreground mb-1.5">{t(f.labelKey)}</label>
                    <div className="flex items-center gap-2">
                      <input
                        type="number"
                        min={f.hourBased ? '0.5' : '1'}
                        step={f.hourBased ? '0.5' : '1'}
                        value={f.hourBased ? minutesToHourInputValue(fields[f.key]) : fields[f.key]}
                        onChange={e => f.hourBased ? setHourField(f.key, e.target.value) : setField(f.key, e.target.value)}
                        className="flex-1 px-4 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-[oklch(0.488_0.243_264.376)]/40"
                      />
                      <span className="text-xs text-muted-foreground whitespace-nowrap shrink-0">{t(f.hourBased ? 'admin.system.rules.unitHours' : f.unitKey)}</span>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          ))}
        </div>

        <div className="px-5 py-4 border-t border-border shrink-0 flex gap-3">
          <button type="button" onClick={onClose}
            className="flex-1 py-2.5 rounded-xl border border-border text-sm text-foreground hover:bg-accent">
            {t('common.cancel')}
          </button>
          <button type="button" onClick={handleSave} disabled={saving}
            className="flex-1 py-2.5 rounded-xl bg-[oklch(0.488_0.243_264.376)] text-white text-sm hover:opacity-90 transition-opacity disabled:opacity-40 disabled:cursor-not-allowed">
            {t('admin.system.rules.saveDraft')}
          </button>
        </div>
      </div>
    </div>
  );
}

interface PublishRuleModalProps {
  rule: RuleVersion;
  onClose: () => void;
  onPublished: (rule: RuleVersion) => void;
}

function PublishRuleModal({ rule, onClose, onPublished }: PublishRuleModalProps) {
  const { t } = useTranslation();
  const [effectiveFrom, setEffectiveFrom] = useState('');
  const [validation, setValidation] = useState<RuleValidationResult | null>(null);
  const [checking, setChecking] = useState(true);
  const [publishing, setPublishing] = useState(false);

  useEffect(() => {
    api.rules.validate(rule.id).then(setValidation).catch(() => setValidation(null)).finally(() => setChecking(false));
  }, [rule.id]);

  const handlePublish = async () => {
    if (!effectiveFrom) {
      toast.error(t('admin.system.rules.effectiveFromRequired'));
      return;
    }
    setPublishing(true);
    try {
      const published = await api.rules.publish(rule.id, localDateTimeToISO(effectiveFrom), rule.version);
      toast.success(t('admin.system.rules.rulePublished'));
      onPublished(published);
    } catch (err) {
      if (err instanceof ApiError) toast.error(mapApiError(err).message);
      else toast.error(String(err));
    } finally {
      setPublishing(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4">
      <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" onClick={onClose} />
      <div className="relative bg-card w-full sm:max-w-md sm:rounded-2xl rounded-t-2xl shadow-xl flex flex-col max-h-[92vh]">
        <div className="flex items-center gap-3 px-5 py-4 border-b border-border shrink-0">
          <Shield className="w-5 h-5 text-[oklch(0.488_0.243_264.376)] shrink-0" />
          <h3 className="flex-1 text-foreground text-sm">{t('admin.system.rules.publishTitle')}</h3>
          <button onClick={onClose} className="p-1.5 rounded-lg hover:bg-accent text-muted-foreground">
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className="overflow-y-auto px-5 py-4 space-y-4 text-sm">
          <div className="bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-700 rounded-xl p-3 flex gap-2.5">
            <AlertTriangle className="w-4 h-4 text-amber-500 shrink-0 mt-0.5" />
            <p className="text-xs text-amber-800 dark:text-amber-300 leading-relaxed">{t('admin.system.rules.publishWarning')}</p>
          </div>

          {checking ? (
            <p className="text-xs text-muted-foreground">{t('admin.system.rules.checkingValidity')}</p>
          ) : validation && !validation.valid ? (
            <div className="bg-destructive/10 border border-destructive/30 rounded-xl p-3 space-y-1">
              <p className="text-xs text-destructive font-medium">{t('admin.system.rules.validateInvalid')}</p>
              <ul className="text-xs text-destructive/90 list-disc list-inside space-y-0.5">
                {validation.issues.map((issue, i) => <li key={i}>{issue}</li>)}
              </ul>
            </div>
          ) : validation ? (
            <p className="text-xs text-green-600 dark:text-green-400">{t('admin.system.rules.validateValid')}</p>
          ) : null}

          <div>
            <label className="block text-xs text-muted-foreground mb-1.5">{t('admin.system.rules.effectiveFromLabel')}</label>
            <input type="date" value={effectiveFrom} onChange={e => setEffectiveFrom(e.target.value)}
              className="w-full px-4 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-[oklch(0.488_0.243_264.376)]/40" />
          </div>
        </div>

        <div className="px-5 py-4 border-t border-border shrink-0 flex gap-3">
          <button type="button" onClick={onClose}
            className="flex-1 py-2.5 rounded-xl border border-border text-sm text-foreground hover:bg-accent">
            {t('common.cancel')}
          </button>
          <button type="button" onClick={handlePublish} disabled={publishing || checking || (validation ? !validation.valid : false)}
            className="flex-1 py-2.5 rounded-xl bg-[oklch(0.488_0.243_264.376)] text-white text-sm hover:opacity-90 transition-opacity disabled:opacity-40 disabled:cursor-not-allowed">
            {t('admin.system.rules.confirmPublish')}
          </button>
        </div>
      </div>
    </div>
  );
}

interface SchoolInfoEditModalProps {
  initial: SchoolInfo;
  onClose: () => void;
  onSaved: (info: SchoolInfo) => void;
}

function SchoolInfoEditModal({ initial, onClose, onSaved }: SchoolInfoEditModalProps) {
  const { t } = useTranslation();
  const [form, setForm] = useState({
    name: initial.name ?? '', fullName: initial.fullName ?? '',
    studentDevCenter: initial.studentDevCenter ?? '', contact: initial.contact ?? '',
  });
  const [saving, setSaving] = useState(false);

  const handleSave = async () => {
    if (!form.name.trim()) {
      toast.error(t('admin.system.schoolNameRequired'));
      return;
    }
    setSaving(true);
    try {
      const saved = await api.school.updateInfo(form);
      toast.success(t('admin.system.schoolInfoUpdated'));
      onSaved(saved);
    } catch (err) {
      if (err instanceof ApiError) toast.error(mapApiError(err).message);
      else toast.error(t('common.unknownError'));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4">
      <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" onClick={onClose} />
      <div className="relative bg-card w-full sm:max-w-md sm:rounded-2xl rounded-t-2xl shadow-xl flex flex-col max-h-[92vh]">
        <div className="flex items-center gap-3 px-5 py-4 border-b border-border shrink-0">
          <Building2 className="w-5 h-5 text-[oklch(0.488_0.243_264.376)] shrink-0" />
          <h3 className="flex-1 text-foreground text-sm">{t('admin.system.editSchoolInfo')}</h3>
          <button onClick={onClose} className="p-1.5 rounded-lg hover:bg-accent text-muted-foreground">
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className="overflow-y-auto px-5 py-4 space-y-4 text-sm">
          <div>
            <label className="block text-xs text-muted-foreground mb-1.5">{t('admin.system.schoolName')}</label>
            <input type="text" value={form.name} maxLength={255} onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
              className="w-full px-4 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-[oklch(0.488_0.243_264.376)]/40" />
          </div>
          <div>
            <label className="block text-xs text-muted-foreground mb-1.5">{t('admin.system.schoolFullName')}</label>
            <input type="text" value={form.fullName} maxLength={255} onChange={e => setForm(f => ({ ...f, fullName: e.target.value }))}
              className="w-full px-4 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-[oklch(0.488_0.243_264.376)]/40" />
          </div>
          <div>
            <label className="block text-xs text-muted-foreground mb-1.5">{t('admin.system.officeLocation')}</label>
            <input type="text" value={form.studentDevCenter} maxLength={255} onChange={e => setForm(f => ({ ...f, studentDevCenter: e.target.value }))}
              className="w-full px-4 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-[oklch(0.488_0.243_264.376)]/40" />
          </div>
          <div>
            <label className="block text-xs text-muted-foreground mb-1.5">{t('admin.system.adminContact')}</label>
            <input type="text" value={form.contact} maxLength={255} onChange={e => setForm(f => ({ ...f, contact: e.target.value }))}
              className="w-full px-4 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-[oklch(0.488_0.243_264.376)]/40" />
          </div>
        </div>

        <div className="px-5 py-4 border-t border-border shrink-0 flex gap-3">
          <button type="button" onClick={onClose}
            className="flex-1 py-2.5 rounded-xl border border-border text-sm text-foreground hover:bg-accent">
            {t('common.cancel')}
          </button>
          <button type="button" onClick={handleSave} disabled={saving}
            className="flex-1 py-2.5 rounded-xl bg-[oklch(0.488_0.243_264.376)] text-white text-sm hover:opacity-90 transition-opacity disabled:opacity-40 disabled:cursor-not-allowed">
            {t('common.save')}
          </button>
        </div>
      </div>
    </div>
  );
}

type Tab = 'users' | 'roles' | 'rules' | 'import' | 'logs' | 'categories' | 'organizations' | 'settings';
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

/** `AuditLogEntry.action` is a real backend action code like `eventProposal.reviewDecision` — split into words for display rather than translated, since new action codes are added over time and a per-key i18n table would drift out of sync with the backend. */
function humanizeAuditAction(action: string): string {
  return action
    .split('.')
    .map(part => part.replace(/([a-z0-9])([A-Z])/g, '$1 $2'))
    .join(' — ')
    .replace(/^./, c => c.toUpperCase());
}

/** Dot color per resource type, same visual language as the old client-only log's `category` field. */
function auditLogDotColor(resourceType: string): string {
  if (resourceType === 'eventProposal' || resourceType === 'serviceRecord' || resourceType === 'case') return 'bg-blue-500';
  if (resourceType === 'project') return 'bg-green-500';
  if (resourceType === 'import' || resourceType === 'export') return 'bg-purple-500';
  if (resourceType === 'student') return 'bg-orange-400';
  return 'bg-gray-400';
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
      // 8, not 6: matches the backend's @Size(min = 8) on CreateUserRequest.initialPassword
      // (issue 086 — the UI used to instruct 6 and the server then rejected it).
      if (form.password.length < 8) { toast.error(t('admin.system.errorPasswordTooShort')); return; }
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
              placeholder={t('admin.system.namePlaceholder')} maxLength={100}
              className="w-full px-4 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-[oklch(0.488_0.243_264.376)]/40" />
          </div>

          {/* Email */}
          <div>
            <label className="block text-xs text-muted-foreground mb-1.5">{t('admin.system.emailLabel')} *</label>
            <input type="email" value={form.email} onChange={e => set({ email: e.target.value })}
              placeholder={t('admin.system.emailPlaceholder')} maxLength={255}
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
                placeholder={t('admin.system.staffIdPlaceholder')} maxLength={64}
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

          {/* Reset password (edit). The admin reset endpoint takes no body and
              returns no password: it clears this account's password, issues a
              single-use activation code and streams back a printable slip
              (APIContract §4.15, `POST /users/{id}/reset-password`; plan 10
              §3.7). It offered "new password"/"confirm" boxes once, whose value
              was validated and then silently thrown away (issue 064), and then
              showed a server-generated temporary password (issue 088) — both
              are gone. The toggle arms the reset and the hint says what
              actually happens, including that the user cannot sign in until
              they redeem the slip. */}
          {mode === 'edit' && (
            <div className="border border-border rounded-xl overflow-hidden">
              <button type="button" onClick={() => set({ showResetPwd: !form.showResetPwd, password: '', confirmPassword: '' })}
                className="w-full flex items-center justify-between px-4 py-3 text-sm text-muted-foreground hover:bg-accent transition-colors">
                <span>{t('admin.system.resetPassword')}</span>
                <span className={`w-4 h-4 rounded border flex items-center justify-center ${form.showResetPwd ? 'bg-[oklch(0.488_0.243_264.376)] border-transparent text-white' : 'border-border'}`}>
                  {form.showResetPwd && <Check className="w-3 h-3" />}
                </span>
              </button>
              {form.showResetPwd && (
                <p className="px-4 pb-4 pt-3 border-t border-border text-xs text-muted-foreground">
                  {t('admin.system.resetPasswordHint')}
                </p>
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
  const canPublishRules = hasPermission('rules.publish');
  const canReadRoles = hasPermission('roles.read');
  const canManageRoles = hasPermission('roles.manage');
  // 07-RoleFinetune.md §2.7: template management is admin/sysadmin-only by
  // role code, not just roles.manage — narrower than ordinary role
  // management (enforced server-side too; this only hides the buttons
  // that would otherwise 403).
  const canManageTemplates = canManageRoles && (user?.roles.includes('admin') || user?.roles.includes('sysadmin')) === true;
  // Deliberately narrower than the admin/sysadmin pattern above: school info
  // is editable by sysadmin only, not admin, even though the two hold
  // identical permission grants post-role-merge — hardcoded role-code check
  // rather than a new permission, matching the backend's identical gate.
  const isSysadmin = user?.roles.includes('sysadmin') ?? false;
  const auditLogs = usePagedApi<AuditLogEntry>(api.auditLogs.list, { sort: '-createdAt', pageSize: 20 });
  const [schoolInfo, setSchoolInfo] = useState<SchoolInfo | null>(null);
  const [systemVersion, setSystemVersion] = useState('');
  const [editingSchoolInfo, setEditingSchoolInfo] = useState(false);

  useEffect(() => {
    api.school.getInfo().then(setSchoolInfo).catch(() => {});
    api.system.version().then(v => setSystemVersion(v.version)).catch(() => {});
  }, []);
  const [activeTab, setActiveTab] = useState<Tab>('users');
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [roles, setRoles] = useState<RoleSummary[]>([]);
  const [createOpen, setCreateOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<AdminUser | null>(null);
  const rulesApi = usePagedApi<RuleVersion>(api.rules.list, { sort: '-createdAt', pageSize: 20 });
  const [ruleFormMode, setRuleFormMode] = useState<'create' | 'edit' | null>(null);
  const [ruleFormTarget, setRuleFormTarget] = useState<RuleVersion | null>(null);
  const [publishingRule, setPublishingRule] = useState<RuleVersion | null>(null);
  const [validatingRuleId, setValidatingRuleId] = useState<string | null>(null);
  const [archiveConfirmId, setArchiveConfirmId] = useState<string | null>(null);
  const [archivingRuleId, setArchivingRuleId] = useState<string | null>(null);
  const features = user?.featureFlags ?? { pov: false, redemption: false, borrowing: false, appreciation: false };
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

  const handleRuleSaved = (_saved: RuleVersion) => {
    setRuleFormMode(null);
    setRuleFormTarget(null);
    rulesApi.refetch();
  };

  const handleRulePublished = (_published: RuleVersion) => {
    setPublishingRule(null);
    rulesApi.refetch();
  };

  const handleValidateRule = async (rule: RuleVersion) => {
    setValidatingRuleId(rule.id);
    try {
      const result: RuleValidationResult = await api.rules.validate(rule.id);
      if (result.valid) {
        toast.success(t('admin.system.rules.validateValid'));
      } else {
        toast.error(`${t('admin.system.rules.validateInvalid')}: ${result.issues.join('; ')}`);
      }
    } catch (err) {
      if (err instanceof ApiError) toast.error(mapApiError(err).message);
      else toast.error(String(err));
    } finally {
      setValidatingRuleId(null);
    }
  };

  const handleArchiveRule = async (rule: RuleVersion) => {
    setArchivingRuleId(rule.id);
    try {
      await api.rules.archive(rule.id);
      toast.success(t('admin.system.rules.ruleArchived'));
      setArchiveConfirmId(null);
      rulesApi.refetch();
    } catch (err) {
      if (err instanceof ApiError) toast.error(mapApiError(err).message);
      else toast.error(String(err));
    } finally {
      setArchivingRuleId(null);
    }
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
      if (form.showResetPwd) {
        // The reset returns the printable activation slip, not a password —
        // there is no longer one to put in a toast (plan 10 §3.7, issue 088).
        const { blob, filename } = await api.users.resetPassword(editTarget.id);
        downloadBlob(blob, filename);
        toast.success(t('admin.system.passwordReset'));
      }
      setUsers(prev => prev.map(u => u.id === editTarget.id
        ? { ...u, name: form.name.trim(), email: form.email.trim(), roleCode: form.roleCode, status: form.status, version: u.version + 1,
            scopeGradeIds: needsGradeScope ? form.scopeGradeIds : [],
            studentId: isStudent ? form.studentId : undefined,
            staffId: needsStaffId ? form.staffId.trim() : undefined }
        : u
      ));
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
      downloadBlob(await api.imports.template(type), `${type}-template.csv`);
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
        const details = errResult.errors.slice(0, 3)
          .map(e => t('admin.system.importRowError', { row: e.rowNumber, message: e.message })).join('; ');
        toast.error(t('admin.system.importInvalidRowsSummary', { count: invalidRows, details }));
      }
      if (validRows > 0) {
        await api.imports.commit(job.id, newIdempotencyKey());
        const committed = await pollImportJob(job.id, ['COMMITTED', 'COMMIT_FAILED']);
        if (committed.status === 'COMMIT_FAILED') {
          toast.error(t('common.unknownError'));
        } else {
          // `validRows` doubles as "rows committed" once status is COMMITTED (JobProgressWriter.succeed) — same field, different meaning post-commit.
          toast.success(t('admin.system.importCommitComplete', { count: committed.validRows ?? 0 }));
        }
      } else {
        await api.imports.cancel(job.id);
        toast.error(t('admin.system.importNoValidRows'));
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
    { key: 'categories', label: t('admin.system.tabCategories'), icon: Tags },
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
                      <span className={`text-xs px-2 py-0.5 rounded-full whitespace-nowrap ${STATUS_COLOR_CLASSES[u.status === 'active' ? 'green' : 'gray']}`}>
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
                      <span className={`text-xs px-2 py-0.5 rounded-full whitespace-nowrap ${STATUS_COLOR_CLASSES[u.status === 'active' ? 'green' : 'gray']}`}>
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
          {canManageRules && (
            <div className="flex justify-end">
              <button onClick={() => { setRuleFormTarget(rulesApi.items[0] ?? null); setRuleFormMode('create'); }}
                className="inline-flex items-center gap-1.5 px-3 py-2 rounded-xl bg-[oklch(0.488_0.243_264.376)] text-white text-xs hover:opacity-90">
                <Plus className="w-3.5 h-3.5" /> {t('admin.system.rules.newDraft')}
              </button>
            </div>
          )}
          {rulesApi.items.map(rule => (
            <div key={rule.id} className="bg-card border border-border rounded-2xl overflow-hidden">
              <div className="px-4 py-3 border-b border-border flex items-center justify-between gap-3">
                <div className="flex items-center gap-2">
                  <span className={`text-xs px-2 py-0.5 rounded-full whitespace-nowrap ${ruleStatusBadgeClass(rule.status)}`}>
                    {t(`admin.system.rules.status.${rule.status}`)}
                  </span>
                  <span className="text-xs text-muted-foreground">
                    {rule.status === 'published' && rule.effectiveFrom
                      ? `${t('admin.system.rules.effectiveFromLabel')} ${new Date(rule.effectiveFrom).toLocaleDateString()}${rule.effectiveTo ? ` – ${new Date(rule.effectiveTo).toLocaleDateString()}` : ''}`
                      : new Date(rule.createdAt).toLocaleDateString()}
                  </span>
                </div>
                <div className="flex items-center gap-2 shrink-0">
                  {rule.status === 'draft' && canManageRules && (
                    <>
                      <button onClick={() => { setRuleFormTarget(rule); setRuleFormMode('edit'); }} className={ACTION_BTN_BLUE}>
                        <Pencil className="w-3 h-3" /> {t('admin.system.edit')}
                      </button>
                      <button onClick={() => handleValidateRule(rule)} disabled={validatingRuleId === rule.id} className={ACTION_BTN_BLUE}>
                        {t('admin.system.rules.validate')}
                      </button>
                    </>
                  )}
                  {rule.status === 'draft' && canPublishRules && (
                    <button onClick={() => setPublishingRule(rule)}
                      className="inline-flex items-center gap-1 text-xs px-2 py-1 rounded-lg border border-green-200 dark:border-green-800 text-green-600 dark:text-green-400 hover:bg-green-50 dark:hover:bg-green-900/20 transition-colors">
                      {t('admin.system.rules.publish')}
                    </button>
                  )}
                  {rule.status === 'published' && canManageRules && archiveConfirmId !== rule.id && (
                    <button onClick={() => setArchiveConfirmId(rule.id)}
                      className="inline-flex items-center gap-1 text-xs px-2 py-1 rounded-lg border border-red-200 dark:border-red-800 text-red-600 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-900/20 transition-colors">
                      <Trash2 className="w-3 h-3" /> {t('admin.system.rules.archive')}
                    </button>
                  )}
                  {archiveConfirmId === rule.id && (
                    <div className="flex items-center gap-2">
                      <button onClick={() => setArchiveConfirmId(null)} className="text-xs px-2 py-1 rounded-lg border border-border text-muted-foreground hover:bg-accent">
                        {t('common.cancel')}
                      </button>
                      <button onClick={() => handleArchiveRule(rule)} disabled={archivingRuleId === rule.id}
                        className="text-xs px-2 py-1 rounded-lg bg-destructive text-destructive-foreground hover:opacity-90">
                        {t('admin.system.rules.confirmArchive')}
                      </button>
                    </div>
                  )}
                  {!canManageRules && !canPublishRules && <Lock className="w-3.5 h-3.5 text-muted-foreground/50" />}
                </div>
              </div>
              <div className="divide-y divide-border">
                {RULE_FIELD_GROUPS.map(group => (
                  <div key={group.titleKey}>
                    <div className="px-4 pt-2.5 pb-1 text-[10px] text-muted-foreground/70 uppercase tracking-wide bg-muted/30">{t(group.titleKey)}</div>
                    {group.fields.map(f => (
                      <div key={f.key} className="flex items-center justify-between px-4 py-3">
                        <span className="text-xs text-muted-foreground">{t(f.labelKey)}</span>
                        <span className="text-xs text-foreground font-medium tabular-nums">
                          {f.hourBased
                            ? `${minutesToHours(rule[f.key])} ${t('admin.system.rules.unitHours')} (${rule[f.key]} ${t('admin.system.rules.unitMinutes')})`
                            : `${rule[f.key]} ${t(f.unitKey)}`}
                        </span>
                      </div>
                    ))}
                  </div>
                ))}
              </div>
            </div>
          ))}
          <ListStateBanner error={rulesApi.error} empty={!rulesApi.loading && rulesApi.items.length === 0} emptyLabel={t('admin.system.rules.noRules')} />
          <PageControls page={rulesApi.page} onPageChange={rulesApi.setPage} pageSize={rulesApi.params.pageSize} onPageSizeChange={rulesApi.setPageSize} />
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
            <p className="text-xs text-muted-foreground">{t('admin.system.logsDesc', { count: auditLogs.page?.totalItems ?? auditLogs.items.length })}</p>
          </div>
          <div className="divide-y divide-border">
            {auditLogs.items.map(log => {
              const operator = log.actorId ? (users.find(u => u.id === log.actorId)?.name ?? log.actorId) : t('admin.system.logSystemActor');
              return (
                <div key={log.id} className="px-4 py-3 flex items-start gap-3">
                  <div className={`w-2 h-2 rounded-full mt-1.5 shrink-0 ${auditLogDotColor(log.resourceType)}`} />
                  <div className="flex-1 min-w-0">
                    <div className="text-sm text-foreground">{humanizeAuditAction(log.action)}</div>
                    <div className="text-xs text-muted-foreground">{log.resourceType}{log.resourceId ? ` · ${log.resourceId}` : ''}</div>
                    {log.reasonCode && (
                      <div className="mt-1 text-xs text-muted-foreground/70 bg-muted/50 rounded-md px-2 py-1 italic">
                        {t('admin.system.memoLabel')}: {log.reasonCode}
                      </div>
                    )}
                  </div>
                  <div className="text-right shrink-0">
                    <div className="text-xs text-foreground">{operator}</div>
                    <div className="text-xs text-muted-foreground">{new Date(log.createdAt).toLocaleString()}</div>
                  </div>
                </div>
              );
            })}
            <ListStateBanner error={auditLogs.error} empty={!auditLogs.loading && auditLogs.items.length === 0} emptyLabel={t('admin.system.logsEmpty')} />
          </div>
          <div className="px-4 py-3 border-t border-border">
            <PageControls page={auditLogs.page} onPageChange={auditLogs.setPage} pageSize={auditLogs.params.pageSize} onPageSizeChange={auditLogs.setPageSize} />
          </div>
        </div>
      )}

      {/* ── Partner Organizations tab ────────────────────────────────── */}
      {/* ── Service Categories tab (issue 078) ────────────────────── */}
      {activeTab === 'categories' && <ServiceCategoryManagement />}

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
              { label: t('admin.system.sysName'), value: 'TimeBank' },
              { label: t('admin.system.sysVersion'), value: systemVersion ? `v${systemVersion}` : '—' },
              { label: t('admin.system.apiVersion'), value: '/api/v1' },
            ].map(item => (
              <div key={item.label} className="flex items-center justify-between px-4 py-3 border-b border-border last:border-b-0">
                <span className="text-xs text-muted-foreground">{item.label}</span>
                <span className="text-xs text-foreground">{item.value}</span>
              </div>
            ))}
          </div>

          <div className="bg-card border border-border rounded-2xl overflow-hidden">
            <div className="px-4 py-3 border-b border-border flex items-center justify-between">
              <div>
                <h3 className="text-foreground">{t('admin.system.schoolInfo')}</h3>
                <p className="text-xs text-muted-foreground">{t('admin.system.schoolInfoDesc')}</p>
              </div>
              {isSysadmin ? (
                <button onClick={() => setEditingSchoolInfo(true)} className={ACTION_BTN_BLUE}>
                  <Pencil className="w-3 h-3" /> {t('admin.system.edit')}
                </button>
              ) : (
                <Lock className="w-3.5 h-3.5 text-muted-foreground/50" />
              )}
            </div>
            {[
              { label: t('admin.system.schoolName'), value: schoolInfo?.name ?? '—' },
              { label: t('admin.system.schoolFullName'), value: schoolInfo?.fullName || '—' },
              { label: t('admin.system.officeLocation'), value: schoolInfo?.studentDevCenter ?? '—' },
              { label: t('admin.system.adminContact'), value: schoolInfo?.contact ?? '—' },
            ].map(item => (
              <div key={item.label} className="flex items-center justify-between px-4 py-3 border-b border-border last:border-b-0">
                <span className="text-xs text-muted-foreground">{item.label}</span>
                <span className="text-xs text-foreground">{item.value}</span>
              </div>
            ))}
            {!isSysadmin && (
              <p className="px-4 py-3 text-xs text-muted-foreground/70">{t('admin.system.schoolInfoSysadminOnly')}</p>
            )}
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

      {/* Rule create/edit draft modal */}
      {ruleFormMode && (
        <RuleFormModal
          mode={ruleFormMode}
          initial={ruleFormTarget}
          onClose={() => { setRuleFormMode(null); setRuleFormTarget(null); }}
          onSaved={handleRuleSaved}
        />
      )}

      {/* Rule publish modal */}
      {publishingRule && (
        <PublishRuleModal
          rule={publishingRule}
          onClose={() => setPublishingRule(null)}
          onPublished={handleRulePublished}
        />
      )}

      {/* School info edit modal */}
      {editingSchoolInfo && schoolInfo && (
        <SchoolInfoEditModal
          initial={schoolInfo}
          onClose={() => setEditingSchoolInfo(false)}
          onSaved={saved => { setSchoolInfo(saved); setEditingSchoolInfo(false); }}
        />
      )}
    </div>
  );
}
