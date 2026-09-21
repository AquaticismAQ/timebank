import type { Grade } from '../api/types';

/**
 * Splits grades into the school's stages, using the exit grade levels published
 * by `GET /lifecycle/config` (issue 114).
 *
 * A stage is "everything up to and including its exit level", because that is
 * exactly what an exit level means: finishing it graduates the student rather
 * than promoting them (`Design/&#42;/14-AnnualRollover.md` §2.1). With
 * `[8, 12]` and grades 6…12 that gives `[[6,7,8], [9,10,11,12]]`.
 *
 * Derived rather than written down. A literal "6-8 / 9-12" in a component would
 * be a second source of truth for a structure only the rollover really owns, and
 * would be wrong on the day a school's stages differ — the same reason the levels
 * are fetched at all instead of hardcoded.
 *
 * @param exitLevels the exit grade levels; order does not matter, they are sorted
 * @returns one array per stage, in ascending order, with empty stages dropped.
 *   A single group containing everything when `exitLevels` is empty — no
 *   segmentation rather than a guessed one, since a split that disagrees with
 *   the rollover is worse than no split.
 */
export function splitGradesIntoStages(grades: Grade[], exitLevels: number[]): Grade[][] {
  const ordered = [...grades].sort((a, b) => a.sequence - b.sequence);
  if (exitLevels.length === 0) {
    return ordered.length > 0 ? [ordered] : [];
  }
  const bounds = [...exitLevels].sort((a, b) => a - b);
  const stages: Grade[][] = bounds.map(() => []);
  // Anything above the last exit level has no stage to belong to — it should not
  // exist, but it is put in the final group rather than silently dropped, since
  // a grade missing from a picker is invisible while a grade in the wrong row is
  // merely odd.
  const overflow: Grade[] = [];
  for (const grade of ordered) {
    const index = bounds.findIndex(bound => grade.sequence <= bound);
    if (index === -1) {
      overflow.push(grade);
    } else {
      stages[index].push(grade);
    }
  }
  if (overflow.length > 0) {
    stages[stages.length - 1].push(...overflow);
  }
  return stages.filter(stage => stage.length > 0);
}

/**
 * A stage's range as a bare number span — "6–8".
 *
 * Deliberately not "Middle School"/"High School": the API publishes boundaries,
 * not names, and inventing a name here would put back the hardcoded structure
 * this is meant to remove. (`admin.system.rules.groupRequirements*` does carry
 * those names, but they are labels on fixed rule groups, not on a derived split.)
 */
export function stageRangeLabel(stage: Grade[]): string {
  if (stage.length === 0) return '';
  const first = stage[0].sequence;
  const last = stage[stage.length - 1].sequence;
  return first === last ? `${first}` : `${first}–${last}`;
}
