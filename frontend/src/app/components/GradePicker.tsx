import type { Grade } from '../api/types';

/**
 * Multi-select grade toggle grid — extracted from ProjectManagement's
 * eligible-grades picker (previously duplicated verbatim between its edit
 * and create forms) so account creation's teacher grade-bonding field
 * (issue 002) can reuse the same UI instead of a third copy.
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
  return (
    <div className="grid grid-cols-3 sm:grid-cols-6 gap-2">
      {grades.map(g => (
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
  );
}
