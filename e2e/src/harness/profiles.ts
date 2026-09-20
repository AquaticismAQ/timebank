import type { Gate } from './types.js';

export const ALL_GATES: Gate[] = ['core', 'branch:return', 'branch:reject', 'audit', 'tts', 'pace'];

/**
 * A profile is a set of gates, and that is the whole mechanism.
 *
 * One flow serves a live demonstration and a regression run because the phases
 * the two care about are different, not because the steps are. Two scripts would
 * drift, and the reject/return branches would rot in the one nobody runs before a
 * demo.
 */
export const PROFILES: Record<string, Gate[]> = {
  // In front of the school: one clean story, spoken, paced, nothing ugly.
  demo: ['core', 'tts', 'pace'],
  // Nobody watching: every branch, the audit trail, silent and fast.
  verify: ['core', 'branch:return', 'branch:reject', 'audit'],
  // Rehearsing the verification run with sound.
  full: ALL_GATES,
};

export function resolveGates(profile: string, add: string[], remove: string[]): Set<Gate> {
  const base = PROFILES[profile];
  if (!base) {
    throw new Error(`Unknown profile '${profile}'. Known: ${Object.keys(PROFILES).join(', ')}`);
  }
  const gates = new Set<Gate>(base);
  for (const g of add) {
    if (!ALL_GATES.includes(g as Gate)) throw new Error(`Unknown gate '${g}'. Known: ${ALL_GATES.join(', ')}`);
    gates.add(g as Gate);
  }
  for (const g of remove) {
    if (!ALL_GATES.includes(g as Gate)) throw new Error(`Unknown gate '${g}'. Known: ${ALL_GATES.join(', ')}`);
    gates.delete(g as Gate);
  }
  return gates;
}
