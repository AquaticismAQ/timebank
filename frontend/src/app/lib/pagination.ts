/** The query shape every list method accepts (APIContract.md §2.7) — mirrors what the wire actually carries. */
export interface ListParams {
  page?: number;
  pageSize?: number;
  sort?: string;
  filter?: Record<string, string | undefined>;
  search?: string;
}

/** Frontend statuses are camelCase ('underReview'); backend enums are UPPER_SNAKE ('UNDER_REVIEW') — one transform, used at every filter call site. */
export function toBackendEnum(value: string): string {
  return value.replace(/([a-z0-9])([A-Z])/g, '$1_$2').toUpperCase();
}

/** The reverse — every response mapper runs a resource's `status` (and similar enum fields) through this before handing it to a component. */
export function fromBackendEnum(value: string): string {
  return value.toLowerCase().replace(/_([a-z0-9])/g, (_, c) => c.toUpperCase());
}

/**
 * The single definition of list paging for the whole app.
 *
 * These used to live in two places — the default in `useApi.ts` and the selector
 * options as a `PageControls` prop — so changing the set meant finding every
 * screen and checking it agreed. Both now read from here, and `PageControls` no
 * longer accepts an override, so a screen *cannot* offer a different set. That
 * is the point: changing this file changes every list, with nothing per-screen
 * left to verify.
 */

/** Selector options, smallest first. `DEFAULT_PAGE_SIZE` must be one of these. */
export const PAGE_SIZE_OPTIONS = [10, 20, 50, 100];

/**
 * 10 exists so a list can be made to span pages on demand. A sort or filter
 * applied only to the loaded page looks perfectly correct until a page boundary
 * exists to cross (issue 095), and several lists are single-page at 20 —
 * Project Management among them. Dropping to 10 creates that boundary for the
 * mid-sized lists. Organizations, cases and proposals stay single-page even at
 * 10, which is a fixture problem rather than something this setting can fix.
 */
export const DEFAULT_PAGE_SIZE = 20;

/**
 * Mirrors `PageQueryArgumentResolver.MAX_PAGE_SIZE` on the backend, which
 * silently clamps rather than erroring — asking for 5000 returns 100 and the
 * caller is not told. Anything requesting more than this is not fetching
 * "everything"; it is fetching the first hundred and quietly dropping the rest.
 */
export const MAX_PAGE_SIZE = 100;
