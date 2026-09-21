import { useState, useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import {  } from 'lucide-react';
import { toast } from 'sonner';
import { api } from '../../api/client';
import { ApiError, mapApiError, newIdempotencyKey } from '../../api/http';
import { type Case, type ServiceRecord } from '../../api/types';
import { minutesToHours } from '../../lib/format';
import { useEscapeToClose } from '../../lib/useEscapeToClose';

/**
 * 06-StudentCaseFiling.md — shared create+submit modal for the two
 * student-initiated case types (Manual Correction stays staff-only per
 * §2.1). Appeal always has a pre-attached `record` (only ever opened from
 * a rejected record's row, §2.2); Supplement may be opened with a
 * pre-attached record (an approved/rejected row's own button) or with
 * none, in which case it offers an optional picker over the student's own
 * eligible records (§2.3's "both attached and unattached" decision).
 * Creation lands in DRAFT then is immediately submitted in one step, same
 * as the original inline appeal flow this replaces — no draft/submit
 * distinction is exposed to the student (§2.5 keeps the whole form simple).
 */
/**
 * The stored title must not carry the type label (issue 079). Every surface
 * that shows a case renders the type as its own translated element, so putting
 * `t('...supplement')` in the title both duplicated it and froze the filer's
 * UI language into the database — a case filed in Chinese then read as Chinese
 * to an English reviewer. What is left is the case's own content: the attached
 * record's name, or the student's own words. `case_record.title` is
 * VARCHAR(200).
 */
function caseTitle(subject: ServiceRecord | null, reason: string): string {
  // A self-initiated record can carry neither a project title nor an
  // organization name, so the reason is the fallback in that case too — the
  // backend requires a non-blank title.
  const subjectName = subject ? (subject.projectTitle || subject.organizationName || '').trim() : '';
  const raw = subjectName || reason.trim().split('\n')[0];
  return raw.length > 200 ? `${raw.slice(0, 199)}…` : raw;
}

export function CaseFilingModal({
  type, record, draft, allowDraft = false, onClose, onFiled,
}: {
  type: 'appeal' | 'supplement';
  record: ServiceRecord | null;
  /** An existing DRAFT/NEEDS_INFORMATION case being edited instead of a new filing (issue 081). */
  draft?: Case | null;
  /**
   * Whether "Save Draft" is offered (issue 143).
   *
   * Off by default, because the answer is about the *caller*: a saved draft has
   * to be visible somewhere the student lands afterwards. My Cases lists cases
   * and refetches, so it passes true. The records page does not, so a draft
   * saved from there was real, correct, and invisible — a button that reported
   * success and appeared to do nothing.
   */
  allowDraft?: boolean;
  onClose: () => void;
  onFiled: () => void;
}) {
  // Escape closes the topmost dialog (issue 102 §5).
  useEscapeToClose(onClose);

  const { t } = useTranslation();
  const [reason, setReason] = useState(draft?.description ?? '');
  const [saving, setSaving] = useState(false);
  const keyRef = useRef(newIdempotencyKey());

  const allowPicker = type === 'supplement' && !record && !draft;
  const [pickedRecordId, setPickedRecordId] = useState('');
  const [pickableRecords, setPickableRecords] = useState<ServiceRecord[]>([]);
  useEffect(() => {
    if (!allowPicker) return;
    // Only approved/rejected records are offered — a returned record already has its
    // own edit-and-resubmit path (06-StudentCaseFiling.md §2.3), and draft/submitted/
    // underReview records have no decision yet to supplement.
    api.serviceRecords.list({ pageSize: 100 })
      .then(res => setPickableRecords(res.data.filter(r => r.status === 'approved' || r.status === 'rejected')))
      .catch(() => setPickableRecords([]));
  }, [allowPicker]);

  const subject = record ?? pickableRecords.find(r => r.id === pickedRecordId) ?? null;
  const titleKey = type === 'appeal' ? 'student.records.appeal' : 'student.records.supplement';
  const reasonPlaceholderKey = type === 'appeal' ? 'student.records.appealReasonPlaceholder' : 'student.records.supplementReasonPlaceholder';
  const reasonRequiredKey = type === 'appeal' ? 'student.records.appealReasonRequired' : 'student.records.supplementReasonRequired';
  const submittedKey = type === 'appeal' ? 'student.records.appealSubmitted' : 'student.records.supplementSubmitted';

  /**
   * Issue 081: filing is create-then-submit, but the two halves are now
   * separable — "Save Draft" stops after the create, which the backend always
   * supported and only the UI never offered.
   */
  const save = async (thenSubmit: boolean) => {
    if (!reason.trim()) { toast.error(t(reasonRequiredKey)); return; }
    setSaving(true);
    try {
      const id = draft
        ? (await api.cases.update(draft.id, {
            title: caseTitle(subject, reason), description: reason, version: draft.version,
          })).id
        : (await api.cases.create({
            type,
            subjectType: subject ? 'serviceRecord' : undefined,
            subjectId: subject?.id,
            title: caseTitle(subject, reason),
            description: reason,
            reasonCode: type === 'appeal' ? 'STUDENT_APPEAL' : 'STUDENT_SUPPLEMENT',
          })).id;
      if (thenSubmit) {
        await api.cases.submit(id, keyRef.current);
        keyRef.current = newIdempotencyKey();
        toast.success(t(submittedKey));
      } else {
        toast.success(t('student.cases.draftSaved'));
      }
      onFiled();
    } catch (err) {
      if (err instanceof ApiError) toast.error(mapApiError(err).message);
      else toast.error(t('common.unknownError'));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50 p-4">
      {/* Backdrop is decorative only — no onClick (issue 020's convention: an accidental outside click must not discard a typed reason). */}
      <div className="bg-card border border-border rounded-2xl p-5 w-full max-w-md">
        <div className="flex items-center justify-between mb-3">
          <h3 className="text-foreground">{t(titleKey)}</h3>
        </div>

        {record && (
          <p className="text-xs text-muted-foreground mb-3">{record.projectTitle || record.organizationName} · {record.serviceDate}</p>
        )}

        {allowPicker && (
          <div className="mb-3">
            <label className="block text-xs text-muted-foreground mb-1.5">{t('student.records.supplementAttachRecord')}</label>
            <select value={pickedRecordId} onChange={e => setPickedRecordId(e.target.value)}
              className="w-full px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none">
              <option value="">{t('student.records.supplementNoRecordOption')}</option>
              {pickableRecords.map(r => (
                <option key={r.id} value={r.id}>{r.serviceDate} · {r.projectTitle || r.organizationName} · {minutesToHours(r.durationMinutes)}h</option>
              ))}
            </select>
          </div>
        )}

        <textarea rows={4} value={reason} onChange={e => setReason(e.target.value)}
          placeholder={t(reasonPlaceholderKey)} maxLength={4000}
          className="w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-primary/50 resize-none" />

        <div className="flex gap-2 mt-4">
          <button onClick={onClose} disabled={saving}
            className="flex-1 py-2.5 rounded-xl border border-border text-foreground hover:bg-accent text-sm">{t('common.cancel')}</button>
          {/* Only where the draft will be visible afterwards (issue 143).
              Opened from a service record, this dialog closes back onto a list
              of *records*; the case draft it just created is real but appears
              nowhere the student is looking, so the button reported success and
              seemed to do nothing. My Cases refetches and shows it. */}
          {allowDraft && (
            <button onClick={() => save(false)} disabled={saving}
              className="flex-1 py-2.5 rounded-xl border border-border text-foreground hover:bg-accent text-sm disabled:opacity-50">
              {t('student.cases.saveDraft')}
            </button>
          )}
          <button onClick={() => save(true)} disabled={saving}
            className="flex-1 py-2.5 rounded-xl bg-primary text-primary-foreground hover:opacity-90 text-sm disabled:opacity-50">
            {saving ? t('common.saving', { defaultValue: 'Saving...' }) : t('common.submit')}
          </button>
        </div>
      </div>
    </div>
  );
}
