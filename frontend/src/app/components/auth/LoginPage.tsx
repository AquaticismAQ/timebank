import { useState, useEffect } from 'react';
import { useNavigate, Navigate } from 'react-router';
import { Eye, EyeOff, Clock, Sun, Moon, Monitor } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { useAuth, useTheme } from '../../App';
import { toast } from 'sonner';
import { api } from '../../api/client';
import { type SchoolInfo } from '../../mock/data';
import { LanguageSwitcher } from '../LanguageSwitcher';

export function LoginPage() {
  const navigate = useNavigate();
  const { t } = useTranslation();
  const { login, user, currentWorkspace } = useAuth();
  const { theme, setTheme } = useTheme();

  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [showPwd, setShowPwd] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [schoolInfo, setSchoolInfo] = useState<SchoolInfo | null>(null);

  useEffect(() => {
    api.school.getInfo().then(setSchoolInfo).catch(() => {});
  }, []);

  // Redirect if already logged in
  if (user) {
    if (user.workspaces.length > 1 && !currentWorkspace) return <Navigate to="/workspace" replace />;
    if (currentWorkspace === 'student') return <Navigate to="/student/home" replace />;
    if (currentWorkspace === 'admin') return <Navigate to="/admin/dashboard" replace />;
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
      if (u.workspaces?.length > 1) navigate('/workspace');
      else if (u.workspaces?.[0] === 'student') navigate('/student/home');
      else navigate('/admin/dashboard');
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
          <p className="text-muted-foreground text-sm">{schoolInfo?.name ?? ''} {t('auth.login.subtitle')}</p>
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
        </div>

        {/* Demo accounts */}
        <div className="mt-6 bg-card/50 border border-border rounded-xl p-4">
          <p className="text-xs text-muted-foreground mb-3 font-medium">{t('auth.login.demoAccounts')}</p>
          <div className="grid grid-cols-2 gap-2">
            {[
              { username: 'student001', label: t('auth.login.demoStudent'), role: t('auth.login.demoStudentGrade') },
              { username: 'teacher001', label: t('auth.login.demoTeacher'), role: t('auth.login.demoTeacherRole') },
              { username: 'admin001', label: t('auth.login.demoAdmin'), role: t('auth.login.demoAdminDept') },
              { username: 'both001', label: t('auth.login.demoDual'), role: t('auth.login.demoDualRole') },
            ].map(acc => (
              <button
                key={acc.username}
                onClick={() => { setUsername(acc.username); setPassword('password'); }}
                className="text-left px-3 py-2 rounded-lg border border-border hover:bg-accent hover:border-primary/30 transition-colors"
              >
                <div className="text-xs text-foreground">{acc.label}</div>
                <div className="text-xs text-muted-foreground">{acc.role}</div>
              </button>
            ))}
          </div>
        </div>

        <p className="text-center text-xs text-muted-foreground mt-6">
          {t('auth.login.title')} · {schoolInfo?.name ?? ''} · {schoolInfo?.studentDevCenter ?? ''}
        </p>
      </div>
    </div>
  );
}
