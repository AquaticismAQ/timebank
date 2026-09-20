import i18n from '../../i18n';
import type { ApiError } from './http';

/**
 * Turns a server error into a sentence in the reader's language.
 *
 * The backend authors no localized copy — it has no `MessageSource`, and its
 * i18n is "resolve the request's language, then pick the matching per-row
 * column" (`iam.application.SlipCopy` records that decision and why). So every
 * `ApiException` message is English, and until now every one of them reached
 * the user verbatim through `handleMutationError` (issue 121).
 *
 * The division of labour is the one `OVERLAPPING_RECORD` already established:
 * **the server sends facts and codes, the client sends words.** This module is
 * where the words live.
 *
 * Three tiers, most specific first:
 *
 * 1. A coded `fieldErrors[]` entry we have wording for — `errors.field.<CODE>`.
 *    The entry's `message` is data, not prose: a count, an id, a name. When it
 *    is a number it is passed as `count` so the sentence can place it.
 * 2. The envelope's own `code` — `errors.code.<CODE>`. Generic, but correct and
 *    in the right language for every error, including the hundred-odd
 *    `stateConflict`/`invalidRequest` sites that have no code of their own yet.
 * 3. The server's English sentence, as a last resort. Showing English beats
 *    showing nothing, and this tier shrinks as codes are added.
 *
 * Uses the i18n singleton rather than a `t` passed in, matching `http.ts` — a
 * `t` parameter would have to be threaded through all 23 `handleMutationError`
 * call sites to say the same thing.
 */

/** `''` when the bundle has no such key, which is how each tier tests itself. */
function translated(key: string, params?: Record<string, unknown>): string {
  return i18n.t(key, { defaultValue: '', ...params }) as string;
}

/**
 * A field error's own wording, for showing under the input it belongs to.
 * Falls back to the server's message, which is at least about the right field.
 */
export function fieldErrorMessage(code: string, serverMessage: string): string {
  return translated(`errors.field.${code}`, numericParams(serverMessage)) || serverMessage;
}

/**
 * Codes that describe *a field*, not *what went wrong*.
 *
 * "This cannot be empty" is the right thing to print under an input and a
 * useless thing to put in a toast, because the toast cannot say which input.
 * So these are used inline only, and a request that fails on one of them gets
 * the general "check the fields marked below" sentence instead. A code like
 * `EVIDENCE_REQUIRED` or `OVERLAPPING_RECORD` says enough on its own and is not
 * in this set.
 */
const FIELD_ONLY_CODES = new Set([
  'NOTBLANK', 'NOTNULL', 'NOTEMPTY', 'SIZE', 'POSITIVE', 'POSITIVEORZERO',
  'MIN', 'MAX', 'EMAIL', 'PATTERN', 'REQUIRED', 'INVALID', 'NOT_FOUND',
]);

/** The sentence to put in a toast for a whole failed request. */
export function apiErrorMessage(err: ApiError): string {
  for (const fe of err.fieldErrors ?? []) {
    if (FIELD_ONLY_CODES.has(fe.code)) continue;
    const specific = translated(`errors.field.${fe.code}`, numericParams(fe.message));
    if (specific) return specific;
  }
  return translated(`errors.code.${err.code}`) || err.message;
}

/**
 * A field error whose `message` is purely a number is carrying a count — the
 * server has no business formatting "3 students" in a language it does not
 * know. Anything else is passed as `{{value}}` so a message can still quote it.
 */
function numericParams(message: string): Record<string, unknown> {
  const n = Number(message);
  return message.trim() !== '' && Number.isFinite(n)
    ? { count: n, value: message }
    : { value: message };
}
