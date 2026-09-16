import { useState } from 'react';
import { Link, useNavigate, useSearchParams, Navigate } from 'react-router';
import { Eye, EyeOff, KeyRound, ArrowLeft, Sun, Moon, Monitor } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { toast } from 'sonner';
import { useAuth, useTheme } from '../../App';
import { api } from '../../api/client';
import { ApiError } from '../../api/http';
import { LanguageSwitcher } from '../LanguageSwitcher';

// First-time access screen (Docs/optimization/10-StudentIdentityAndOnboarding.md
// §2.9, §3.8). The route is `/activate` because the printed slip carries the
// site's root URL and the login page carries the entry — a student only ever
// reaches this path by tapping that entry, or by scanning the QR, which deep
// links here as `/activate?code=...` with the code prefilled.
//
// NOT WIRED TO A BACKEND YET: `api.auth.activate` posts to an endpoint that
// does not exist (plan §3.5 is owned by another slice), so submitting shows the
// generic failure message. Everything up to the request — normalisation, format
// checks, password rules — is real.

// §2.5: nine Crockford data characters plus one check character. Crockford
// excludes I, L, O and U from the data alphabet precisely so a handwritten or
// mistyped `O`/`l` can be folded back deterministically.
const CROCKFORD_DATA = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
// Crockford's spec allows five extra symbols in the *check* position only. The
// backend may or may not use them, so accept either rather than reject a valid
// code on a guess.
const CROCKFORD_CHECK_EXTRA = '*~$=U';
const CODE_LENGTH = 10;
const MIN_PASSWORD_LENGTH = 8; // matches the backend's @Size(min = 8) on password fields

/** Uppercases, drops separators, and folds Crockford's ambiguous characters. */
export function normalizeActivationCode(raw: string): string {
  return raw
    .toUpperCase()
    .replace(/[^0-9A-Z*~$=]/g, '')
    .replace(/[ILO]/g, c => (c === 'O' ? '0' : '1'))
    .slice(0, CODE_LENGTH);
}

/**
 * Shape check only. The check *character* is not verified here — its algorithm
 * is defined by the generator (plan §2.5), which does not exist yet; validating
 * it against a guessed variant would reject real codes.
 */
export function isWellFormedActivationCode(code: string): boolean {
  if (code.length !== CODE_LENGTH) return false;
  for (let i = 0; i < CODE_LENGTH - 1; i += 1) {
    if (!CROCKFORD_DATA.includes(code[i])) return false;
  }
  const check = code[CODE_LENGTH - 1];
  return CROCKFORD_DATA.includes(check) || CROCKFORD_CHECK_EXTRA.includes(check);
}

/** `HKTZ49QP2M` → `HKTZ4-9QP2M`, the grouping the slip prints. */
function formatActivationCode(code: string): string {
  return code.length > 5 ? `${code.slice(0, 5)}-${code.slice(5)}` : code;
}

export function ActivationPage() {
  const navigate = useNavigate();
  const { t } = useTranslation();
  const { user } = useAuth();
  const { theme, setTheme } = useTheme();
  const [searchParams] = useSearchParams();

  const [code, setCode] = useState(() => normalizeActivationCode(searchParams.get('code') ?? ''));
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [showPwd, setShowPwd] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  // An already-signed-in user has nothing to activate.
  if (user) {
    return <Navigate to={user.workspaces[0] === 'student' ? '/student/home' : '/admin/dashboard'} replace />;
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!code) return setError(t('auth.activation.codeRequired'));
    if (!isWellFormedActivationCode(code)) return setError(t('auth.activation.codeFormat'));
    if (!password) return setError(t('auth.activation.passwordRequired'));
    if (password.length < MIN_PASSWORD_LENGTH) return setError(t('auth.activation.passwordMinLength'));
    if (password !== confirm) return setError(t('auth.activation.passwordMismatch'));

    setError('');
    setLoading(true);
    try {
      await api.auth.activate(code, password);
      toast.success(t('auth.activation.success'));
      navigate('/login');
    } catch (err) {
      setError(err instanceof ApiError && err.message ? err.message : t('auth.activation.failed'));
    } finally {
      setLoading(false);
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

  const inputClass =
    'w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50 focus:border-primary transition-colors';

  return (
    <div className="min-h-screen flex flex-col items-center justify-center bg-gradient-to-br from-background to-secondary/30 px-4 py-10">
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
        <div className="text-center mb-8">
          <div className="inline-flex items-center justify-center w-16 h-16 bg-primary rounded-2xl mb-4 shadow-lg">
            <KeyRound className="w-8 h-8 text-primary-foreground" />
          </div>
          <h1 className="text-foreground mb-1">{t('auth.activation.title')}</h1>
          <p className="text-muted-foreground text-sm">{t('auth.activation.subtitle')}</p>
        </div>

        <div className="bg-card border border-border rounded-2xl shadow-lg p-6 sm:p-8">
          <form onSubmit={handleSubmit} className="space-y-5">
            <div>
              <label htmlFor="activation-code" className="block text-sm text-foreground mb-2">
                {t('auth.activation.codeLabel')}
              </label>
              <input
                id="activation-code"
                type="text"
                value={formatActivationCode(code)}
                onChange={e => setCode(normalizeActivationCode(e.target.value))}
                placeholder={t('auth.activation.codePlaceholder')}
                required
                autoComplete="one-time-code"
                autoCapitalize="characters"
                autoCorrect="off"
                spellCheck={false}
                inputMode="text"
                className={`${inputClass} font-mono tracking-widest uppercase`}
              />
              <p className="text-xs text-muted-foreground mt-2">{t('auth.activation.codeHint')}</p>
            </div>

            <div>
              <label htmlFor="activation-password" className="block text-sm text-foreground mb-2">
                {t('auth.activation.passwordLabel')}
              </label>
              <div className="relative">
                <input
                  id="activation-password"
                  type={showPwd ? 'text' : 'password'}
                  value={password}
                  onChange={e => setPassword(e.target.value)}
                  placeholder={t('auth.activation.passwordPlaceholder')}
                  required
                  autoComplete="new-password"
                  className={`${inputClass} pr-12`}
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

            <div>
              <label htmlFor="activation-confirm" className="block text-sm text-foreground mb-2">
                {t('auth.activation.confirmLabel')}
              </label>
              <input
                id="activation-confirm"
                type={showPwd ? 'text' : 'password'}
                value={confirm}
                onChange={e => setConfirm(e.target.value)}
                placeholder={t('auth.activation.confirmPlaceholder')}
                required
                autoComplete="new-password"
                className={inputClass}
              />
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
                  {t('auth.activation.submitting')}
                </span>
              ) : t('auth.activation.submit')}
            </button>
          </form>
        </div>

        <div className="text-center mt-6">
          <Link
            to="/login"
            className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-primary transition-colors"
          >
            <ArrowLeft className="w-4 h-4" />
            {t('auth.activation.backToLogin')}
          </Link>
        </div>
      </div>
    </div>
  );
}
