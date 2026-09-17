import { useTranslation } from 'react-i18next';
import { Check, X, Circle } from 'lucide-react';
import {
  MAX_LENGTH,
  MIN_CHARACTER_CLASSES,
  MIN_LENGTH,
  messageKeyFor,
  requirementsFor,
  strengthOf,
  type PasswordStrength as Strength,
} from '../../lib/passwordPolicy';

/**
 * The live password checker, shown under every box where a password is set
 * (activation, admin create-user). It renders the *requirements*, not just a
 * verdict: a student told "weak" and nothing else cannot act on it, and the
 * button they need is disabled until they do. The coloured bar is an extra
 * on top of the list, never a replacement for it.
 *
 * The rule itself lives in `lib/passwordPolicy.ts`, which mirrors the
 * backend's `PasswordPolicy` exactly — this file only draws it.
 */

interface PasswordStrengthProps {
  password: string;
  /**
   * The account the password is for, when the screen knows it. At activation
   * it does not — the student holds a printed code, not a username — so the
   * "must not contain your username" row is hidden there and the server
   * applies that check itself.
   */
  username?: string | null;
  /** The confirmation box's value; omit to hide the match row entirely. */
  confirm?: string;
  className?: string;
}

const BAR_SEGMENTS = 4;

const STRENGTH_META: Record<Exclude<Strength, 'none'>, { filled: number; bar: string; text: string; labelKey: string }> = {
  weak: { filled: 1, bar: 'bg-destructive', text: 'text-destructive', labelKey: 'password.strength.weak' },
  fair: { filled: 2, bar: 'bg-amber-500', text: 'text-amber-600 dark:text-amber-500', labelKey: 'password.strength.fair' },
  good: { filled: 3, bar: 'bg-emerald-500', text: 'text-emerald-600 dark:text-emerald-500', labelKey: 'password.strength.good' },
  strong: { filled: 4, bar: 'bg-emerald-600', text: 'text-emerald-600 dark:text-emerald-500', labelKey: 'password.strength.strong' },
};

export function PasswordStrength({ password, username, confirm, className = '' }: PasswordStrengthProps) {
  const { t } = useTranslation();
  const requirements = requirementsFor(password, username);
  const strength = strengthOf(password, username);
  const meta = strength === 'none' ? null : STRENGTH_META[strength];

  return (
    <div className={`mt-2 space-y-2 ${className}`}>
      {/* Strength bar. Hidden until there is something to score, so an
          untouched form isn't already shouting "weak" in red. */}
      {meta && (
        <div className="flex items-center gap-2">
          <div className="flex-1 flex gap-1" aria-hidden="true">
            {Array.from({ length: BAR_SEGMENTS }, (_, i) => (
              <span
                key={i}
                className={`h-1 flex-1 rounded-full transition-colors ${i < meta.filled ? meta.bar : 'bg-border'}`}
              />
            ))}
          </div>
          <span className={`text-xs shrink-0 ${meta.text}`}>
            {t('password.strength.label')}: {t(meta.labelKey)}
          </span>
        </div>
      )}

      {/* The requirements themselves — always visible, so the list doubles as
          the explanation for why the submit button is still disabled. */}
      <div>
        <p className="text-xs text-muted-foreground mb-1.5">{t('password.requirement.title')}</p>
        <ul className="space-y-1" aria-live="polite">
          {requirements.map(req => (
            <li key={req.id} className="flex items-start gap-1.5 text-xs">
              {req.met ? (
                <Check className="w-3.5 h-3.5 shrink-0 mt-px text-emerald-600 dark:text-emerald-500" aria-hidden="true" />
              ) : password.length === 0 ? (
                <Circle className="w-3.5 h-3.5 shrink-0 mt-px text-muted-foreground" aria-hidden="true" />
              ) : (
                <X className="w-3.5 h-3.5 shrink-0 mt-px text-destructive" aria-hidden="true" />
              )}
              <span className={req.met ? 'text-muted-foreground' : 'text-foreground'}>
                {t(req.labelKey, req.labelValues)}
                <span className="sr-only">
                  {' — '}
                  {t(req.met ? 'password.requirement.met' : 'password.requirement.notMet')}
                </span>
              </span>
            </li>
          ))}
        </ul>
      </div>

      {/* Confirmation match, on the same checklist so "why is the button
          still grey" has exactly one place to look. */}
      {confirm !== undefined && confirm.length > 0 && (
        <div className="flex items-start gap-1.5 text-xs" aria-live="polite">
          {confirm === password ? (
            <>
              <Check className="w-3.5 h-3.5 shrink-0 mt-px text-emerald-600 dark:text-emerald-500" aria-hidden="true" />
              <span className="text-muted-foreground">{t('password.match.ok')}</span>
            </>
          ) : (
            <>
              <X className="w-3.5 h-3.5 shrink-0 mt-px text-destructive" aria-hidden="true" />
              <span className="text-foreground">{t('password.match.no')}</span>
            </>
          )}
        </div>
      )}
    </div>
  );
}

/**
 * Renders a backend `fieldErrors[].code` through the same copy this checker
 * shows, so a server rejection never contradicts what the student was just
 * reading. Returns `null` for a code this build does not know — the caller
 * falls back to its own generic message rather than printing a raw code.
 */
export function passwordFailureMessage(code: string, t: (key: string, values?: object) => string): string | null {
  const key = messageKeyFor(code);
  if (!key) return null;
  return t(key, { min: MIN_LENGTH, max: MAX_LENGTH, classes: MIN_CHARACTER_CLASSES });
}
