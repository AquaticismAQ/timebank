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
