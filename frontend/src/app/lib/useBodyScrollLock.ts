import { useEffect } from 'react';

/**
 * Stops the page behind a modal from scrolling (issue 126).
 *
 * The document is this app's scroller — `AppShell` is `min-h-screen`, so it
 * grows with its content and `<main>`'s `overflow-auto` never becomes a scroll
 * container. A wheel event over an open dialog therefore reaches the document
 * and moves the page underneath it: measured at 0 → 1500px with a dialog open.
 *
 * Two symptoms, one cause. A dialog with nothing to scroll passes the wheel
 * straight through; a dialog that *can* scroll passes it on once it reaches its
 * own end — scroll chaining. `overscroll-behavior: contain` fixes only the
 * second (there is no boundary to stop at in the first), so the page itself has
 * to be held still.
 *
 * <h2>Why a depth counter</h2>
 *
 * Dialogs nest — a delete confirmation over the form it belongs to, the same
 * case `useEscapeToClose` handles with its stack. Locking and unlocking
 * independently would have the inner dialog's unmount release the page while
 * the outer one is still open. So the page unlocks when the *last* modal
 * closes, and the original inline styles are restored rather than assumed:
 * `overflow: ''` would quietly discard a value someone else had set.
 */
let depth = 0;
let restore: (() => void) | null = null;

function lock(): void {
  if (depth++ > 0) {
    return;
  }
  const body = document.body;
  const previousOverflow = body.style.overflow;
  const previousPaddingRight = body.style.paddingRight;

  // Hiding the scrollbar reclaims its width, and everything on the page shifts
  // sideways as the dialog opens. Pad by exactly what was lost. Zero on
  // overlay-scrollbar platforms (macOS trackpads), where there is nothing to
  // compensate for.
  const scrollbarWidth = window.innerWidth - document.documentElement.clientWidth;

  body.style.overflow = 'hidden';
  if (scrollbarWidth > 0) {
    body.style.paddingRight = `${scrollbarWidth}px`;
  }

  restore = () => {
    body.style.overflow = previousOverflow;
    body.style.paddingRight = previousPaddingRight;
  };
}

function unlock(): void {
  depth = Math.max(0, depth - 1);
  if (depth > 0) {
    return;
  }
  restore?.();
  restore = null;
}

/**
 * @param enabled false while the dialog is closed. Passed as a flag rather than
 *        calling the hook conditionally, for the same reason
 *        {@link import('./useEscapeToClose').useEscapeToClose} takes one: hook
 *        order has to stay stable, and a component may hold several.
 */
export function useBodyScrollLock(enabled = true): void {
  useEffect(() => {
    if (!enabled) {
      return;
    }
    lock();
    return unlock;
  }, [enabled]);
}
