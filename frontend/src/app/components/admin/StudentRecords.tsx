import { useState } from 'react';
import { useBodyScrollLock } from '../../lib/useBodyScrollLock';
import { useTranslation } from 'react-i18next';
import { ChevronDown, ChevronUp } from 'lucide-react';
import { api } from '../../api/client';
import { usePagedApi } from '../../api/useApi';
import { PageControls } from '../PageControls';
import { ListStateBanner } from '../ListStateBanner';
import { EvidenceLink } from '../EvidenceLink';
import { RECORD_STATUS_LABELS, STATUS_COLOR_CLASSES } from '../../lib/statusLabels';
import { minutesToHours } from '../../lib/format';

/**
 * Admin "Records" button for a single student (Student Management) — read-only, no decision
 * actions (that workflow lives in Audit Center). Filters the same `/service-records` list every
 * other admin screen already uses via the new `filter[studentId]` param, rather than a dedicated
 * sub-resource endpoint (matches the precedent `api.accounts.get(studentId)` already set).
 */
// list-conventions: no-search — one student's records, in a modal reached from the roster.
// The search that matters happened on the way in; `/service-records` does support `search`,
// so add one here if this ever becomes a page rather than a look-up.

export function StudentRecords({ studentId, studentName, onClose }: { studentId: string; studentName: string; onClose: () => void }) {
  const { t } = useTranslation();
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const { items, page, loading, error, setPage } =
    usePagedApi(api.serviceRecords.list, { filter: { studentId }, sort: '-serviceDate' });

  // Mounted only while open, and it does not register with
  // `useEscapeToClose`, so the page lock is asked for here (issue 126).
  useBodyScrollLock();

  return (
    <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4">
      {/* Backdrop is decorative only — no onClick, matching the other review modals (an accidental outside click must not dismiss this). */}
      <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" />
      <div className="relative bg-card w-full sm:max-w-2xl sm:rounded-2xl rounded-t-2xl shadow-xl flex flex-col h-full sm:h-auto sm:max-h-[85vh]">
        <div className="flex items-center gap-3 px-5 py-4 border-b border-border shrink-0">
          <div className="flex-1 min-w-0">
            <h3 className="text-foreground leading-tight">{t('admin.students.records')}</h3>
            <p className="text-xs text-muted-foreground truncate">{studentName}</p>
          </div>
        </div>

        <div className="overflow-y-auto px-5 py-4 space-y-2 flex-1 overscroll-contain">
          <ListStateBanner error={error} empty={!loading && items.length === 0} emptyLabel={t('admin.students.noRecords')} />
          {items.map(record => {
            const isExpanded = expandedId === record.id;
            const status = RECORD_STATUS_LABELS[record.status];
            return (
              <div key={record.id} className="bg-card border border-border rounded-xl overflow-hidden">
                <button className="w-full p-3 text-left" onClick={() => setExpandedId(isExpanded ? null : record.id)}>
                  <div className="flex items-start gap-3">
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2 flex-wrap mb-1">
                        <span className={`text-xs px-2 py-0.5 rounded-full whitespace-nowrap ${STATUS_COLOR_CLASSES[status.color]}`}>{t(status.label)}</span>
                        {record.recordNumber && <span className="text-xs text-muted-foreground">{record.recordNumber}</span>}
                      </div>
                      <div className="text-sm text-foreground font-medium truncate">{record.projectTitle || record.organizationName || t('admin.students.records')}</div>
                      <div className="text-xs text-muted-foreground">{record.serviceDate} · {minutesToHours(record.durationMinutes)}h</div>
                    </div>
                    {isExpanded ? <ChevronUp className="w-4 h-4 text-muted-foreground shrink-0" /> : <ChevronDown className="w-4 h-4 text-muted-foreground shrink-0" />}
                  </div>
                </button>
                {isExpanded && (
                  <div className="px-3 pb-3 border-t border-border pt-2 space-y-1.5 text-xs">
                    <div className="flex justify-between"><span className="text-muted-foreground">{t('admin.students.recordLocation')}</span><span className="text-foreground text-right ml-2">{record.location || '—'}</span></div>
                    <div className="flex justify-between"><span className="text-muted-foreground">{t('admin.students.recordCategory')}</span><span className="text-foreground">{record.serviceCategoryName || '—'}</span></div>
                    <div className="flex justify-between"><span className="text-muted-foreground">{t('admin.students.recordSupervisor')}</span><span className="text-foreground text-right ml-2">{record.supervisorName || '—'}</span></div>
                    {record.description && (
                      <div>
                        <span className="text-muted-foreground">{t('admin.students.recordDescription')}</span>
                        <p className="text-foreground whitespace-pre-wrap mt-0.5">{record.description}</p>
                      </div>
                    )}
                    {record.reviewSummary && (
                      <div>
                        <span className="text-muted-foreground">{t('admin.students.recordReviewSummary')}</span>
                        <p className="text-foreground whitespace-pre-wrap mt-0.5">{record.reviewSummary}</p>
                      </div>
                    )}
                    {record.evidenceFiles.length > 0 && (
                      <div className="flex gap-2 flex-wrap pt-1">
                        {record.evidenceFiles.map(f => (
                          <span key={f.id} className="px-2 py-1 bg-muted rounded">
                            <EvidenceLink fileId={f.id} name={f.name} />
                          </span>
                        ))}
                      </div>
                    )}
                  </div>
                )}
              </div>
            );
          })}
          <PageControls page={page} onPageChange={setPage} />
        </div>

        <div className="px-5 py-4 border-t border-border shrink-0">
          <button onClick={onClose} className="w-full py-2.5 rounded-xl border border-border text-sm text-foreground hover:bg-accent transition-colors">
            {t('common.cancel')}
          </button>
        </div>
      </div>
    </div>
  );
}
