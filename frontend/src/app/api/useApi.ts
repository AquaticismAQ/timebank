import { useState, useEffect, useCallback, useRef } from 'react';
import type { PageResult } from './http';
import { ApiError, mapApiError, type ErrorUiMapping } from './http';
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
  const setSearch = useCallback((s: string) => setParams(p => ({ ...p, search: s || undefined, page: 1 })), []);
  const setFilter = useCallback(
    (key: string, value: string | undefined) => setParams(p => ({ ...p, filter: { ...p.filter, [key]: value }, page: 1 })),
    []
  );
  const setSort = useCallback((s: string | undefined) => setParams(p => ({ ...p, sort: s })), []);
  const refetch = useCallback(() => setTick(t => t + 1), []);

  return { items, page, loading, error, params, setPage, setSearch, setFilter, setSort, refetch };
}
