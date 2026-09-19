import i18n from '../../i18n';

// Display formatters for the wire's canonical units (APIContract.md §2.1):
// integer centi-coins and integer minutes. Convert in exactly these two
// places — grep for direct `/ 100` or `/ 60` elsewhere before adding one.

/**
 * Grades 6-12 are the ones the school dictionary defines
 * (`R__seed_base_data.sql`) — a small lookup, not a general numeral
 * converter, since Chinese numeral construction isn't a formula for two-
 * digit numbers (十 for 10, not a concatenation of 1 and 0's characters).
 *
 * 12 was added with the senior stage (`Design/*\u002f13-AnnualRollover.md` §2).
 * The fallback below is why this needed finding by looking: an unmapped grade
 * renders as "12年级" rather than failing, which sits next to "十一年级" and
 * reads as a formatting slip rather than a missing entry.
 */
const ZH_GRADE_NUMERALS: Record<number, string> = {
  6: '六', 7: '七', 8: '八', 9: '九', 10: '十', 11: '十一', 12: '十二',
};

/**
 * "G10, Class A" (en) / "十年级, A班" (zh). `gradeLevel`/`className` arrive
 * from the API as raw, language-invariant data (issue 010 — neither is
 * translated content: a grade number and a class label like "Luna" are the
 * same in every language) — this is where the localized display string is
 * assembled, so every display location shows the same format instead of
 * repeating inline template literals (issue 005).
 */
export function formatGradeClass(gradeLevel: number, className: string): string {
  const zh = i18n.language.startsWith('zh');
  const grade = zh ? `${ZH_GRADE_NUMERALS[gradeLevel] ?? gradeLevel}年级` : `G${gradeLevel}`;
  if (!className) return grade;
  return zh ? `${grade}, ${className}班` : `${grade}, Class ${className}`;
}

/** Standalone class-only labels (filter/edit `<select>` options) — "A" (en, unchanged) / "A班" (zh). */
export function formatClassName(className: string): string {
  return i18n.language.startsWith('zh') ? `${className}班` : className;
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
