import { useState, useEffect, useMemo } from 'react';
import { useNavigate } from 'react-router';
import { LogOut, ChevronRight, Bell, Shield, HelpCircle, Moon, Sun, Monitor, Mail, FolderOpen, Wallet, Lightbulb, Clock, Lock, Eye, Database, UserCheck, Globe, Pencil } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { toast } from 'sonner';
import { useAuth, useTheme } from '../../App';
import { type StudentProgress, type Account, type SchoolInfo } from '../../api/types';
import { formatCoins, minutesToHours } from '../../lib/format';
import { changeLanguage } from '../../lib/language';
import { api } from '../../api/client';
import { useFieldErrors, handleMutationError, FieldError } from '../../api/useFieldErrors';
import { useEscapeToClose } from '../../lib/useEscapeToClose';

export function ProfilePage() {
  const { t, i18n } = useTranslation();
  const { user, logout, updateUser } = useAuth();
  const { theme, setTheme } = useTheme();
  const navigate = useNavigate();

  const [progress, setProgress] = useState<StudentProgress | null>(null);
  const [loadedAccount, setAccount] = useState<Account | null>(null);
  const [schoolInfo, setSchoolInfo] = useState<SchoolInfo | null>(null);
  const [loadingAccount, setLoadingAccount] = useState(true);

  // `getOrNull`, not `get`: a student with no approved record yet has no
  // account row, which is a zero balance rather than a failure (issue 099).
  // Loading is tracked separately from the value so the guard below can tell
  // "still fetching" from "fetched, and there is nothing".
  useEffect(() => {
    let cancelled = false;
    setLoadingAccount(true);
    api.students.progress(user?.studentId || 'std_001').then(setProgress).catch(() => {});
    api.accounts.getOrNull(user?.studentId || 'std_001')
      .then(a => { if (!cancelled) setAccount(a); })
      .catch(() => { if (!cancelled) setAccount(null); })
      .finally(() => { if (!cancelled) setLoadingAccount(false); });
    api.school.getInfo().then(setSchoolInfo).catch(() => {});
    return () => { cancelled = true; };
  }, [user?.studentId]);

  const [notifEnabled, setNotifEnabled] = useState<Record<string, boolean>>({
    email_audit: true,
    email_proposal: true,
    push_project: true,
    push_account: false,
    push_deadline: true,
    push_system: true,
  });

  const notifSettings = useMemo(() => [
    { key: 'email_audit',    icon: Mail,       label: t('student.profile.notifEmailAudit'),   desc: t('student.profile.notifEmailAuditDesc', { email: user?.email || '' }), enabled: notifEnabled.email_audit },
    { key: 'email_proposal', icon: Lightbulb,  label: t('student.profile.notifEmailProposal'), desc: t('student.profile.notifEmailProposalDesc'),           enabled: notifEnabled.email_proposal },
    { key: 'push_project',   icon: FolderOpen, label: t('student.profile.notifPushProject'),         desc: t('student.profile.notifPushProjectDesc'),                       enabled: notifEnabled.push_project },
    { key: 'push_account',   icon: Wallet,     label: t('student.profile.notifPushAccount'),        desc: t('student.profile.notifPushAccountDesc'),                            enabled: notifEnabled.push_account },
    { key: 'push_deadline',  icon: Clock,      label: t('student.profile.notifPushDeadline'),          desc: t('student.profile.notifPushDeadlineDesc'),                            enabled: notifEnabled.push_deadline },
    { key: 'push_system',    icon: Bell,       label: t('student.profile.notifPushSystem'),             desc: t('student.profile.notifPushSystemDesc'),                              enabled: notifEnabled.push_system },
  ], [t, i18n.language, user?.email, notifEnabled]);

  const toggleNotif = (key: string) =>
    setNotifEnabled(prev => ({ ...prev, [key]: !prev[key] }));

  const [showPrivacyModal, setShowPrivacyModal] = useState(false);

  const [editingBasicInfo, setEditingBasicInfo] = useState(false);
  const [basicForm, setBasicForm] = useState({ displayName: '', email: '' });
  const [savingBasicInfo, setSavingBasicInfo] = useState(false);

  // Escape closes the topmost open dialog (issue 102 §5).
  useEscapeToClose(() => setShowPrivacyModal(false), Boolean(showPrivacyModal));
  const basicInfoErrors = useFieldErrors();

  const startEditBasicInfo = () => {
    setBasicForm({ displayName: user?.displayName ?? '', email: user?.email ?? '' });
    basicInfoErrors.clearAll();
    setEditingBasicInfo(true);
  };
  const cancelEditBasicInfo = () => {
    setEditingBasicInfo(false);
    basicInfoErrors.clearAll();
  };
  const saveBasicInfo = async () => {
    setSavingBasicInfo(true);
    basicInfoErrors.clearAll();
    try {
      const displayName = basicForm.displayName.trim();
      const email = basicForm.email.trim();
      await api.auth.updateProfile({ displayName, email });
      updateUser({ displayName, email });
      toast.success(t('student.profile.basicInfoSaved'));
      setEditingBasicInfo(false);
    } catch (err) {
      handleMutationError(err, { fieldErrors: basicInfoErrors, fallbackMessage: t('common.unknownError') });
    } finally {
      setSavingBasicInfo(false);
    }
  };

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  const themeOptions: { value: 'light' | 'dark' | 'auto'; label: string; icon: React.ReactNode }[] = [
    { value: 'light', label: t('common.theme.light'), icon: <Sun className="w-4 h-4" /> },
    { value: 'dark', label: t('common.theme.dark'), icon: <Moon className="w-4 h-4" /> },
    { value: 'auto', label: t('common.theme.auto'), icon: <Monitor className="w-4 h-4" /> },
  ];

  const languages: { code: string; label: string; nativeLabel: string }[] = [
    { code: 'zh', label: t('student.profile.langZh'), nativeLabel: '中文' },
    { code: 'en', label: t('student.profile.langEn'), nativeLabel: 'English' },
  ];

  const changeLang = (code: string) => {
    changeLanguage(code as 'zh' | 'en');
  };

  if (!progress || loadingAccount) {
    return <div className="p-4 lg:p-6 text-muted-foreground">{t('common.loading')}</div>;
  }

  // No row means no transactions means zero — see AccountPage.
  const account = loadedAccount ?? api.accounts.zeroAccount(user?.studentId ?? '');

  return (
    <div className="p-4 lg:p-6 space-y-5">
      <h2 className="text-foreground">{t('student.profile.title')}</h2>

      {/* Profile card */}
      <div className="bg-card border border-border rounded-2xl p-5 flex items-center gap-4">
        <div className="w-16 h-16 bg-[oklch(0.488_0.243_264.376)] rounded-full flex items-center justify-center shrink-0">
          <span className="text-white text-2xl font-semibold">{user?.displayName?.[0]}</span>
        </div>
        <div className="flex-1 min-w-0">
          <div className="text-foreground font-semibold text-lg">{user?.displayName}</div>
          <div className="text-sm text-muted-foreground">{progress.grade} · {progress.academicYear} {t('common.academicYear')}</div>
          <div className="text-xs text-muted-foreground mt-0.5">{user?.email}</div>
        </div>
      </div>

      {/* Quick stats */}
      <div className="grid grid-cols-3 gap-3">
        <div className="bg-card border border-border rounded-xl p-3 text-center">
          <div className="text-foreground font-semibold text-xl">{minutesToHours(progress.completedMinutes)}</div>
          <div className="text-xs text-muted-foreground">{t('student.profile.completedHours')}</div>
        </div>
        <div className="bg-card border border-border rounded-xl p-3 text-center">
          <div className="text-foreground font-semibold text-xl">{formatCoins(account.balance)}</div>
          <div className="text-xs text-muted-foreground">{t('student.profile.coinBalance')}</div>
        </div>
        <div className="bg-card border border-border rounded-xl p-3 text-center">
          <div className="text-foreground font-semibold text-xl">{Math.round((progress.completedMinutes / progress.annualRequiredMinutes) * 100)}%</div>
          <div className="text-xs text-muted-foreground">{t('student.profile.annualProgress')}</div>
        </div>
      </div>

      {/* Language settings */}
      <div className="bg-card border border-border rounded-2xl overflow-hidden">
        <div className="px-4 py-3 border-b border-border flex items-center gap-2">
          <Globe className="w-4 h-4 text-muted-foreground" />
          <span className="text-sm font-medium text-muted-foreground">{t('student.profile.languageTitle')}</span>
        </div>
        {languages.map(lang => (
          <button
            key={lang.code}
            onClick={() => changeLang(lang.code)}
            className="w-full flex items-center gap-3 px-4 py-3 hover:bg-accent transition-colors border-b border-border last:border-b-0"
          >
            <div className="text-muted-foreground text-sm">{lang.nativeLabel}</div>
            <span className="flex-1 text-sm text-foreground text-left">{lang.label}</span>
            {i18n.language === lang.code && (
              <span className="w-4 h-4 rounded-full bg-[oklch(0.488_0.243_264.376)] flex items-center justify-center">
                <span className="text-white text-xs">✓</span>
              </span>
            )}
          </button>
        ))}
      </div>

      {/* Theme settings */}
      <div className="bg-card border border-border rounded-2xl overflow-hidden">
        <div className="px-4 py-3 border-b border-border">
          <div className="text-sm text-muted-foreground font-medium">{t('student.profile.themeTitle')}</div>
        </div>
        {themeOptions.map(opt => (
          <button
            key={opt.value}
            onClick={() => setTheme(opt.value)}
            className="w-full flex items-center gap-3 px-4 py-3 hover:bg-accent transition-colors border-b border-border last:border-b-0"
          >
            <div className="text-muted-foreground">{opt.icon}</div>
            <span className="flex-1 text-sm text-foreground text-left">{opt.label}</span>
            {theme === opt.value && (
              <span className="w-4 h-4 rounded-full bg-[oklch(0.488_0.243_264.376)] flex items-center justify-center">
                <span className="text-white text-xs">✓</span>
              </span>
            )}
          </button>
        ))}
      </div>

      {/* Notification settings */}
      <div className="bg-card border border-border rounded-2xl overflow-hidden">
        <div className="px-4 py-3 border-b border-border flex items-center gap-2">
          <Bell className="w-4 h-4 text-muted-foreground" />
          <span className="text-sm font-medium text-muted-foreground">{t('student.profile.notifSettings')}</span>
        </div>
        {notifSettings.map(s => (
          <div key={s.key} className="flex items-center gap-3 px-4 py-3 border-b border-border last:border-b-0">
            <s.icon className="w-4 h-4 text-muted-foreground shrink-0 mt-0.5" />
            <div className="flex-1 min-w-0">
              <div className="text-sm text-foreground">{s.label}</div>
              <div className="text-xs text-muted-foreground mt-0.5 leading-relaxed">{s.desc}</div>
            </div>
            <button
              role="switch"
              aria-checked={s.enabled}
              onClick={() => toggleNotif(s.key)}
              className={`relative w-10 h-6 rounded-full transition-colors duration-200 shrink-0 focus:outline-none ${s.enabled ? 'bg-[oklch(0.488_0.243_264.376)]' : 'bg-muted'}`}
            >
              <span className={`absolute top-1 left-1 w-4 h-4 bg-white rounded-full shadow transition-transform duration-200 ${s.enabled ? 'translate-x-4' : 'translate-x-0'}`} />
            </button>
          </div>
        ))}
      </div>

      {/* Menu items */}
      <div className="bg-card border border-border rounded-2xl overflow-hidden">
        {[
          { icon: Shield, label: t('student.profile.accountSecurity'), action: () => setShowPrivacyModal(true) },
          { icon: HelpCircle, label: t('student.profile.helpRules'), action: () => {} },
        ].map((item, i) => (
          <button
            key={i}
            onClick={item.action}
            className="w-full flex items-center gap-3 px-4 py-4 hover:bg-accent transition-colors border-b border-border last:border-b-0"
          >
            <item.icon className="w-5 h-5 text-muted-foreground shrink-0" />
            <span className="flex-1 text-sm text-foreground text-left">{item.label}</span>
            <ChevronRight className="w-4 h-4 text-muted-foreground" />
          </button>
        ))}
      </div>

      {/* Basic info — name/email are self-service (PATCH /me/profile); studentId/grade stay read-only (school records, Design.md §7's "authoritative fields") */}
      <div className="bg-card border border-border rounded-2xl overflow-hidden">
        <div className="px-4 py-3 border-b border-border flex items-center justify-between gap-2">
          <div className="text-sm text-muted-foreground font-medium">{t('student.profile.basicInfo')}</div>
          {editingBasicInfo ? (
            <div className="flex items-center gap-2">
              <button
                onClick={cancelEditBasicInfo}
                disabled={savingBasicInfo}
                className="text-xs text-muted-foreground hover:text-foreground transition-colors disabled:opacity-50"
              >
                {t('common.cancel')}
              </button>
              <button
                onClick={saveBasicInfo}
                disabled={savingBasicInfo}
                className="text-xs px-3 py-1.5 rounded-lg bg-[oklch(0.488_0.243_264.376)] text-white hover:opacity-90 transition-opacity disabled:opacity-50"
              >
                {savingBasicInfo ? t('common.saving') : t('common.save')}
              </button>
            </div>
          ) : (
            <button
              onClick={startEditBasicInfo}
              className="flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground transition-colors"
            >
              <Pencil className="w-3.5 h-3.5" />
              {t('common.edit')}
            </button>
          )}
        </div>

        <div className="flex items-center justify-between px-4 py-3 border-b border-border">
          <span className="text-sm text-muted-foreground shrink-0">{t('student.profile.name')}</span>
          {editingBasicInfo ? (
            <div className="flex-1 ml-4">
              <input
                type="text"
                value={basicForm.displayName}
                onChange={e => setBasicForm(prev => ({ ...prev, displayName: e.target.value }))}
                className="w-full px-3 py-1.5 text-sm text-right rounded-lg border border-border bg-input-background dark:bg-muted text-foreground focus:outline-none focus:ring-2 focus:ring-primary/50 focus:border-primary transition-colors"
              />
              <FieldError message={basicInfoErrors.getError('displayName')} />
            </div>
          ) : (
            <span className="text-sm text-foreground">{user?.displayName || '—'}</span>
          )}
        </div>

        <div className="flex items-center justify-between px-4 py-3 border-b border-border">
          <span className="text-sm text-muted-foreground">{t('student.profile.studentId')}</span>
          <span className="text-sm text-foreground">{user?.studentId || '—'}</span>
        </div>

        <div className="flex items-center justify-between px-4 py-3 border-b border-border">
          <span className="text-sm text-muted-foreground">{t('student.profile.grade')}</span>
          <span className="text-sm text-foreground">{progress.grade}</span>
        </div>

        <div className="flex items-center justify-between px-4 py-3 border-b border-border last:border-b-0">
          <span className="text-sm text-muted-foreground shrink-0">{t('student.profile.email')}</span>
          {editingBasicInfo ? (
            <div className="flex-1 ml-4">
              <input
                type="email"
                value={basicForm.email}
                onChange={e => setBasicForm(prev => ({ ...prev, email: e.target.value }))}
                className="w-full px-3 py-1.5 text-sm text-right rounded-lg border border-border bg-input-background dark:bg-muted text-foreground focus:outline-none focus:ring-2 focus:ring-primary/50 focus:border-primary transition-colors"
              />
              <FieldError message={basicInfoErrors.getError('email')} />
            </div>
          ) : (
            <span className="text-sm text-foreground">{user?.email || '—'}</span>
          )}
        </div>

        <p className="px-4 py-2.5 text-xs text-muted-foreground bg-muted/30">{t('student.profile.basicInfoNote')}</p>
      </div>

      {/* Logout */}
      <button
        onClick={handleLogout}
        className="w-full py-4 bg-card border border-border rounded-2xl text-destructive hover:bg-destructive/10 transition-colors flex items-center justify-center gap-2 text-sm"
      >
        <LogOut className="w-4 h-4" />
        {t('common.logout')}
      </button>

      <p className="text-center text-xs text-muted-foreground pb-4">
        {schoolInfo?.name ?? ''} Student Volunteer Service Time Bank · {schoolInfo?.studentDevCenter ?? ''} · {schoolInfo?.contact ?? ''}
      </p>

      {/* Account safety & privacy modal */}
      {showPrivacyModal && (
        <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4">
          {/* Backdrop */}
          <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" />

          {/* Sheet / dialog */}
          <div className="relative bg-card w-full sm:max-w-lg sm:rounded-2xl rounded-t-2xl shadow-xl flex flex-col max-h-[90vh]">
            {/* Header */}
            <div className="flex items-center gap-3 px-5 py-4 border-b border-border shrink-0">
              <Shield className="w-5 h-5 text-[oklch(0.488_0.243_264.376)] shrink-0" />
              <h3 className="flex-1 text-foreground">{t('student.profile.privacyTitle')}</h3>
            </div>

            {/* Scrollable body */}
            <div className="overflow-y-auto px-5 py-4 space-y-5 text-sm">

              {/* Sections */}
              {[
                {
                  icon: Lock,
                  title: t('student.profile.accountSafety'),
                  items: [
                    t('student.profile.accountSafety1'),
                    t('student.profile.accountSafety2'),
                    t('student.profile.accountSafety3'),
                  ],
                },
                {
                  icon: Eye,
                  title: t('student.profile.dataVisibility'),
                  items: [
                    t('student.profile.dataVisibility1'),
                    t('student.profile.dataVisibility2'),
                    t('student.profile.dataVisibility3'),
                  ],
                },
                {
                  icon: Database,
                  title: t('student.profile.dataCollection'),
                  items: [
                    t('student.profile.dataCollection1'),
                    t('student.profile.dataCollection2'),
                    t('student.profile.dataCollection3'),
                  ],
                },
                {
                  icon: UserCheck,
                  title: t('student.profile.yourRights'),
                  items: [
                    t('student.profile.rights1'),
                    t('student.profile.rights2'),
                    t('student.profile.rights3'),
                  ],
                },
              ].map(section => (
                <div key={section.title}>
                  <div className="flex items-center gap-2 mb-2">
                    <section.icon className="w-4 h-4 text-[oklch(0.488_0.243_264.376)] shrink-0" />
                    <span className="text-foreground font-medium">{section.title}</span>
                  </div>
                  <ul className="space-y-2 pl-6">
                    {section.items.map((item, i) => (
                      <li key={i} className="text-muted-foreground leading-relaxed list-disc">{item}</li>
                    ))}
                  </ul>
                </div>
              ))}

              {/* Footer note */}
              <div className="bg-muted/50 rounded-xl px-4 py-3 text-xs text-muted-foreground leading-relaxed">
                {t('student.profile.privacyFooter')}
              </div>
            </div>

            {/* Footer */}
            <div className="px-5 py-4 border-t border-border shrink-0">
              <button onClick={() => setShowPrivacyModal(false)}
                className="w-full py-2.5 rounded-xl bg-[oklch(0.488_0.243_264.376)] text-white text-sm hover:opacity-90 transition-opacity">
                {t('student.profile.understood')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
