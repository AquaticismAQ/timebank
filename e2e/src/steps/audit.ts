import type { Api } from '../harness/api.js';

export interface AuditRow { action: string; resourceId: string; createdAt: string }

/**
 * Every mutation must leave an audit entry, and the only way to know is to ask
 * for them — reading the code proves the call exists, not that the row lands.
 *
 * Queried through the API because the Audit Center's own filters are built for a
 * human hunting one event, not for asserting that a specific set of actions all
 * occurred within one run.
 */
export async function auditActionsSince(api: Api, since: Date): Promise<string[]> {
  // `page[size]`; a plain `size` is ignored and yields the default 20 rows,
  // which on a busy run covers well under a minute of history.
  const rows = await api.get<AuditRow[]>('/audit-logs?page%5Bsize%5D=300');
  return rows
    .filter(r => Date.parse(r.createdAt) >= since.getTime() - 60_000)
    .map(r => r.action);
}
