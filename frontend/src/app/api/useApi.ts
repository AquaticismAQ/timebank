import { useState, useEffect, useCallback, useRef } from 'react';
import { useSearchParams } from 'react-router';
import type { PageResult } from './http';
import { ApiError, mapApiError, buildListQuery, type ErrorUiMapping } from './http';
import type { ListParams } from '../lib/pagination';

interface ApiState<T> {
  data: T | null;
  loading: boolean;
  error: string | null;
  refetch: () => void;
}

export function useApi<T>(fn: () => Promise<T>, deps: unknown[] = []): ApiState<T> {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [tick, setTick] = useState(0);
  const fnRef = useRef(fn);
  fnRef.current = fn;

  const refetch = useCallback(() => setTick(t => t + 1), []);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    fnRef.current()
      .then(result => { if (!cancelled) { setData(result); setLoading(false); } })
      .catch(err => { if (!cancelled) { setError(err.message || '请求失败'); setLoading(false); } });
    return () => { cancelled = true; };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick]);

  return { data, loading, error, refetch };
}

// ── I1.2/I1.3/I1.4: paginated list fetching, server-driven filter/search/sort ──
// Every swapped list screen uses this instead of a raw useState<T[]> +
// client-side .filter()/.sort() — the params shape (ListParams) is what
// both the mock and real client branches accept identically (lib/pagination.ts).

export interface PagedApiState<T> {
  items: T[];
  page: { number: number; size: number; totalItems: number; totalPages: number } | null;
  loading: boolean;
  error: ErrorUiMapping | null;
  params: ListParams;
  setPage: (n: number) => void;
  setPageSize: (n: number) => void;
  setSearch: (s: string) => void;
  setFilter: (key: string, value: string | undefined) => void;
  setSort: (s: string | undefined) => void;
  refetch: () => void;
}

export function usePagedApi<T>(
  fn: (params: ListParams) => Promise<PageResult<T>>,
  initialParams: ListParams = {}
): PagedApiState<T> {
  const [params, setParams] = useState<ListParams>({ page: 1, pageSize: 20, ...initialParams });
  const [items, setItems] = useState<T[]>([]);
  const [page, setPageMeta] = useState<PagedApiState<T>['page']>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<ErrorUiMapping | null>(null);
  const [tick, setTick] = useState(0);
  const fnRef = useRef(fn);
  fnRef.current = fn;
  const paramsKey = JSON.stringify(params);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    fnRef.current(params)
      .then(result => {
        if (cancelled) return;
        setItems(result.data);
        setPageMeta(result.page);
        setLoading(false);
      })
      .catch(err => {
        if (cancelled) return;
        setError(err instanceof ApiError ? mapApiError(err) : { kind: 'unknown', message: 'Request failed.' });
        setItems([]);
        setPageMeta(null);
        setLoading(false);
      });
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [paramsKey, tick]);

  const setPage = useCallback((n: number) => setParams(p => ({ ...p, page: n })), []);
  // §2.2: changing page size resets to page 1, same as filter/search.
  const setPageSize = useCallback((n: number) => setParams(p => ({ ...p, pageSize: n, page: 1 })), []);
  const setSearch = useCallback((s: string) => setParams(p => ({ ...p, search: s || undefined, page: 1 })), []);
  const setFilter = useCallback(
    (key: string, value: string | undefined) => setParams(p => ({ ...p, filter: { ...p.filter, [key]: value }, page: 1 })),
    []
  );
  const setSort = useCallback((s: string | undefined) => setParams(p => ({ ...p, sort: s })), []);
  const refetch = useCallback(() => setTick(t => t + 1), []);

  return { items, page, loading, error, params, setPage, setPageSize, setSearch, setFilter, setSort, refetch };
}

// ── Docs/optimization/04-DataViewOptimization.md §2.1 (D1): the shared URL-sync
// wrapper every migrated list page uses, so filter/search/sort/page state survives
// a refresh, back/forward, and a copied link — the first such mechanism in this
// codebase (there was no prior art to extend). Reuses `buildListQuery`'s exact
// `page[number]`/`page[size]`/`sort`/`search`/`filter[x]` shape for the URL query
// string too, so it's the same dialect as the wire request, not a second one.

const FILTER_KEY = /^filter\[(.+)\]$/;

function paramsFromSearchParams(sp: URLSearchParams): ListParams {
  const params: ListParams = {};
  const page = sp.get('page[number]');
  const pageSize = sp.get('page[size]');
  const sort = sp.get('sort');
  const search = sp.get('search');
  if (page) params.page = Number(page);
  if (pageSize) params.pageSize = Number(pageSize);
  if (sort) params.sort = sort;
  if (search) params.search = search;
  const filter: Record<string, string | undefined> = {};
  for (const key of sp.keys()) {
    const match = key.match(FILTER_KEY);
    if (match) filter[match[1]] = sp.get(key) ?? undefined;
  }
  if (Object.keys(filter).length > 0) params.filter = filter;
  return params;
}

function searchParamsFromParams(params: ListParams): URLSearchParams {
  const flat = buildListQuery(params);
  const sp = new URLSearchParams();
  for (const [key, value] of Object.entries(flat)) {
    if (value !== undefined && value !== '') sp.set(key, String(value));
  }
  return sp;
}

export function useUrlSyncedPagedApi<T>(
  fn: (params: ListParams) => Promise<PageResult<T>>,
  initialParams: ListParams = {}
): PagedApiState<T> {
  const [searchParams, setSearchParams] = useSearchParams();
  // URL is read once, on mount, to seed initial state (refresh/copied-link/
  // back-forward all land here) — after that, state drives the URL one-way
  // (the effect below), never the reverse, so typing in a debounced search
  // box can't fight a URL->state->URL loop.
  const initialRef = useRef<ListParams>({ ...initialParams, ...paramsFromSearchParams(searchParams) });
  const paged = usePagedApi(fn, initialRef.current);
  const paramsKey = JSON.stringify(paged.params);

  useEffect(() => {
    setSearchParams(searchParamsFromParams(paged.params), { replace: true });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [paramsKey]);

  return paged;
}
