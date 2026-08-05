// I0.7: per-domain mock/real toggle (APIContract.md §13, Docs/implementation
// /10-Integration-I0-ContractPrep.md I0.7). Default is empty — every domain
// stays mock until explicitly flipped, so a plain `pnpm dev` (no
// .env.local, no dev-proxy target) keeps working standalone. I1
// (Docs/implementation/11-Integration-I1-ReadPath.md) proved all 8
// read-domains work end to end against the real backend — see
// 00-Status.md §22 — but left this default alone rather than breaking the
// zero-config dev workflow; set VITE_API_REAL_DOMAINS in .env.local (see
// frontend/README.md) to opt into real mode.

const REAL_DOMAINS = new Set(
  (import.meta.env.VITE_API_REAL_DOMAINS ?? '')
    .split(',')
    .map((d: string) => d.trim())
    .filter(Boolean)
);

export function isRealDomain(domain: string): boolean {
  return REAL_DOMAINS.has(domain);
}
