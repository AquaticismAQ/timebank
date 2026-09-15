import { AlertTriangle } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { type ServiceRecord } from '../../api/types';
import { isoToLocalDateTime } from '../../lib/datetime';
import { RECORD_STATUS_LABELS } from '../../lib/statusLabels';
import { minutesToHours } from '../../lib/format';

/** "2026-09-15 21:00 – 23:00", or "2026-09-15 21:00 – 2026-09-16 01:00" when it runs past midnight. */
function formatWindow(date: string, startTime: string, endDate: string, endTime: string) {
  if (!date || !startTime || !endTime) return '';
  const tail = endDate && endDate !== date ? `${endDate} ${endTime}` : endTime;
  return `${date} ${startTime} – ${tail}`;
}

/**
 * Shown when a submit is refused because the record's time window overlaps
 * another of the same student's records (issue 069 — the backend's
 * `OVERLAPPING_RECORD` field error).
 *
 * This is a dialog rather than an inline field error because the wizard has
 * already created the draft by the time submit fails, so the student is
 * deciding what happens to an existing record, not correcting a field.
 *
 * `canDelete` is false when the blocked record is a RETURNED one being
 * resubmitted: `deleteDraft` only permits deleting a DRAFT (issue 059), so
 * there is genuinely one choice there, and offering a second button that
 * would fail is worse than showing one that works.
 *
 * `conflict` is resolved asynchronously by the caller and may be null if that
 * lookup failed — the block still stands, it just can't name the other record.
 */
export function OverlapBlockedDialog({ attempted, conflict, canDelete, onDelete, onKeepDraft, onClose }: {
  attempted: { date: string; startTime: string; endTime: string; endDate: string };
  conflict: ServiceRecord | null;
  canDelete: boolean;
  onDelete: () => void;
  onKeepDraft: () => void;
  onClose: () => void;
}) {
  const { t } = useTranslation();

  const conflictStart = conflict ? isoToLocalDateTime(conflict.startAt) : null;
  const conflictEnd = conflict ? isoToLocalDateTime(conflict.endAt) : null;
  const conflictStatus = conflict ? RECORD_STATUS_LABELS[conflict.status] : null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" onClick={onClose} />
      <div className="relative bg-card w-full max-w-sm rounded-2xl shadow-xl p-5 space-y-4 max-h-[90vh] overflow-y-auto">
        <div className="flex items-center gap-2">
          <AlertTriangle className="w-4 h-4 shrink-0 text-destructive" />
          <h3 className="text-sm font-medium text-foreground">{t('student.records.overlapTitle')}</h3>
        </div>
        <p className="text-xs text-muted-foreground">{t('student.records.overlapMessage')}</p>

        <div className="space-y-2">
          <div className="rounded-xl border border-border p-3 space-y-1">
            <div className="text-xs text-muted-foreground">{t('student.records.overlapThisRecord')}</div>
            <div className="text-xs text-foreground">
              {formatWindow(attempted.date, attempted.startTime, attempted.endDate, attempted.endTime)}
            </div>
          </div>

          {conflict && conflictStart && conflictEnd && (
            <div className="rounded-xl border border-destructive/40 bg-destructive/5 p-3 space-y-1">
              <div className="flex items-center justify-between gap-2">
                <span className="text-xs text-muted-foreground">{t('student.records.overlapExistingRecord')}</span>
                {conflictStatus && (
                  <span className="text-[10px] px-2 py-0.5 rounded-full bg-muted text-muted-foreground whitespace-nowrap">
                    {t(conflictStatus.label)}
                  </span>
                )}
              </div>
              <div className="text-xs text-foreground font-medium">
                {conflict.projectTitle || conflict.organizationName || conflict.recordNumber}
              </div>
              {conflict.recordNumber && (
                <div className="text-[11px] text-muted-foreground">{conflict.recordNumber}</div>
              )}
              <div className="text-xs text-foreground">
                {formatWindow(conflictStart.date, conflictStart.time, conflictEnd.date, conflictEnd.time)}
                {conflict.durationMinutes > 0 && ` (${minutesToHours(conflict.durationMinutes)} ${t('common.hours')})`}
              </div>
            </div>
          )}
        </div>

        <p className="text-xs text-muted-foreground">
          {canDelete ? t('student.records.overlapChoicesHint') : t('student.records.overlapReturnedHint')}
        </p>
        {canDelete ? (
          <div className="flex gap-3">
            <button type="button" onClick={onDelete}
              className="flex-1 py-2.5 rounded-xl border border-destructive/40 text-sm text-destructive hover:bg-destructive/10">
              {t('student.records.overlapDelete')}
            </button>
            <button type="button" onClick={onKeepDraft}
              className="flex-1 py-2.5 rounded-xl bg-[oklch(0.488_0.243_264.376)] text-white text-sm hover:opacity-90">
              {t('student.records.overlapKeepDraft')}
            </button>
          </div>
        ) : (
          <button type="button" onClick={onClose}
            className="w-full py-2.5 rounded-xl bg-[oklch(0.488_0.243_264.376)] text-white text-sm hover:opacity-90">
            {t('common.ok')}
          </button>
        )}
      </div>
    </div>
  );
}
