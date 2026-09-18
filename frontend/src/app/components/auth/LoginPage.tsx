import { useState, useEffect } from 'react';
import { useNavigate, Navigate, Link } from 'react-router';
import { Eye, EyeOff, Clock, KeyRound, Sun, Moon, Monitor } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { useAuth, useTheme } from '../../App';
import { toast } from 'sonner';
import { api } from '../../api/client';
import { type SchoolInfo } from '../../api/types';
import { LanguageSwitcher } from '../LanguageSwitcher';

const PROJECT_REPO_URL = 'https://github.com/AquaticismAQ/timebank';

export function LoginPage() {
  const navigate = useNavigate();
  const { t } = useTranslation();
  const { login, user } = useAuth();
  const { theme, setTheme } = useTheme();

  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [showPwd, setShowPwd] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  // Only the name is available here — see `api.school.getIdentity`.
  const [schoolInfo, setSchoolInfo] = useState<{ name: string; fullName?: string } | null>(null);

  useEffect(() => {
    // `getIdentity`, not `getInfo`: this page is unauthenticated (issue: the
    // name rendered blank because /school/info 401s before login).
    api.school.getIdentity().then(setSchoolInfo).catch(() => {});
  }, []);

  // Redirect if already logged in — 07-RoleFinetune.md §2.5: one role, one workspace, always.
  if (user) {
    return <Navigate to={user.workspaces[0] === 'student' ? '/student/home' : '/admin/dashboard'} replace />;
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    const ok = await login(username, password);
    setLoading(false);
    if (ok) {
      toast.success(t('auth.login.loginSuccess'));
      const u = JSON.parse(localStorage.getItem('timebank-user') || '{}');
      navigate(u.workspaces?.[0] === 'student' ? '/student/home' : '/admin/dashboard');
    } else {
      setError(t('auth.login.loginError'));
    }
  };

  const themeIcons: Record<string, React.ReactNode> = {
    light: <Sun className="w-4 h-4" />,
    dark: <Moon className="w-4 h-4" />,
    auto: <Monitor className="w-4 h-4" />,
  };

  const themeOptions: { value: 'light' | 'dark' | 'auto'; label: string }[] = [
    { value: 'light', label: t('common.theme.light') },
    { value: 'dark', label: t('common.theme.dark') },
    { value: 'auto', label: t('common.theme.auto') },
  ];

  return (
    <div className="min-h-screen flex flex-col items-center justify-center bg-gradient-to-br from-background to-secondary/30 px-4">
      {/* Theme toggle */}
      <div className="absolute top-4 right-4 flex items-center gap-2">
        <LanguageSwitcher />
        <div className="flex gap-1 bg-card border border-border rounded-lg p-1">
          {themeOptions.map(opt => (
            <button
              key={opt.value}
              onClick={() => setTheme(opt.value)}
              title={opt.label}
              className={`p-2 rounded-md transition-colors ${
                theme === opt.value
                  ? 'bg-primary text-primary-foreground'
                  : 'text-muted-foreground hover:text-foreground hover:bg-accent'
              }`}
            >
              {themeIcons[opt.value]}
            </button>
          ))}
        </div>
      </div>

      <div className="w-full max-w-md">
        {/* Logo & brand */}
        <div className="text-center mb-8">
          <div className="inline-flex items-center justify-center w-16 h-16 bg-primary rounded-2xl mb-4 shadow-lg">
            <Clock className="w-8 h-8 text-primary-foreground" />
          </div>
          <h1 className="text-foreground mb-1">{t('auth.login.title')}</h1>
          {/* School name leads at a larger size; the platform description sits on
              its own line rather than running on after it. */}
          {/* `name` is the host school's own name — the one `school_info.name` holds and
          the one its `org_school_default` organization row carries. **Not `fullName`**:
          that field is free text a school types in the School Info editor, and on at
          least one deployment it was holding a *partner organisation's* name, so
          preferring it displayed a partner as the host school. */}
          {schoolInfo?.name && <p className="text-foreground text-lg font-medium">{schoolInfo.name}</p>}
          <p className="text-muted-foreground text-sm">{t('auth.login.subtitle')}</p>
        </div>

        {/* Login card */}
        <div className="bg-card border border-border rounded-2xl shadow-lg p-8">
          <form onSubmit={handleSubmit} className="space-y-5">
            <div>
              <label htmlFor="username" className="block text-sm text-foreground mb-2">
                {t('auth.login.accountPlaceholder')}
              </label>
              <input
                id="username"
                type="text"
                value={username}
                onChange={e => setUsername(e.target.value)}
                placeholder={t('auth.login.accountPlaceholder')}
                required
                autoComplete="username"
                className="w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50 focus:border-primary transition-colors"
              />
            </div>

            <div>
              <label htmlFor="password" className="block text-sm text-foreground mb-2">
                {t('auth.login.password')}
              </label>
              <div className="relative">
                <input
                  id="password"
                  type={showPwd ? 'text' : 'password'}
                  value={password}
                  onChange={e => setPassword(e.target.value)}
                  placeholder={t('auth.login.passwordRequired')}
                  required
                  autoComplete="current-password"
                  className="w-full px-4 py-3 pr-12 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50 focus:border-primary transition-colors"
                />
                <button
                  type="button"
                  onClick={() => setShowPwd(!showPwd)}
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground p-1"
                >
                  {showPwd ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                </button>
              </div>
            </div>

            {error && (
              <div className="text-sm text-destructive bg-destructive/10 px-4 py-3 rounded-lg">
                {error}
              </div>
            )}

            <button
              type="submit"
              disabled={loading}
              className="w-full py-3 px-6 bg-[oklch(0.488_0.243_264.376)] text-white rounded-xl hover:opacity-90 transition-opacity disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center gap-2"
            >
              {loading ? (
                <span className="flex items-center gap-2">
                  <span className="w-4 h-4 border-2 border-primary-foreground/30 border-t-primary-foreground rounded-full animate-spin" />
                  {t('auth.login.loggingIn')}
                </span>
              ) : t('auth.login.loginButton')}
            </button>

            <div className="text-center">
              <a href="#" className="text-sm text-muted-foreground hover:text-primary transition-colors">
                {t('auth.login.forgotPassword')}
              </a>
            </div>
          </form>

          {/* First-time entry (10-StudentIdentityAndOnboarding.md §2.9): the slip
              prints the site's root URL, so this is the only signposted way in
              for a student who has never logged in. It is deliberately a
              full-width block, not a footnote link, and its wording is verbatim
              what the printed slip says — a child has to match paper to screen. */}
          <div className="mt-6 pt-6 border-t border-border">
            <Link
              to="/activate"
              className="w-full py-3 px-4 rounded-xl border border-primary/40 bg-primary/5 text-primary hover:bg-primary/10 transition-colors flex items-center justify-center gap-2 text-sm font-medium text-center"
            >
              <KeyRound className="w-4 h-4 shrink-0" />
              {t('auth.login.firstTimeEntry')}
            </Link>
            <p className="text-xs text-muted-foreground text-center mt-2">
              {t('auth.login.firstTimeHint')}
            </p>
          </div>
        </div>

        <div className="text-center text-xs text-muted-foreground mt-6 space-y-1">
          <p>{t('auth.login.versionLine', { name: t('auth.login.title'), version: __APP_VERSION__ })}</p>
          <p>
            <a href={PROJECT_REPO_URL} target="_blank" rel="noreferrer"
              className="hover:text-foreground transition-colors underline-offset-2 hover:underline">
              {PROJECT_REPO_URL}
            </a>
          </p>
        </div>
      </div>
    </div>
  );
}
