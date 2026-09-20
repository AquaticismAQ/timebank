import type { Flow } from '../harness/types.js';
import { preflight } from './phases/preflight.js';
import { createProject } from './phases/createProject.js';
import { registration } from './phases/registration.js';
import { serviceRecord } from './phases/serviceRecord.js';
import { approve } from './phases/approve.js';
import { closingView } from './phases/closingView.js';
import { returnBranch } from './phases/returnBranch.js';
import { rejectBranch } from './phases/rejectBranch.js';
import { auditTrail } from './phases/auditTrail.js';

/**
 * The project lifecycle, end to end: a teacher creates and publishes a project, a
 * student registers, the school confirms them, the student files a service record
 * with evidence, the school approves it, and the student's hours and time coins
 * go up.
 *
 * That last sentence is the school's question, so it is the flow's closing shot.
 */
export const projectWorkflow: Flow = {
  id: 'project-workflow',
  titleZh: '项目全流程演示',
  phases: [
    preflight,
    createProject,
    registration,
    serviceRecord,
    approve,
    closingView,
    returnBranch,
    rejectBranch,
    auditTrail,
  ],
};
