import { useState } from 'react';
import { useTranslation } from 'react-i18next';
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
  pageSizeOptions = [20, 50, 100],
}: {
  page: { number: number; size: number; totalItems: number; totalPages: number } | null;
  onPageChange: (n: number) => void;
  pageSize?: number;
  onPageSizeChange?: (n: number) => void;
  pageSizeOptions?: number[];
}) {
  const { t } = useTranslation();
  const [jumpValue, setJumpValue] = useState('');
  const enhanced = pageSize !== undefined && onPageSizeChange !== undefined;
  const showPager = !!page && page.totalPages > 1;
  if (!page || (!showPager && !enhanced)) return null;

  const go = (n: number) => (e: React.MouseEvent) => {
    e.preventDefault();
    if (n >= 1 && n <= page.totalPages) onPageChange(n);
  };
  const submitJump = (e: React.FormEvent) => {
    e.preventDefault();
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
              {pageSizeOptions.map(size => <option key={size} value={size}>{size}</option>)}
            </select>
          </label>
        )}
      </div>
      {showPager && (
        <div className="flex items-center gap-3 flex-wrap">
          <Pagination className="mx-0 w-auto justify-end">
            <PaginationContent>
              <PaginationItem>
                <PaginationPrevious href="#" onClick={go(page.number - 1)} aria-disabled={page.number <= 1} />
              </PaginationItem>
              <PaginationItem>
                <PaginationLink href="#" isActive>{page.number}</PaginationLink>
              </PaginationItem>
              <PaginationItem>
                <PaginationNext href="#" onClick={go(page.number + 1)} aria-disabled={page.number >= page.totalPages} />
              </PaginationItem>
            </PaginationContent>
          </Pagination>
          {enhanced && (
            <form onSubmit={submitJump} className="flex items-center gap-1.5 text-xs text-muted-foreground">
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
