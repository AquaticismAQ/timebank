import { useState, useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { X } from 'lucide-react';
import { toast } from 'sonner';
import { api } from '../../api/client';
import { ApiError, mapApiError, newIdempotencyKey } from '../../api/http';
import { type ServiceRecord } from '../../api/types';
import { minutesToHours } from '../../lib/format';

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
  type, record, onClose, onFiled,
}: {
  type: 'appeal' | 'supplement';
  record: ServiceRecord | null;
  onClose: () => void;
  onFiled: () => void;
}) {
  const { t } = useTranslation();
  const [reason, setReason] = useState('');
  const [saving, setSaving] = useState(false);
  const keyRef = useRef(newIdempotencyKey());

  const allowPicker = type === 'supplement' && !record;
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

  const handleSubmit = async () => {
    if (!reason.trim()) { toast.error(t(reasonRequiredKey)); return; }
    setSaving(true);
    try {
      const created = await api.cases.create({
        type,
        subjectType: subject ? 'serviceRecord' : undefined,
        subjectId: subject?.id,
        title: caseTitle(subject, reason),
        description: reason,
        reasonCode: type === 'appeal' ? 'STUDENT_APPEAL' : 'STUDENT_SUPPLEMENT',
      });
      await api.cases.submit(created.id, keyRef.current);
      keyRef.current = newIdempotencyKey();
      toast.success(t(submittedKey));
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
          <button onClick={onClose} className="text-muted-foreground hover:text-foreground"><X className="w-4 h-4" /></button>
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

        <div className="flex gap-3 mt-4">
          <button onClick={onClose} disabled={saving}
            className="flex-1 py-2.5 rounded-xl border border-border text-foreground hover:bg-accent text-sm">{t('common.cancel')}</button>
          <button onClick={handleSubmit} disabled={saving}
            className="flex-1 py-2.5 rounded-xl bg-primary text-primary-foreground hover:opacity-90 text-sm disabled:opacity-50">
            {saving ? t('common.saving', { defaultValue: 'Saving...' }) : t('common.submit')}
          </button>
        </div>
      </div>
    </div>
  );
}
