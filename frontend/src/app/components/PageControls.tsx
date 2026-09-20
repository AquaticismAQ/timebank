import { useState, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { reportLocalizedValidity } from '../lib/validity';
import { PAGE_SIZE_OPTIONS } from '../lib/pagination';
import {
  Pagination,
  PaginationContent,
  PaginationItem,
  PaginationLink,
  PaginationPrevious,
  PaginationNext,
} from './ui/pagination';

const SELECT_CLASS = 'px-2 py-1 rounded-lg border border-border bg-input-background dark:bg-muted text-foreground text-xs focus:outline-none';

/**
 * I1.2's shared pagination control — composes the vendored ui/pagination.tsx primitives, reused across every swapped list screen instead of rebuilt per screen.
 * `pageSize`/`onPageSizeChange` are optional and opt a caller into the page-size selector and page-jump input added by
 * Docs/optimization/04-DataViewOptimization.md §2.3/§6 phase 1 — omitting them (every screen this round doesn't migrate, per §2.5's scope decision)
 * reproduces the exact prior behavior unchanged, prev/next included.
 */
export function PageControls({
  page,
  onPageChange,
  pageSize,
  onPageSizeChange,
}: {
  page: { number: number; size: number; totalItems: number; totalPages: number } | null;
  onPageChange: (n: number) => void;
  pageSize?: number;
  onPageSizeChange?: (n: number) => void;
  // Deliberately no `pageSizeOptions` override: the set is defined once in
  // lib/pagination.ts so every list offers the same one. No caller ever passed
  // it, and allowing one would reintroduce exactly the per-screen drift this
  // removes.
}) {
  const { t } = useTranslation();
  const jumpFormRef = useRef<HTMLFormElement>(null);
  const [jumpValue, setJumpValue] = useState('');
  const enhanced = pageSize !== undefined && onPageSizeChange !== undefined;
  const showPager = !!page && page.totalPages > 1;
  if (!page || (!showPager && !enhanced)) return null;

  const atFirst = page.number <= 1;
  const atLast = page.number >= page.totalPages;
  // `aria-disabled` was already set on both, so assistive tech was told — but
  // nothing styled it, leaving Previous on page 1 looking identical to a live
  // control that simply did nothing when clicked. Styled here rather than in
  // ui/pagination.tsx, which is vendored (CLAUDE.md: compose, don't edit).
  // `pointer-events-none` stops the click reaching a handler that would reject
  // it anyway, and tabIndex -1 keeps an unusable control out of the tab order.
  const disabledClass = 'pointer-events-none opacity-40 text-muted-foreground';

  const go = (n: number) => (e: React.MouseEvent) => {
    e.preventDefault();
    if (n >= 1 && n <= page.totalPages) onPageChange(n);
  };
  const submitJump = (e: React.FormEvent) => {
    e.preventDefault();
    if (!reportLocalizedValidity(jumpFormRef.current, t)) return;
    const n = Number(jumpValue);
    if (Number.isInteger(n) && n >= 1 && n <= page.totalPages) {
      onPageChange(n);
      setJumpValue('');
    }
  };

  return (
    <div className="flex items-center justify-between mt-4 flex-wrap gap-2">
      <div className="flex items-center gap-3 flex-wrap">
        <span className="text-xs text-muted-foreground">
          {page.totalItems} total · page {page.number} / {page.totalPages}
        </span>
        {enhanced && (
          <label className="flex items-center gap-1.5 text-xs text-muted-foreground">
            {t('common.list.pageSize')}
            <select value={pageSize} onChange={e => onPageSizeChange(Number(e.target.value))} className={SELECT_CLASS}>
              {PAGE_SIZE_OPTIONS.map(size => <option key={size} value={size}>{size}</option>)}
            </select>
          </label>
        )}
      </div>
      {showPager && (
        <div className="flex items-center gap-3 flex-wrap">
          <Pagination className="mx-0 w-auto justify-end">
            <PaginationContent>
              <PaginationItem>
                <PaginationPrevious
                  href="#" onClick={go(page.number - 1)}
                  aria-disabled={atFirst} tabIndex={atFirst ? -1 : undefined}
                  className={atFirst ? disabledClass : undefined}
                />
              </PaginationItem>
              <PaginationItem>
                <PaginationLink href="#" isActive>{page.number}</PaginationLink>
              </PaginationItem>
              <PaginationItem>
                <PaginationNext
                  href="#" onClick={go(page.number + 1)}
                  aria-disabled={atLast} tabIndex={atLast ? -1 : undefined}
                  className={atLast ? disabledClass : undefined}
                />
              </PaginationItem>
            </PaginationContent>
          </Pagination>
          {enhanced && (
            <form ref={jumpFormRef} onSubmit={submitJump} noValidate className="flex items-center gap-1.5 text-xs text-muted-foreground">
              {t('common.list.pageJump')}
              <input
                type="number" min={1} max={page.totalPages} value={jumpValue}
                onChange={e => setJumpValue(e.target.value)}
                className="w-14 px-2 py-1 rounded-lg border border-border bg-input-background dark:bg-muted text-foreground text-xs focus:outline-none"
              />
            </form>
          )}
        </div>
      )}
    </div>
  );
}
