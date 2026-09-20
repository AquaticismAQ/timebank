/**
 * One id per run, stamped into every title, description, artifact directory and
 * narration filename, so everything a run created can be found afterwards with a
 * single search. Residue is not cleaned up (README), so being able to identify it
 * is the whole courtesy.
 *
 * Sortable-first (`YYMMDD-HHMMSS`) so run directories list in order, with four
 * random characters because two runs can start in the same second.
 */
export function newRunId(now = new Date()): string {
  const p = (n: number) => String(n).padStart(2, '0');
  const stamp = `${p(now.getFullYear() % 100)}${p(now.getMonth() + 1)}${p(now.getDate())}`
    + `-${p(now.getHours())}${p(now.getMinutes())}${p(now.getSeconds())}`;
  const suffix = Math.random().toString(36).slice(2, 6).toUpperCase();
  return `${stamp}-${suffix}`;
}
