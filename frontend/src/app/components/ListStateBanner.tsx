import { AlertTriangle, Inbox, Lock, WifiOff } from 'lucide-react';
import type { ErrorUiMapping } from '../api/http';

/** I1.4's shared loading/empty/error state — the mock's `.catch(() => {})` pattern disappears; silent failure is not a state. */
export function ListStateBanner({ error, empty, emptyLabel }: { error: ErrorUiMapping | null; empty: boolean; emptyLabel?: string }) {
  if (error) {
    const { icon: Icon, text } =
      error.kind === 'notAvailable'
        ? { icon: Lock, text: 'Not available.' }
        : error.kind === 'rateLimited'
        ? { icon: AlertTriangle, text: 'Too many requests — try again shortly.' }
        : error.kind === 'conflict'
        ? { icon: AlertTriangle, text: error.message }
        : { icon: WifiOff, text: 'Something went wrong loading this list.' };
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
        <p className="text-sm">{emptyLabel ?? 'Nothing here yet.'}</p>
      </div>
    );
  }
  return null;
}
