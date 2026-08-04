// I0.3/I0.4/I0.5/I0.6: the HTTP transport layer the mock client is designed
// to be replaced by (APIContract.md §12.1, Docs/implementation/10-Integration
// -I0-ContractPrep.md). Base URL is always the relative `/api/v1` — same-
// origin in production, and via the dev proxy locally (frontend/README.md,
// I0.12) — so there is nothing to configure for CORS on either side.
import i18n from '../../i18n';
import type { ApiErrorBody, ApiFieldError } from './types';

const BASE_URL = '/api/v1';

export class ApiError extends Error {
  readonly code: string;
  readonly fieldErrors?: ApiFieldError[];
  readonly requestId: string;

  constructor(body: ApiErrorBody) {
    super(body.message);
    this.code = body.code;
    this.fieldErrors = body.fieldErrors;
    this.requestId = body.requestId;
  }
}

function readCookie(name: string): string | undefined {
  const match = document.cookie.match(new RegExp(`(?:^|; )${name}=([^;]*)`));
  return match ? decodeURIComponent(match[1]) : undefined;
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PATCH' | 'DELETE';
  body?: unknown;
  idempotencyKey?: string;
  query?: Record<string, string | number | undefined>;
}

function buildUrl(path: string, query?: RequestOptions['query']): string {
  const url = new URL(BASE_URL + path, window.location.origin);
  if (query) {
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined) url.searchParams.set(key, String(value));
    }
  }
  return url.pathname + url.search;
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const method = options.method ?? 'GET';
  const headers: Record<string, string> = {
    'Accept-Language': i18n.language === 'zh' ? 'zh-CN' : 'en',
  };
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  if (options.idempotencyKey) headers['Idempotency-Key'] = options.idempotencyKey;
  if (method !== 'GET') {
    const csrfToken = readCookie('XSRF-TOKEN');
    if (csrfToken) headers['X-XSRF-TOKEN'] = csrfToken;
  }

  const response = await fetch(buildUrl(path, options.query), {
    method,
    credentials: 'include',
    headers,
    body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
  });

  const text = await response.text();
  const parsed = text ? JSON.parse(text) : {};

  if (!response.ok || parsed.error) {
    throw new ApiError(parsed.error ?? {
      code: 'INTERNAL_ERROR',
      message: response.statusText || 'Request failed.',
      requestId: '',
    });
  }

  return parsed.data as T;
}

export interface PageResult<T> {
  data: T[];
  page: { number: number; size: number; totalItems: number; totalPages: number };
}

async function requestPage<T>(path: string, options: RequestOptions = {}): Promise<PageResult<T>> {
  const method = options.method ?? 'GET';
  const headers: Record<string, string> = {
    'Accept-Language': i18n.language === 'zh' ? 'zh-CN' : 'en',
  };
  const response = await fetch(buildUrl(path, options.query), {
    method,
    credentials: 'include',
    headers,
  });
  const text = await response.text();
  const parsed = text ? JSON.parse(text) : {};
  if (!response.ok || parsed.error) {
    throw new ApiError(parsed.error ?? { code: 'INTERNAL_ERROR', message: response.statusText, requestId: '' });
  }
  return { data: parsed.data, page: parsed.page };
}

export const http = {
  get: <T>(path: string, query?: RequestOptions['query']) => request<T>(path, { method: 'GET', query }),
  getPage: <T>(path: string, query?: RequestOptions['query']) => requestPage<T>(path, { method: 'GET', query }),
  post: <T>(path: string, body?: unknown, idempotencyKey?: string) =>
    request<T>(path, { method: 'POST', body, idempotencyKey }),
  patch: <T>(path: string, body?: unknown) => request<T>(path, { method: 'PATCH', body }),
  delete: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
};

// ── I0.5: error-to-UI mapping (APIContract.md §2.6) ─────────────────────────
// Never render or log a raw error object — always go through this.

export type ErrorUiKind = 'field' | 'conflict' | 'notAvailable' | 'rateLimited' | 'unknown';

export interface ErrorUiMapping {
  kind: ErrorUiKind;
  message: string;
  fieldErrors?: ApiFieldError[];
}

export function mapApiError(err: ApiError): ErrorUiMapping {
  switch (err.code) {
    case 'VALIDATION_FAILED':
      return { kind: 'field', message: err.message, fieldErrors: err.fieldErrors };
    case 'STATE_CONFLICT':
      return { kind: 'conflict', message: err.message };
    case 'FORBIDDEN':
    case 'RESOURCE_NOT_FOUND':
      return { kind: 'notAvailable', message: err.message };
    case 'RATE_LIMITED':
      return { kind: 'rateLimited', message: err.message };
    default:
      return { kind: 'unknown', message: err.message };
  }
}

// ── I0.6: idempotency + concurrency helpers ─────────────────────────────────
// Concurrency is a body field, never `If-Match` (APIContract.md §2.8 — the
// real backend only ever reads `version`/`recordVersion`/`proposalVersion`
// from the request body; confirmed against ConcurrencyControl in B3/B4/B5).

export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}

/** Merges the resource's current `version` into a request body under `field` (default `'version'`). */
export function withVersion<T extends object>(body: T, version: number, field = 'version'): T & Record<string, number> {
  return { ...body, [field]: version };
}
