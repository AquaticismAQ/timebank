import type { Config } from './config.js';

/**
 * A tiny read-mostly API client, separate from the browser.
 *
 * It exists for two jobs the UI cannot do cheaply: finding the student's existing
 * service records before any window is chosen (`slots.ts`), and reading a balance
 * as a number rather than scraping formatted text. Everything a *user* does still
 * goes through the browser — that is the point of the suite.
 */
export class Api {
  private cookies = new Map<string, string>();

  private constructor(private readonly baseUrl: string) {}

  static async loginAs(config: Config, username: string, password: string): Promise<Api> {
    const api = new Api(config.baseUrl);
    // The session is cookie-based and CSRF-protected; the token is handed out by
    // /auth/csrf and echoed back in X-XSRF-TOKEN. Same dance the frontend does.
    await api.request('GET', '/auth/csrf');
    const res = await api.request('POST', '/auth/login', { username, password });
    if (!res.ok) {
      throw new Error(`Login failed for ${username}: HTTP ${res.status}`);
    }
    return api;
  }

  async get<T>(path: string): Promise<T> {
    const res = await this.request('GET', path);
    if (!res.ok) throw new Error(`GET ${path} failed: HTTP ${res.status}`);
    const body = await res.json() as { data: T };
    return body.data;
  }

  private async request(method: string, path: string, body?: unknown): Promise<Response> {
    const headers: Record<string, string> = { 'Accept-Language': 'zh-CN' };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (this.cookies.size > 0) {
      headers['Cookie'] = [...this.cookies].map(([k, v]) => `${k}=${v}`).join('; ');
    }
    const xsrf = this.cookies.get('XSRF-TOKEN');
    if (xsrf) headers['X-XSRF-TOKEN'] = xsrf;

    const res = await fetch(`${this.baseUrl}/api/v1${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      redirect: 'manual',
    });
    for (const raw of res.headers.getSetCookie()) {
      const [pair] = raw.split(';');
      const eq = pair.indexOf('=');
      if (eq > 0) this.cookies.set(pair.slice(0, eq).trim(), pair.slice(eq + 1).trim());
    }
    return res;
  }
}

export interface ServiceRecordRow {
  id: string;
  serviceDate: string;
  startAt: string;
  endAt: string;
  status: string;
}
