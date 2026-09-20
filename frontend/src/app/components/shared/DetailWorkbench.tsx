import {  } from 'lucide-react';
import { useEscapeToClose } from '../../lib/useEscapeToClose';

export interface DetailWorkbenchTab {
  key: string;
  label: string;
  content: React.ReactNode;
}

/**
 * Docs/optimization/04-DataViewOptimization.md §2.3 (D4/D5)'s shared detail-workbench
 * chrome — Audit Center / Case Review / Proposal Review each supply their own tabs and
 * bottom action bar content; this only owns the responsive shell (full-screen sheet on
 * mobile, large centered modal on desktop — same `sm:` breakpoint and header/body/footer
 * skeleton `ProjectManagement.tsx`'s edit modal already uses, just with a tab row added)
 * and the open/close/tab-switch mechanics. Callers control `open`/`activeTab` themselves
 * rather than this component owning that state, since a "view details" trigger and a
 * clicked row/card (D5's two entry points) both need to set the same state from outside.
 */
export function DetailWorkbench({
  open, onClose, title, subtitle, tabs, activeTab, onTabChange, footer,
}: {
  open: boolean;
  onClose: () => void;
  title: React.ReactNode;
  subtitle?: React.ReactNode;
  tabs: DetailWorkbenchTab[];
  activeTab: string;
  onTabChange: (key: string) => void;
  /** Must contain the only way out — see the header comment (issue 102). */
  footer?: React.ReactNode;
}) {
  // Before the early return, and gated on `open` instead — a hook after a
  // conditional return would change hook order between renders.
  useEscapeToClose(onClose, open);

  if (!open) return null;
  const active = tabs.find(t => t.key === activeTab) ?? tabs[0];

  return (
    <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4">
      {/* Backdrop is decorative only — no onClick — an accidental outside click must not discard the review in progress. */}
      <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" />
      <div
        className="relative bg-card w-full sm:max-w-4xl sm:rounded-2xl rounded-t-2xl shadow-xl flex flex-col h-full sm:h-auto sm:max-h-[92vh] z-50"
        onClick={e => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-start gap-3 px-5 py-4 border-b border-border shrink-0">
          <div className="flex-1 min-w-0">
            <h3 className="text-foreground leading-tight truncate">{title}</h3>
            {subtitle && <p className="text-xs text-muted-foreground truncate mt-0.5">{subtitle}</p>}
          </div>
        </div>

        {/* Tabs */}
        <div className="flex gap-2 border-b border-border px-5 overflow-x-auto shrink-0">
          {tabs.map(tab => (
            <button
              key={tab.key}
              onClick={() => onTabChange(tab.key)}
              className={`px-3 py-2.5 text-sm whitespace-nowrap border-b-2 transition-colors -mb-px ${
                activeTab === tab.key ? 'border-primary text-primary' : 'border-transparent text-muted-foreground hover:text-foreground'
              }`}
            >
              {tab.label}
            </button>
          ))}
        </div>

        {/* Body */}
        <div className="overflow-y-auto px-5 py-5 space-y-5 text-sm flex-1 overscroll-contain">
          {active?.content}
        </div>

        {/* Footer action bar */}
        {footer && (
          <div className="border-t border-border px-5 py-4 shrink-0">
            {footer}
          </div>
        )}
      </div>
    </div>
  );
}
