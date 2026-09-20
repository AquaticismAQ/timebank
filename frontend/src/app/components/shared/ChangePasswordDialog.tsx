import { useState } from 'react';
import { useBodyScrollLock } from '../../lib/useBodyScrollLock';
import { useTranslation } from 'react-i18next';
import { KeyRound, AlertTriangle } from 'lucide-react';
import { api } from '../../api/client';
import { ApiError, mapApiError } from '../../api/http';
import { PasswordStrength, passwordFailureMessage, type Translate } from './PasswordStrength';
import { isAcceptable } from '../../lib/passwordPolicy';

/**
 * Self-service password change (issue 111), shared by both portals' profile
 * pages so the student and staff experiences cannot drift apart.
 *
 * <p>Three things this screen owes the user, all of them learned from the
 * activation page it deliberately mirrors:</p>
 *
 * <ul>
 *   <li><b>The requirements are shown, and the button stays disabled until they
 *       are met.</b> A weak password is refused here rather than accepted and
 *       bounced by the server — a round trip that teaches nothing.</li>
 *   <li><b>The current password is required.</b> That is what separates this from
 *       password recovery, which this system deliberately does not have.</li>
 *   <li><b>It says that signing out is part of it</b>, before submitting rather
 *       than after. The server revokes every session including this one, so the
 *       user is about to be logged out whether or not they expected it.</li>
 * </ul>
 */
export function ChangePasswordDialog({ username, onClose, onChanged }: {
  /** The account's username, so the "must not contain your username" rule can be checked live. */
  username?: string | null;
  onClose: () => void;
  /** Called after a successful change; the caller signs the user out. */
  onChanged: () => void;
}) {
  const { t } = useTranslation();
  const [currentPassword, setCurrentPassword] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  // Unlike activation, this screen knows the username, so the live checker can
  // apply the full rule rather than leaving that one row to the server.
  const passwordOk = isAcceptable(password, username);
  const canSubmit = currentPassword.length > 0 && passwordOk && confirm.length > 0 && password === confirm;

  const inputClass = 'w-full px-4 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-[oklch(0.488_0.243_264.376)]/40';

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canSubmit || saving) return;
    setSaving(true);
    setError(null);
    try {
      await api.auth.changePassword(currentPassword, password);
      onChanged();
    } catch (err) {
      if (err instanceof ApiError) {
        // A policy rejection arrives as fieldErrors on `newPassword` carrying the
        // backend's own codes, which render through the same copy the live
        // checker was already showing — so a server refusal reads consistently
        // with what the user saw while typing.
        const fieldError = err.fieldErrors?.find(f => f.field === 'newPassword');
        const fromCode = fieldError ? passwordFailureMessage(fieldError.code, t as Translate) : null;
        setError(fromCode ?? mapApiError(err).message);
      } else {
        setError(t('common.unknownError'));
      }
      setSaving(false);
    }
  };

  // This dialog does not use `useEscapeToClose`, so it asks for the page
  // lock directly — it is mounted only while open (issue 126).
  useBodyScrollLock();

  return (
    <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4">
      {/* No backdrop click-to-close: issue 102 — a half-typed password should not
          be discarded by a stray tap outside the sheet. */}
      <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" />
      <form onSubmit={handleSubmit}
        className="relative bg-card w-full sm:max-w-md sm:rounded-2xl rounded-t-2xl shadow-xl flex flex-col max-h-[92vh]">
        <div className="flex items-center gap-3 px-5 py-4 border-b border-border shrink-0">
          <KeyRound className="w-5 h-5 text-[oklch(0.488_0.243_264.376)] shrink-0" />
          <h3 className="flex-1 text-foreground text-sm">{t('password.change.title')}</h3>
        </div>

        <div className="overflow-y-auto px-5 py-4 space-y-4 text-sm overscroll-contain">
          <div className="flex gap-2.5 rounded-xl border border-amber-500/40 bg-amber-500/10 p-3">
            <AlertTriangle className="w-4 h-4 text-amber-600 dark:text-amber-400 shrink-0 mt-0.5" />
            <p className="text-xs text-amber-800 dark:text-amber-300 leading-relaxed">
              {t('password.change.signsYouOut')}
            </p>
          </div>

          <label className="block">
            <span className="text-xs text-muted-foreground">{t('password.change.currentLabel')}</span>
            <input type="password" autoComplete="current-password" className={inputClass}
              value={currentPassword} onChange={e => setCurrentPassword(e.target.value)} />
          </label>

          <label className="block">
            <span className="text-xs text-muted-foreground">{t('password.change.newLabel')}</span>
            <input type="password" autoComplete="new-password" className={inputClass}
              value={password} onChange={e => setPassword(e.target.value)} />
          </label>

          <label className="block">
            <span className="text-xs text-muted-foreground">{t('password.change.confirmLabel')}</span>
            <input type="password" autoComplete="new-password" className={inputClass}
              value={confirm} onChange={e => setConfirm(e.target.value)} />
          </label>

          <PasswordStrength password={password} username={username} confirm={confirm} />

          {error && <p className="text-xs text-destructive">{error}</p>}
        </div>

        <div className="flex gap-2 px-5 py-4 border-t border-border shrink-0">
          <button type="button" onClick={onClose} disabled={saving}
            className="flex-1 py-2.5 border border-border rounded-xl text-sm text-foreground hover:bg-accent disabled:opacity-40">
            {t('common.cancel')}
          </button>
          <button type="submit" disabled={saving || !canSubmit}
            className="flex-1 py-2.5 rounded-xl text-sm text-white bg-[oklch(0.488_0.243_264.376)] hover:opacity-90 disabled:opacity-40">
            {saving ? t('password.change.saving') : t('password.change.submit')}
          </button>
        </div>
      </form>
    </div>
  );
}
