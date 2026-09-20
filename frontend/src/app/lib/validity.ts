import type { TFunction } from 'i18next';

/**
 * Constraint validation, in the language of the page.
 *
 * The browser writes its own validation messages ("Please fill out this
 * field.") in the *browser's* UI language, not the document's. Nothing about
 * `lang`, `Accept-Language` or i18next reaches them, so a Chinese form in an
 * English Chrome tells a student off in English (issue 118). The one hook the
 * platform offers is `setCustomValidity`, which replaces the message outright.
 *
 * So the rule here is: every message the user can see comes from the bundles,
 * and nothing falls through to the native text.
 */

type Validatable = HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement;

const isValidatable = (el: Element): el is Validatable =>
  (el instanceof HTMLInputElement
    || el instanceof HTMLTextAreaElement
    || el instanceof HTMLSelectElement)
  // Excludes buttons, disabled and readonly controls — the ones the browser
  // itself would skip.
  && el.willValidate;

const controls = (form: HTMLFormElement): Validatable[] =>
  Array.from(form.elements).filter(isValidatable);

/**
 * Drops every message this module has set, so `checkValidity()` sees only the
 * real constraints again.
 *
 * A custom message is *itself* a validity failure for as long as it is set, and
 * only a real `input`/`change` event retracts it. A control whose value is
 * driven by React — `TimeInput`'s hidden proxy, say — never fires one, so
 * without this a single failed check would disable a "Next" button permanently.
 * Call it before any `checkValidity()` that gates something.
 */
export function clearCustomValidity(form: HTMLFormElement | null): void {
  if (form) for (const el of controls(form)) el.setCustomValidity('');
}

function messageFor(el: Validatable, t: TFunction): string {
  const v = el.validity;
  // The browser distinguishes these two, and so should we: "fill this out" is
  // wrong advice for a dropdown.
  if (v.valueMissing) {
    return el instanceof HTMLSelectElement
      ? t('common.validation.select')
      : t('common.validation.required');
  }
  if (v.rangeUnderflow) return t('common.validation.min', { min: (el as HTMLInputElement).min });
  if (v.rangeOverflow) return t('common.validation.max', { max: (el as HTMLInputElement).max });
  return t('common.validation.invalid');
}

/**
 * Like `form.reportValidity()`, but the bubble is in the page's language.
 *
 * Reports only the first offender, which is what the native call does too — it
 * focuses and speaks for one control at a time.
 */
export function reportLocalizedValidity(form: HTMLFormElement | null, t: TFunction): boolean {
  if (!form) return true;
  clearCustomValidity(form);
  const offender = controls(form).find(el => !el.checkValidity());
  if (!offender) return true;

  offender.setCustomValidity(messageFor(offender, t));
  offender.reportValidity();
  // Ours to set, ours to retract: see `clearCustomValidity` above.
  const clear = () => {
    offender.setCustomValidity('');
    offender.removeEventListener('input', clear);
    offender.removeEventListener('change', clear);
  };
  offender.addEventListener('input', clear);
  offender.addEventListener('change', clear);
  return false;
}
