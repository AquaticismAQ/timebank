/**
 * The frontend's copy of the one password rule, mirroring
 * `cn.timebank.iam.domain.PasswordPolicy` character for character
 * (Design/*\/08-mlps-level1-security-design-guide.md §8.1).
 *
 * This is a courtesy, not a control: the server validates every password it
 * is sent, and this file exists so a twelve-year-old finds out what is wrong
 * while typing rather than after a round trip. That only works if the two
 * agree exactly — a rule relaxed here to make the form feel nicer buys a
 * green tick followed by a server rejection, which is worse than no check at
 * all. If the backend rule changes, change this file in the same commit, and
 * re-check it against `PasswordPolicyTest`, which states the rule as cases.
 *
 * The failure codes are the backend's own, so a 422 carrying
 * `fieldErrors[].code` on field `newPassword` renders through exactly the
 * same copy the live checker was already showing.
 */

export const MIN_LENGTH = 12;
export const MAX_LENGTH = 128;
/** Of lowercase / uppercase / digit / symbol, how many must appear. */
export const MIN_CHARACTER_CLASSES = 3;

export type PasswordFailureCode =
  | 'PASSWORD_REQUIRED'
  | 'PASSWORD_TOO_SHORT'
  | 'PASSWORD_TOO_LONG'
  | 'PASSWORD_TOO_SIMPLE'
  | 'PASSWORD_TOO_COMMON'
  | 'PASSWORD_CONTAINS_USERNAME';

/**
 * Copied verbatim from `PasswordPolicy.BLOCKED`, including the product's own
 * name — the single most likely choice for someone told to pick a password
 * for this system. Keep the two lists identical; a term here that the server
 * does not know would reject a password the server would have accepted.
 */
const BLOCKED: readonly string[] = [
  'password', 'password1', 'password12', 'password123', 'password1234',
  'passw0rd', 'p@ssw0rd', 'qwertyuiop', 'qwerty123456', '1qaz2wsx3edc',
  'administrator', 'adminadmin', 'letmein12345', 'welcome12345',
  'iloveyou1234', 'abc123456789', 'timebank', 'timebank123',
  'timebank2026', 'shijianyinhang', '123456789012', '1234567890',
];

/**
 * The rule itself, as codes — the mirror of `PasswordPolicy.failuresFor`,
 * reporting *every* failure at once rather than the first, so the form can
 * show the whole list instead of revealing one problem per attempt.
 *
 * @param username the account the password is for, so a password that merely
 *   restates it is refused. Pass `null` where the screen genuinely has no
 *   username in hand — at activation the student holds a code, not a
 *   username, and the server applies this check itself with the username the
 *   code resolves to.
 */
export function failuresFor(password: string, username?: string | null): PasswordFailureCode[] {
  if (!password || isBlank(password)) {
    return ['PASSWORD_REQUIRED'];
  }
  const failures: PasswordFailureCode[] = [];
  if (password.length < MIN_LENGTH) {
    failures.push('PASSWORD_TOO_SHORT');
  }
  if (password.length > MAX_LENGTH) {
    failures.push('PASSWORD_TOO_LONG');
  }
  if (characterClasses(password) < MIN_CHARACTER_CLASSES) {
    failures.push('PASSWORD_TOO_SIMPLE');
  }
  if (isBlocked(password)) {
    failures.push('PASSWORD_TOO_COMMON');
  }
  if (containsUsername(password, username)) {
    failures.push('PASSWORD_CONTAINS_USERNAME');
  }
  return failures;
}

/**
 * Java's `String.isBlank()`, which is `Character.isWhitespace` and *not* JS
 * `trim()`: the two differ on the three non-breaking spaces (U+00A0, U+2007,
 * U+202F), which `trim()` strips and Java does not count as whitespace. It is
 * a silly edge — a password made only of non-breaking spaces is refused
 * either way — but refused under a *different code*, and this file's whole
 * job is that the two sides never disagree about anything.
 */
const JAVA_BLANK =
  /^[\t\n\u000B\f\r\u001C-\u001F\u0020\u1680\u2000-\u2006\u2008-\u200A\u2028\u2029\u205F\u3000]*$/;

function isBlank(password: string): boolean {
  return JAVA_BLANK.test(password);
}

/** True when the password satisfies every rule — the form's submit gate. */
export function isAcceptable(password: string, username?: string | null): boolean {
  return failuresFor(password, username).length === 0;
}

// Java tests one UTF-16 `char` at a time against `Character.isLowerCase` /
// `isUpperCase` / `isDigit`, which are Unicode categories, not `[a-z]`. The
// Unicode property escapes are the faithful equivalent: a Chinese character
// or an unpaired surrogate falls through to "symbol" on both sides, exactly
// as `Character.isDigit('三') == false` does.
const LOWER = /\p{Ll}/u;
const UPPER = /\p{Lu}/u;
const DIGIT = /\p{Nd}/u;

/** How many of lowercase / uppercase / digit / symbol the password uses. */
export function characterClasses(password: string): number {
  let lower = false, upper = false, digit = false, symbol = false;
  // `split('')` walks UTF-16 code units, matching Java's `charAt` loop.
  for (const c of password.split('')) {
    if (LOWER.test(c)) lower = true;
    else if (UPPER.test(c)) upper = true;
    else if (DIGIT.test(c)) digit = true;
    else symbol = true;
  }
  return (lower ? 1 : 0) + (upper ? 1 : 0) + (digit ? 1 : 0) + (symbol ? 1 : 0);
}

function isBlocked(password: string): boolean {
  const lower = password.toLowerCase();
  if (BLOCKED.includes(lower)) {
    return true;
  }
  // A blocked word padded out to reach twelve characters is the obvious way
  // round a list, and is no stronger than the word itself.
  for (const blocked of BLOCKED) {
    if (blocked.length >= 6 && lower.includes(blocked)) {
      return true;
    }
  }
  return isSingleRepeatedCharacter(lower) || isSequential(lower);
}

function isSingleRepeatedCharacter(lower: string): boolean {
  return new Set(lower.split('')).size <= 2;
}

/** `123456789012`, `abcdefghijkl` and their reverses, allowing a constant step. */
function isSequential(lower: string): boolean {
  if (lower.length < MIN_LENGTH) {
    return false;
  }
  const step = lower.charCodeAt(1) - lower.charCodeAt(0);
  if (step !== 1 && step !== -1) {
    return false;
  }
  for (let i = 2; i < lower.length; i++) {
    if (lower.charCodeAt(i) - lower.charCodeAt(i - 1) !== step) {
      return false;
    }
  }
  return true;
}

function containsUsername(password: string, username?: string | null): boolean {
  if (!username || username.length < 4) {
    return false;
  }
  return password.toLowerCase().includes(username.toLowerCase());
}

/**
 * The requirement checklist the form renders, in the order a student reads
 * it. Deliberately *not* one row per failure code: `PASSWORD_TOO_SHORT` and
 * `PASSWORD_TOO_LONG` are the same requirement seen from two ends, and a row
 * that only appears once it is already broken cannot be used as a checklist
 * while typing. `PASSWORD_CONTAINS_USERNAME` is the exception, and is shown
 * only where a username is actually known.
 */
export type PasswordRequirementId = 'length' | 'classes' | 'notCommon' | 'notUsername';

export interface PasswordRequirement {
  id: PasswordRequirementId;
  /** i18n key for the requirement's wording. */
  labelKey: string;
  /** Interpolation values for `labelKey`. */
  labelValues?: Record<string, number>;
  met: boolean;
}

export function requirementsFor(password: string, username?: string | null): PasswordRequirement[] {
  const failures = failuresFor(password, username);
  const empty = password.length === 0;
  // An empty box shows every requirement as not-yet-met rather than as
  // failed — nothing has gone wrong yet, the student simply hasn't typed.
  const met = (...codes: PasswordFailureCode[]) =>
    !empty && !codes.some(code => failures.includes(code));
  const list: PasswordRequirement[] = [
    {
      id: 'length',
      labelKey: 'password.requirement.length',
      labelValues: { min: MIN_LENGTH, max: MAX_LENGTH },
      met: met('PASSWORD_TOO_SHORT', 'PASSWORD_TOO_LONG', 'PASSWORD_REQUIRED'),
    },
    {
      id: 'classes',
      labelKey: 'password.requirement.classes',
      labelValues: { classes: MIN_CHARACTER_CLASSES },
      met: met('PASSWORD_TOO_SIMPLE', 'PASSWORD_REQUIRED'),
    },
    {
      id: 'notCommon',
      labelKey: 'password.requirement.notCommon',
      met: met('PASSWORD_TOO_COMMON', 'PASSWORD_REQUIRED'),
    },
  ];
  if (username && username.length >= 4) {
    list.push({
      id: 'notUsername',
      labelKey: 'password.requirement.notUsername',
      met: met('PASSWORD_CONTAINS_USERNAME', 'PASSWORD_REQUIRED'),
    });
  }
  return list;
}

/**
 * A coarse "how much better than the floor is this" reading, shown *beside*
 * the requirement list and never instead of it: a bar alone tells a student
 * their password is weak without telling them what to change. Length is the
 * only thing that keeps earning credit once the rule is satisfied, which is
 * also the honest advice — a longer passphrase beats a shorter one with a
 * symbol bolted on.
 */
export type PasswordStrength = 'none' | 'weak' | 'fair' | 'good' | 'strong';

export function strengthOf(password: string, username?: string | null): PasswordStrength {
  if (!password) return 'none';
  if (!isAcceptable(password, username)) return 'weak';
  if (password.length >= 20) return 'strong';
  if (password.length >= 16 || characterClasses(password) === 4) return 'good';
  return 'fair';
}

/**
 * i18n key for a backend `fieldErrors[].code`, so a server rejection speaks
 * the same words as the live checker. Returns `null` for a code this file
 * does not know, which the caller should render as its generic failure.
 */
export function messageKeyFor(code: string): string | null {
  switch (code) {
    case 'PASSWORD_REQUIRED': return 'password.error.required';
    case 'PASSWORD_TOO_SHORT': return 'password.error.tooShort';
    case 'PASSWORD_TOO_LONG': return 'password.error.tooLong';
    case 'PASSWORD_TOO_SIMPLE': return 'password.error.tooSimple';
    case 'PASSWORD_TOO_COMMON': return 'password.error.tooCommon';
    case 'PASSWORD_CONTAINS_USERNAME': return 'password.error.containsUsername';
    default: return null;
  }
}
