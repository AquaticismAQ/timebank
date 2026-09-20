import { useEffect } from 'react';
import { useBodyScrollLock } from './useBodyScrollLock';

/**
 * Closes the topmost open modal on `Escape` (issue 102 §5, reversed on the
 * user's decision 2026-09-18).
 *
 * Issue 102 removed the header `X` and made backdrops inert, because both fire
 * *by accident* and discard a part-filled form. `Escape` was removed with them
 * and is now back deliberately: the ARIA authoring practices expect a modal to
 * be dismissible from the keyboard, and unlike a stray click it is hard to
 * press by mistake. **Backdrop clicks and the `X` stay gone** — this is not a
 * general reversal of the rule.
 *
 * <h2>Why a stack rather than a listener per dialog</h2>
 *
 * Dialogs nest here: a delete confirmation opens over the form it belongs to.
 * If every mounted dialog listened on `window` independently, one `Escape`
 * would close the confirmation *and* the form underneath it — losing the very
 * work the confirmation was protecting, which is the exact failure issue 102
 * set out to prevent.
 *
 * So handlers register on a module-level stack and only the last one — the
 * visually topmost, since a nested dialog mounts after its parent — responds.
 * One `Escape`, one dialog.
 */
const stack: Array<() => void> = [];

let listening = false;

function onKeyDown(event: KeyboardEvent) {
  if (event.key !== 'Escape' || stack.length === 0) {
    return;
  }
  // `defaultPrevented` respects anything nearer the event's origin that has
  // already handled this key — a combobox closing its own popover, say, which
  // should not also close the dialog the combobox sits in.
  if (event.defaultPrevented) {
    return;
  }
  event.preventDefault();
  stack[stack.length - 1]();
}

/**
 * @param onClose called when Escape is pressed and this is the topmost dialog
 * @param enabled false while the dialog is closed. Passing the open flag here
 *        rather than calling the hook conditionally is what keeps the hook
 *        order stable, and it is why callers whose modal is inline JSX can hold
 *        several of these in one component.
 */
export function useEscapeToClose(onClose: () => void, enabled = true) {
  // Registering here is how a modal announces that it is open, so this is the
  // one place that already knows about every dialog — and holding the page
  // still is the other thing being open has to mean (issue 126). A modal that
  // does not use this hook has to ask for the lock itself; three do.
  useBodyScrollLock(enabled);

  useEffect(() => {
    if (!enabled) {
      return;
    }
    const handler = () => onClose();
    stack.push(handler);
    if (!listening) {
      // Capture phase: a dialog must still close when focus sits in an input
      // that stops propagation of its own key events.
      window.addEventListener('keydown', onKeyDown, true);
      listening = true;
    }
    return () => {
      const at = stack.lastIndexOf(handler);
      if (at !== -1) {
        stack.splice(at, 1);
      }
      if (stack.length === 0 && listening) {
        window.removeEventListener('keydown', onKeyDown, true);
        listening = false;
      }
    };
  }, [onClose, enabled]);
}
