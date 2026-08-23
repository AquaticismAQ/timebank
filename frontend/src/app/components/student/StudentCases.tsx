import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ChevronDown, ChevronUp } from 'lucide-react';
import { api } from '../../api/client';
import { usePagedApi } from '../../api/useApi';
import { PageControls } from '../PageControls';
import { ListStateBanner } from '../ListStateBanner';
import { CASE_STATUS_LABELS, STATUS_COLOR_CLASSES } from '../../lib/statusLabels';
import { type Case, type TimelineEntry } from '../../api/types';

const TYPE_LABEL: Record<Case['type'], string> = {
  appeal: 'admin.cases.typeAppeal',
  supplement: 'admin.cases.typeSupplement',
  manualCorrection: 'admin.cases.typeManualCorrection',
};

/**
 * 06-StudentCaseFiling.md §2.4/§3 step 4 — read-only history of the student's own
 * filed cases. `GET /cases` is already scoped server-side to the caller's own
 * studentId (`CaseScopeFields.ownerIdPath`), so no filter is needed here; this is
 * a slimmed-down `CaseReview.tsx` (no decision actions — that stays admin-only).
 */
export function StudentCases() {
  const { t } = useTranslation();
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [timeline, setTimeline] = useState<TimelineEntry[]>([]);
  const [timelineLoading, setTimelineLoading] = useState(false);
  const { items, page, loading, error, setPage } = usePagedApi(api.cases.list, { sort: '-submittedAt' });

  const toggleExpand = (c: Case) => {
    if (expandedId === c.id) { setExpandedId(null); return; }
    setExpandedId(c.id);
    setTimeline([]);
    setTimelineLoading(true);
    api.cases.timeline(c.id)
      .then(entries => setTimeline(entries as TimelineEntry[]))
      .catch(() => setTimeline([]))
      .finally(() => setTimelineLoading(false));
  };

  return (
    <div className="space-y-2">
      <ListStateBanner error={error} empty={!loading && items.length === 0} emptyLabel={t('student.records.noCases')} />
      {items.map(c => {
        const isExpanded = expandedId === c.id;
        const status = CASE_STATUS_LABELS[c.status];
        return (
          <div key={c.id} className="bg-card border border-border rounded-xl overflow-hidden">
            <button className="w-full p-3 text-left" onClick={() => toggleExpand(c)}>
              <div className="flex items-start gap-3">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 flex-wrap mb-1">
                    <span className={`text-xs px-2 py-0.5 rounded-full whitespace-nowrap ${STATUS_COLOR_CLASSES[status.color]}`}>{t(status.label)}</span>
                    <span className="text-xs text-muted-foreground">{t(TYPE_LABEL[c.type])}</span>
                    {c.subjectLabel && <span className="text-xs text-muted-foreground">{c.subjectLabel}</span>}
                  </div>
                  <div className="text-sm text-foreground font-medium truncate">{c.title}</div>
                  {c.submittedAt && <div className="text-xs text-muted-foreground">{c.submittedAt.slice(0, 10)}</div>}
                </div>
                {isExpanded ? <ChevronUp className="w-4 h-4 text-muted-foreground shrink-0" /> : <ChevronDown className="w-4 h-4 text-muted-foreground shrink-0" />}
              </div>
            </button>
            {isExpanded && (
              <div className="px-3 pb-3 border-t border-border pt-2 space-y-2 text-xs">
                {c.description && <p className="text-foreground whitespace-pre-wrap">{c.description}</p>}
                <div className="space-y-1.5 pt-1">
                  {timelineLoading ? (
                    <div className="text-muted-foreground">{t('common.loading')}</div>
                  ) : timeline.length === 0 ? (
                    <div className="text-muted-foreground">{t('student.records.noHistory')}</div>
                  ) : (
                    timeline.map((entry, i) => (
                      <div key={i} className="flex gap-2">
                        <div className="w-1.5 h-1.5 rounded-full bg-border mt-1.5 shrink-0" />
                        <div className="flex-1 min-w-0">
                          <div className="text-muted-foreground">{entry.occurredAt.slice(0, 16).replace('T', ' ')}</div>
                          <div className="text-foreground">{entry.description}</div>
                        </div>
                      </div>
                    ))
                  )}
                </div>
              </div>
            )}
          </div>
        );
      })}
      <PageControls page={page} onPageChange={setPage} />
    </div>
  );
}
