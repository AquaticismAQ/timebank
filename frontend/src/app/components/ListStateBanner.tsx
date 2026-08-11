import { AlertTriangle, Inbox, Lock, WifiOff } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import type { ErrorUiMapping } from '../api/http';

/**
 * I1.4's shared loading/empty/error state — the mock's `.catch(() => {})` pattern disappears; silent failure is not a state.
 * `hasActiveFilters`/`onClearFilters` (04-DataViewOptimization.md §2.3) let a caller distinguish "no records at all" from
 * "no results for the current filter" via `emptyLabel`, and offer a way out of the latter — omit them for the former.
 */
export function ListStateBanner({
  error, empty, emptyLabel, hasActiveFilters, onClearFilters,
}: {
  error: ErrorUiMapping | null;
  empty: boolean;
  emptyLabel?: string;
  hasActiveFilters?: boolean;
  onClearFilters?: () => void;
}) {
  const { t } = useTranslation();
  if (error) {
    const { icon: Icon, text } =
      error.kind === 'notAvailable'
        ? { icon: Lock, text: t('common.list.notAvailable') }
        : error.kind === 'rateLimited'
        ? { icon: AlertTriangle, text: t('common.list.rateLimited') }
        : error.kind === 'conflict'
        ? { icon: AlertTriangle, text: error.message }
        : { icon: WifiOff, text: t('common.list.loadError') };
    return (
      <div className="flex flex-col items-center justify-center gap-2 py-12 text-muted-foreground">
        <Icon className="w-8 h-8" />
        <p className="text-sm">{text}</p>
      </div>
    );
  }
  if (empty) {
    return (
      <div className="flex flex-col items-center justify-center gap-2 py-12 text-muted-foreground">
        <Inbox className="w-8 h-8" />
        <p className="text-sm">{emptyLabel ?? t('common.list.emptyNoRecords')}</p>
        {hasActiveFilters && onClearFilters && (
          <button onClick={onClearFilters} className="text-sm text-primary hover:underline">
            {t('common.list.clearFilters')}
          </button>
        )}
      </div>
    );
  }
  return null;
}
