import type { PageResult } from '../api/http';

/** The query shape every list method accepts (APIContract.md §2.7) — mirrors what the wire actually carries. */
export interface ListParams {
  page?: number;
  pageSize?: number;
  sort?: string;
  filter?: Record<string, string | undefined>;
  search?: string;
}

const DEFAULT_PAGE_SIZE = 20;

/** Frontend statuses are camelCase ('underReview'); backend enums are UPPER_SNAKE ('UNDER_REVIEW') — one transform, used at every real-branch filter call site. */
export function toBackendEnum(value: string): string {
  return value.replace(/([a-z0-9])([A-Z])/g, '$1_$2').toUpperCase();
}

/** The reverse — every real-branch response mapper runs a resource's `status` (and similar enum fields) through this before handing it to a component. */
export function fromBackendEnum(value: string): string {
  return value.toLowerCase().replace(/_([a-z0-9])/g, (_, c) => c.toUpperCase());
}

/**
 * Mock-side stand-in for server-side pagination/filter/search/sort
 * (APIContract.md §2.7) — slices the same in-memory fixture array the mock
 * has always used, but through the same params shape the real backend
 * takes, so a screen written against `PageResult<T>` behaves identically
 * whether its domain's toggle (api/config.ts) points at mock or real.
 *
 * `filters` maps each supported `filter[field]` key to a predicate; `search`
 * matches if any of the given item fields contain the term (case-insensitive).
 */
export function paginateMock<T>(
  items: T[],
  params: ListParams | undefined,
  options: {
    filters?: Record<string, (item: T, value: string) => boolean>;
    searchFields?: (item: T) => (string | undefined)[];
    sort?: Record<string, (a: T, b: T) => number>;
  } = {}
): PageResult<T> {
  let result = items;

  if (params?.filter && options.filters) {
    for (const [key, value] of Object.entries(params.filter)) {
      if (value === undefined || value === '') continue;
      const predicate = options.filters[key];
      if (predicate) result = result.filter(item => predicate(item, value));
    }
  }

  if (params?.search && options.searchFields) {
    const term = params.search.toLowerCase();
    result = result.filter(item =>
      options.searchFields!(item).some(field => field?.toLowerCase().includes(term))
    );
  }

  if (params?.sort && options.sort) {
    const desc = params.sort.startsWith('-');
    const key = desc ? params.sort.slice(1) : params.sort;
    const comparator = options.sort[key];
    if (comparator) {
      result = [...result].sort((a, b) => (desc ? -1 : 1) * comparator(a, b));
    }
  }

  const pageSize = params?.pageSize ?? DEFAULT_PAGE_SIZE;
  const pageNumber = params?.page ?? 1;
  const totalItems = result.length;
  const totalPages = Math.max(1, Math.ceil(totalItems / pageSize));
  const start = (pageNumber - 1) * pageSize;
  const data = result.slice(start, start + pageSize);

  return { data, page: { number: pageNumber, size: pageSize, totalItems, totalPages } };
}
