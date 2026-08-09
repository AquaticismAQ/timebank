import i18n from '../../i18n';

// Display formatters for the wire's canonical units (APIContract.md §2.1):
// integer centi-coins and integer minutes. Convert in exactly these two
// places — grep for direct `/ 100` or `/ 60` elsewhere before adding one.

/**
 * "Grade 10, Class A" (en) / "十年级A班" (zh). `grade`/`className` arrive
 * from the API as independent, already-localized strings (never combined
 * server-side, contract §2.10) — this is the one place they're joined, so
 * every display location shows the same format instead of five slightly
 * different inline template literals (issue 005). Chinese class names
 * already carry a "班" (class/homeroom) suffix, so no separator word is
 * needed there the way English needs "Class".
 */
export function formatGradeClass(grade: string, className: string): string {
  if (!grade) return className;
  if (!className) return grade;
  return i18n.language.startsWith('zh') ? `${grade}${className}` : `${grade}, Class ${className}`;
}

/** `centi` is integer centi-coins (1 coin = 100 units). Trims trailing zeros: 600 -> "6", 650 -> "6.5". */
export function formatCoins(centi: number): string {
  const sign = centi < 0 ? '-' : '';
  const abs = Math.abs(centi);
  const whole = Math.floor(abs / 100);
  const fraction = abs % 100;
  if (fraction === 0) return `${sign}${whole}`;
  const decimal = fraction % 10 === 0 ? String(fraction / 10) : String(fraction).padStart(2, '0').replace(/0$/, '');
  return `${sign}${whole}.${decimal}`;
}

/** `minutes` is integer minutes. Trims trailing zeros: 240 -> "4", 90 -> "1.5". */
export function minutesToHours(minutes: number): string {
  const sign = minutes < 0 ? '-' : '';
  const abs = Math.abs(minutes);
  const whole = Math.floor(abs / 60);
  const remainder = abs % 60;
  if (remainder === 0) return `${sign}${whole}`;
  const decimal = Math.round((remainder / 60) * 10);
  return decimal === 10 ? `${sign}${whole + 1}` : `${sign}${whole}.${decimal}`;
}
