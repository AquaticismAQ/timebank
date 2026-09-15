import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { X, AlertTriangle } from 'lucide-react';
import { api } from '../../api/client';
import { ApiError, mapApiError } from '../../api/http';
import { type ServiceRecord, type TimelineEntry } from '../../api/types';
import { RECORD_STATUS_LABELS } from '../../lib/statusLabels';
import { formatCoins, minutesToHours } from '../../lib/format';
import { formatLocalInstant } from '../../lib/datetime';
import { EvidencePreview } from '../shared/EvidencePreview';

const STATUS_COLORS: Record<string, string> = {
  gray: 'bg-muted text-muted-foreground',
  blue: 'bg-blue-100 dark:bg-blue-900/30 text-blue-700 dark:text-blue-400',
  yellow: 'bg-yellow-100 dark:bg-yellow-900/30 text-yellow-700 dark:text-yellow-400',
  green: 'bg-green-100 dark:bg-green-900/30 text-green-700 dark:text-green-400',
  orange: 'bg-orange-100 dark:bg-orange-900/30 text-orange-700 dark:text-orange-400',
  red: 'bg-red-100 dark:bg-red-900/30 text-red-700 dark:text-red-400',
  purple: 'bg-purple-100 dark:bg-purple-900/30 text-purple-700 dark:text-purple-400',
};

function Field({ label, value }: { label: string; value?: string | number | null }) {
  return (
    <div>
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="text-sm text-foreground whitespace-pre-wrap break-words">{value || '—'}</div>
    </div>
  );
}

/**
 * Read-only detail view of one of the student's own service records (issue
 * 072) — the counterpart to the Admin Portal's audit detail workbench, for
 * every status except `draft` (a draft's Edit button opens the full wizard,
 * which shows strictly more).
 *
 * Fetches by id rather than taking the list row: `list()`'s mapping always
 * returns `evidenceFiles: []` (no denormalised name/size without a per-file
 * `GET /files/{id}` — see `fromServiceRecordDto` in client.ts), so a detail
 * view rendered straight off the row would show no evidence at all.
 */
export function ServiceRecordDetail({ recordId, onClose }: { recordId: string; onClose: () => void }) {
  const { t } = useTranslation();
  const [record, setRecord] = useState<ServiceRecord | null>(null);
  const [timeline, setTimeline] = useState<TimelineEntry[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setRecord(null);
    setError(null);
    api.serviceRecords.get(recordId).then(
      r => { if (!cancelled) setRecord(r); },
      err => { if (!cancelled) setError(err instanceof ApiError ? mapApiError(err).message : t('common.unknownError')); },
    );
    // The record itself carries no `reviewedAt` — the DTO deliberately omits it,
    // because a record has no single "last reviewed" moment; the real,
    // append-only history lives on /timeline (the same source the Audit Center's
    // Review History tab uses). A student may read their own record's timeline.
    api.serviceRecords.timeline(recordId).then(
      entries => { if (!cancelled) setTimeline(entries); },
      () => { if (!cancelled) setTimeline([]); },
    );
    return () => { cancelled = true; };
  }, [recordId, t]);

  const statusInfo = record ? RECORD_STATUS_LABELS[record.status] : null;
  const sourceLabel = record
    ? t(`student.records.source_${record.sourceType}`, { defaultValue: record.sourceType })
    : '';

  return (
    <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4">
      <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" onClick={onClose} />
      <div className="relative bg-card w-full sm:max-w-2xl sm:rounded-2xl rounded-t-2xl shadow-xl flex flex-col max-h-[92vh]">
        <div className="flex items-center gap-3 px-5 py-4 border-b border-border shrink-0">
          <div className="flex-1 min-w-0">
            <h3 className="text-sm font-medium text-foreground">{t('student.records.detailTitle')}</h3>
            {record?.recordNumber && <p className="text-xs text-muted-foreground truncate">{record.recordNumber}</p>}
          </div>
          {statusInfo && (
            <span className={`text-xs px-2 py-0.5 rounded-full whitespace-nowrap ${STATUS_COLORS[statusInfo.color]}`}>
              {t(statusInfo.label)}
            </span>
          )}
          <button type="button" onClick={onClose} title={t('common.close')}
            className="p-1.5 rounded-lg text-muted-foreground hover:text-foreground hover:bg-accent transition-colors">
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto px-5 py-4 space-y-5">
          {error && <p className="text-sm text-destructive">{error}</p>}
          {!record && !error && <p className="text-sm text-muted-foreground">{t('common.loading')}</p>}

          {record && (
            <>
              {/* The reviewer's verdict is what the student came to read, so it
                  leads rather than sitting at the bottom of the field list. */}
              {(record.returnReason || record.rejectReason || record.reviewSummary) && (
                <div className={`rounded-xl border px-4 py-3 text-xs flex items-start gap-2 ${
                  record.status === 'approved'
                    ? 'border-green-200 dark:border-green-800 bg-green-50 dark:bg-green-900/20 text-green-800 dark:text-green-300'
                    : 'border-orange-200 dark:border-orange-800 bg-orange-50 dark:bg-orange-900/20 text-orange-800 dark:text-orange-300'
                }`}>
                  <AlertTriangle className="w-3.5 h-3.5 shrink-0 mt-0.5" />
                  <span>{record.returnReason || record.rejectReason || record.reviewSummary}</span>
                </div>
              )}

              <div className="grid grid-cols-2 gap-4">
                <Field label={t('student.records.serviceSource')} value={sourceLabel} />
                <Field label={t('student.records.projectOrOrg')} value={record.projectTitle || record.organizationName} />
                <Field label={t('student.records.serviceDate')} value={record.serviceDate} />
                <Field label={t('student.records.duration')} value={`${minutesToHours(record.durationMinutes)} ${t('common.hours')}`} />
                <Field label={t('student.records.startTime')} value={formatLocalInstant(record.startAt)} />
                <Field label={t('student.records.endTime')} value={formatLocalInstant(record.endAt)} />
                <Field label={t('student.records.serviceLocation')} value={record.location} />
                <Field label={t('student.records.category')} value={record.serviceCategoryName} />
                <Field label={t('student.records.supervisorName')}
                  value={[record.supervisorName, record.supervisorContact].filter(Boolean).join(' · ')} />
                <Field label={t('student.records.travelTime')}
                  value={record.includesTravelTime ? t('common.yes') : t('common.no')} />
                {record.includesTravelTime && record.travelTimeReason && (
                  <Field label={t('student.records.travelTimeReason')} value={record.travelTimeReason} />
                )}
                {record.creditedUnits != null && (
                  <Field label={t('student.records.creditedCoins')} value={`+${formatCoins(record.creditedUnits)}`} />
                )}
                {record.reviewerName && <Field label={t('student.records.reviewer')} value={record.reviewerName} />}
              </div>

              <Field label={t('student.records.description')} value={record.description} />

              <div>
                <div className="text-xs text-muted-foreground mb-2">{t('student.records.evidence')}</div>
                {record.evidenceFiles.length > 0
                  ? <EvidencePreview files={record.evidenceFiles.map(f => ({ id: f.id, name: f.name }))} />
                  : <p className="text-sm text-muted-foreground">—</p>}
              </div>

              <div>
                <div className="text-xs text-muted-foreground mb-2">{t('student.records.timeline')}</div>
                <div className="space-y-2">
                  {timeline.map((entry, i) => (
                    <div key={i} className="flex items-start gap-2 text-xs">
                      <div className="w-1.5 h-1.5 rounded-full bg-border shrink-0 mt-1.5" />
                      <div className="min-w-0 flex-1">
                        <div className="flex items-center gap-2">
                          <span className="text-foreground">
                            {/* `.` is i18next's nesting separator, so `review.approve` becomes `review_approve`. */}
                            {t(`student.records.timelineType.${entry.type.replace(/\./g, '_')}`, { defaultValue: entry.type })}
                          </span>
                          <span className="text-muted-foreground ml-auto shrink-0">{formatLocalInstant(entry.occurredAt)}</span>
                        </div>
                        {entry.description && <div className="text-muted-foreground break-words">{entry.description}</div>}
                      </div>
                    </div>
                  ))}
                  {timeline.length === 0 && <p className="text-xs text-muted-foreground">—</p>}
                </div>
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
