// I0.7: per-domain mock/real toggle (APIContract.md §13, Docs/implementation
// /10-Integration-I0-ContractPrep.md I0.7). Default is empty — every domain
// stays mock until explicitly flipped, so I0 changes no screen's behaviour.
// Set VITE_API_REAL_DOMAINS in .env.local (see frontend/README.md).

const REAL_DOMAINS = new Set(
  (import.meta.env.VITE_API_REAL_DOMAINS ?? '')
    .split(',')
    .map((d: string) => d.trim())
    .filter(Boolean)
);

export function isRealDomain(domain: string): boolean {
  return REAL_DOMAINS.has(domain);
}
