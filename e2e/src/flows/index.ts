import type { Flow } from '../harness/types.js';
import { projectWorkflow } from './projectWorkflow.js';

/**
 * Every flow the harness knows about. Adding one is a file next to
 * `projectWorkflow.ts` and a line here — the runner, gates, narration,
 * screenshots and exit code are all shared.
 */
export const FLOWS: Flow[] = [projectWorkflow];

export function findFlow(id: string): Flow {
  const flow = FLOWS.find(f => f.id === id);
  if (!flow) throw new Error(`Unknown flow '${id}'. Known: ${FLOWS.map(f => f.id).join(', ')}`);
  return flow;
}
