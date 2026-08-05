import {
  Pagination,
  PaginationContent,
  PaginationItem,
  PaginationLink,
  PaginationPrevious,
  PaginationNext,
} from './ui/pagination';

/** I1.2's shared pagination control — composes the vendored ui/pagination.tsx primitives, reused across every swapped list screen instead of rebuilt per screen. */
export function PageControls({
  page,
  onPageChange,
}: {
  page: { number: number; size: number; totalItems: number; totalPages: number } | null;
  onPageChange: (n: number) => void;
}) {
  if (!page || page.totalPages <= 1) return null;

  const go = (n: number) => (e: React.MouseEvent) => {
    e.preventDefault();
    if (n >= 1 && n <= page.totalPages) onPageChange(n);
  };

  return (
    <div className="flex items-center justify-between mt-4 flex-wrap gap-2">
      <span className="text-xs text-muted-foreground">
        {page.totalItems} total · page {page.number} / {page.totalPages}
      </span>
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
    </div>
  );
}
