import { useState, useEffect, useMemo } from 'react';
import { useNavigate } from 'react-router';
import { LogOut, ChevronRight, Bell, Shield, HelpCircle, Moon, Sun, Monitor, Mail, X, Lock, Eye, Database, UserCheck, Globe } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { useAuth, useTheme } from '../../App';
import { type SchoolInfo } from '../../api/types';
import { changeLanguage } from '../../lib/language';
import { api } from '../../api/client';

export function AdminProfilePage() {
  const { t, i18n } = useTranslation();
  const { user, logout } = useAuth();
  const { theme, setTheme } = useTheme();
  const navigate = useNavigate();

  const [schoolInfo, setSchoolInfo] = useState<SchoolInfo | null>(null);

  useEffect(() => {
    api.school.getInfo().then(setSchoolInfo).catch(() => {});
  }, []);

  const [notifEnabled, setNotifEnabled] = useState<Record<string, boolean>>({
    email_audit: true,
    push_system: true,
  });

  const notifSettings = useMemo(() => [
    { key: 'email_audit', icon: Mail, label: t('admin.profile.notifEmailAudit'), desc: t('admin.profile.notifEmailAuditDesc', { email: user?.email || '' }), enabled: notifEnabled.email_audit },
    { key: 'push_system', icon: Bell, label: t('admin.profile.notifPushSystem'), desc: t('admin.profile.notifPushSystemDesc'), enabled: notifEnabled.push_system },
  ], [t, i18n.language, user?.email, notifEnabled]);

  const toggleNotif = (key: string) =>
    setNotifEnabled(prev => ({ ...prev, [key]: !prev[key] }));

  const [showPrivacyModal, setShowPrivacyModal] = useState(false);

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
    { code: 'zh', label: t('admin.profile.langZh'), nativeLabel: '中文' },
    { code: 'en', label: t('admin.profile.langEn'), nativeLabel: 'English' },
  ];

  const changeLang = (code: string) => {
    changeLanguage(code as 'zh' | 'en');
  };

  const roleLabels: Record<string, string> = {
    admin: t('mock.roles.admin'),
    teacher: t('mock.roles.teacher'),
    student: t('mock.roles.student'),
    sysadmin: t('mock.roles.admin'),
  };

  return (
    <div className="p-4 lg:p-6 space-y-5">
      <h2 className="text-foreground">{t('admin.profile.title')}</h2>

      {/* Profile card */}
      <div className="bg-card border border-border rounded-2xl p-5 flex items-center gap-4">
        <div className="w-16 h-16 bg-[oklch(0.488_0.243_264.376)] rounded-full flex items-center justify-center shrink-0">
          <span className="text-white text-2xl font-semibold">{user?.displayName?.[0]}</span>
        </div>
        <div className="flex-1 min-w-0">
          <div className="text-foreground font-semibold text-lg">{user?.displayName}</div>
          <div className="text-sm text-muted-foreground">
            {user?.roles.map(r => roleLabels[r] ?? r).join(' · ')}
          </div>
          <div className="text-xs text-muted-foreground mt-0.5">{user?.email}</div>
        </div>
      </div>

      {/* Language settings */}
      <div className="bg-card border border-border rounded-2xl overflow-hidden">
        <div className="px-4 py-3 border-b border-border flex items-center gap-2">
          <Globe className="w-4 h-4 text-muted-foreground" />
          <span className="text-sm font-medium text-muted-foreground">{t('admin.profile.languageTitle')}</span>
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
          <div className="text-sm text-muted-foreground font-medium">{t('admin.profile.themeTitle')}</div>
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
          <span className="text-sm font-medium text-muted-foreground">{t('admin.profile.notifSettings')}</span>
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
          { icon: Shield, label: t('admin.profile.accountSecurity'), action: () => setShowPrivacyModal(true) },
          { icon: HelpCircle, label: t('admin.profile.helpRules'), action: () => {} },
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

      {/* Basic info (read-only) */}
      <div className="bg-card border border-border rounded-2xl overflow-hidden">
        <div className="px-4 py-3 border-b border-border">
          <div className="text-sm text-muted-foreground font-medium">{t('admin.profile.basicInfo')}</div>
        </div>
        {[
          { label: t('admin.profile.name'), value: user?.displayName || '—' },
          { label: t('admin.profile.email'), value: user?.email || '—' },
          { label: t('admin.profile.roles'), value: user?.roles.map(r => roleLabels[r] ?? r).join(', ') || '—' },
        ].map((item, i) => (
          <div key={i} className="flex items-center justify-between px-4 py-3 border-b border-border last:border-b-0">
            <span className="text-sm text-muted-foreground">{item.label}</span>
            <span className="text-sm text-foreground">{item.value}</span>
          </div>
        ))}
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
        {schoolInfo?.name ?? ''} · {schoolInfo?.studentDevCenter ?? ''} · {schoolInfo?.contact ?? ''}
      </p>

      {/* Account safety & privacy modal */}
      {showPrivacyModal && (
        <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4">
          {/* Backdrop */}
          <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" onClick={() => setShowPrivacyModal(false)} />

          {/* Sheet / dialog */}
          <div className="relative bg-card w-full sm:max-w-lg sm:rounded-2xl rounded-t-2xl shadow-xl flex flex-col max-h-[90vh]">
            {/* Header */}
            <div className="flex items-center gap-3 px-5 py-4 border-b border-border shrink-0">
              <Shield className="w-5 h-5 text-[oklch(0.488_0.243_264.376)] shrink-0" />
              <h3 className="flex-1 text-foreground">{t('admin.profile.privacyTitle')}</h3>
              <button onClick={() => setShowPrivacyModal(false)}
                className="p-1.5 rounded-lg hover:bg-accent text-muted-foreground transition-colors">
                <X className="w-4 h-4" />
              </button>
            </div>

            {/* Scrollable body */}
            <div className="overflow-y-auto px-5 py-4 space-y-5 text-sm">

              {/* Sections */}
              {[
                {
                  icon: Lock,
                  title: t('admin.profile.accountSafety'),
                  items: [
                    t('admin.profile.accountSafety1'),
                    t('admin.profile.accountSafety2'),
                    t('admin.profile.accountSafety3'),
                  ],
                },
                {
                  icon: Eye,
                  title: t('admin.profile.dataVisibility'),
                  items: [
                    t('admin.profile.dataVisibility1'),
                    t('admin.profile.dataVisibility2'),
                    t('admin.profile.dataVisibility3'),
                  ],
                },
                {
                  icon: Database,
                  title: t('admin.profile.dataCollection'),
                  items: [
                    t('admin.profile.dataCollection1'),
                    t('admin.profile.dataCollection2'),
                    t('admin.profile.dataCollection3'),
                  ],
                },
                {
                  icon: UserCheck,
                  title: t('admin.profile.yourRights'),
                  items: [
                    t('admin.profile.rights1'),
                    t('admin.profile.rights2'),
                    t('admin.profile.rights3'),
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
                {t('admin.profile.privacyFooter')}
              </div>
            </div>

            {/* Footer */}
            <div className="px-5 py-4 border-t border-border shrink-0">
              <button onClick={() => setShowPrivacyModal(false)}
                className="w-full py-2.5 rounded-xl bg-[oklch(0.488_0.243_264.376)] text-white text-sm hover:opacity-90 transition-opacity">
                {t('admin.profile.understood')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
