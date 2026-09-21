import { createContext, useContext } from 'react';

/**
 * Lets a page tell the sidebar that one of its badge counts just changed
 * (issue 154).
 *
 * The counts live in the layout, which is the route *parent*: it does not
 * remount when you move between its children, so a fetch on mount is a fetch
 * once per sign-in. Reading a notification then left the badge showing the old
 * number until the browser was reloaded — the number was not stale for a
 * moment, it was stale for the session, and a count that disagrees with the
 * page it points at is worse than no count.
 *
 * **Why a refresh signal rather than a shared number.** The page that changed
 * something does not know the new count: marking one notification read does not
 * mean "minus one" if another arrived, and approving a record can clear two
 * queue entries. Only the server knows, so the page says *that a thing changed*
 * and the layout asks. Decrementing locally would drift, and drift in a badge is
 * invisible until someone counts.
 */
export interface BadgeCounts {
  /** Re-reads every badge count this portal shows. Safe to call from a `.then`. */
  refresh: () => void;
}

/**
 * Defaults to doing nothing, so a page rendered outside a layout — a test, a
 * future embed — works rather than throwing over a decoration.
 */
const BadgeCountsContext = createContext<BadgeCounts>({ refresh: () => {} });

export const BadgeCountsProvider = BadgeCountsContext.Provider;

export function useBadgeCounts(): BadgeCounts {
  return useContext(BadgeCountsContext);
}
