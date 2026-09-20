import type { Api } from '../harness/api.js';

export interface Progress {
  completedMinutes: number;
  underReviewMinutes: number;
  timeCoinBalance: number;
}

/**
 * Approved minutes and the time-coin balance, as numbers, in one call.
 *
 * Read through the API rather than scraped off the sidebar, because the
 * assertion that matters is arithmetic: the balance must rise *by the amount the
 * project recognises*, not merely change. Formatted text would have to be parsed
 * back into a number to say that, and a parse that quietly yields NaN turns a
 * real check into a passing one.
 *
 * `/students/{id}/progress` rather than `/accounts/{id}`: the account endpoint is
 * keyed by account id, not student id, and a student's own list is empty from
 * their session — progress carries both figures and is the student's own view.
 */
export async function readProgress(api: Api, studentId: string): Promise<Progress> {
  return api.get<Progress>(`/students/${studentId}/progress`);
}
