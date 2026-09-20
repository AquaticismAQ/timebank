import type { Grade } from '../api/types';
import { useDictionaries } from '../api/useDictionaries';
import { splitGradesIntoStages, stageRangeLabel } from '../lib/gradeStages';

/**
 * Multi-select grade toggle grid — extracted from ProjectManagement's
 * eligible-grades picker (previously duplicated verbatim between its edit
 * and create forms) so account creation's teacher grade-bonding field
 * (issue 002) can reuse the same UI instead of a third copy.
 *
 * **One block per school stage** (issue 114). Seven grades in one flat run read
 * as a single undifferentiated list, when they are really two stages a student
 * cannot cross — a grade-8 bond and a grade-9 bond mean different cohorts, and
 * the picker should show that before anyone clicks. The split comes from
 * `GET /lifecycle/config`'s exit levels, so it stays right if the school's stage
 * structure ever differs from 6–8 / 9–12.
 *
 * The stage boundaries are read from the shared dictionary cache rather than
 * taken as a prop, deliberately: a call site that forgets to pass them would
 * silently render the old flat list, and this component already has three of
 * them. If the config did not load, it falls back to one flat block — no
 * segmentation rather than a guessed one.
 */
export function GradePicker({
  grades,
  selected,
  onToggle,
  disabled = false,
}: {
  grades: Grade[];
  selected: string[];
  onToggle: (gradeId: string) => void;
  disabled?: boolean;
}) {
  const { lifecycle } = useDictionaries();
  const stages = splitGradesIntoStages(grades, lifecycle?.exitGradeLevels ?? []);

  return (
    <div className="space-y-2">
      {stages.map(stage => (
        // Each stage is its own grid, so the stages stay visually separate at
        // every width: on a phone a stage may wrap to two lines of its own, but
        // it never runs into the next one. Four columns at sm: and up puts each
        // stage on exactly one line for a 6–8 / 9–12 school.
        <div key={stage[0].id} className="grid grid-cols-3 sm:grid-cols-4 gap-2">
          {stage.map(g => (
            <button key={g.id} type="button" disabled={disabled} onClick={() => onToggle(g.id)}
              className={`w-full px-3 py-1.5 rounded-lg text-xs text-center border transition-colors ${
                selected.includes(g.id)
                  ? 'bg-[oklch(0.488_0.243_264.376)] text-white border-[oklch(0.488_0.243_264.376)]'
                  : 'border-border text-muted-foreground hover:bg-accent'
              } ${disabled ? 'opacity-50 cursor-not-allowed' : ''}`}>
              {g.name}
            </button>
          ))}
        </div>
      ))}
    </div>
  );
}

/**
 * The same stage split for a single-select `<select>`: one `<optgroup>` per
 * stage instead of one block per stage (issue 114).
 *
 * A dropdown cannot be laid out in two lines, so grouping is the equivalent —
 * and a `<select>` of seven grades has the same problem the grid did, reading as
 * one flat run when it is really two stages. Render inside a `<select>`,
 * alongside any placeholder option the caller wants first:
 *
 * ```tsx
 * <select ...>
 *   <option value="">{t('...selectGrade')}</option>
 *   <GradeOptions grades={grades} />
 * </select>
 * ```
 *
 * The group label is the bare range ("6–8"), not a stage name — see
 * `stageRangeLabel`. Falls back to ungrouped options when the config did not
 * load, so the control keeps working rather than losing its grades.
 */
export function GradeOptions({ grades }: { grades: Grade[] }) {
  const { lifecycle } = useDictionaries();
  const exitLevels = lifecycle?.exitGradeLevels ?? [];
  if (exitLevels.length === 0) {
    return <>{grades.map(g => <option key={g.id} value={g.id}>{g.name}</option>)}</>;
  }
  return (
    <>
      {splitGradesIntoStages(grades, exitLevels).map(stage => (
        <optgroup key={stage[0].id} label={stageRangeLabel(stage)}>
          {stage.map(g => <option key={g.id} value={g.id}>{g.name}</option>)}
        </optgroup>
      ))}
    </>
  );
}
