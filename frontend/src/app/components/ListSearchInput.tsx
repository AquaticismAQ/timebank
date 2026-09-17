import { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { Search } from 'lucide-react';

/**
 * The shared search box for every paginated list (issue 095 §1).
 *
 * <p>Two deliberate differences from what each screen used to build for itself:</p>
 *
 * <p><b>It does not search as you type.</b> Every screen previously called
 * `setSearch` from `onChange`, so each keystroke was a request — at 2,000 rows a
 * burst of queries nobody reads, and each one racing the last. The value is only
 * committed on <b>Enter</b> or a click of the icon.</p>
 *
 * <p><b>The icon is a button, inside the field, at the trailing edge.</b> It was
 * previously decoration on the left, which gave the user nothing to click once
 * typing stopped triggering the search.</p>
 *
 * <p>`value` is the <em>committed</em> search, owned by the caller; the draft is
 * local. They resynchronise when the caller changes `value` from outside — a
 * "Clear filters" button must visibly empty the box, and would not if the draft
 * ignored it.</p>
 */
export function ListSearchInput({
  value,
  onCommit,
  placeholder,
  className = '',
}: {
  /** The committed search term the list is currently filtered by. */
  value: string;
  /** Called only when the user commits — never per keystroke. */
  onCommit: (term: string) => void;
  placeholder?: string;
  className?: string;
}) {
  const { t } = useTranslation();
  const [draft, setDraft] = useState(value);

  // Follow the caller when it resets or changes the committed term externally.
  useEffect(() => setDraft(value), [value]);

  const commit = () => onCommit(draft.trim());

  return (
    <div className={`relative ${className}`}>
      <input
        type="text"
        value={draft}
        onChange={e => setDraft(e.target.value)}
        onKeyDown={e => {
          if (e.key === 'Enter') {
            // Without this, a field inside a form submits it instead of searching.
            e.preventDefault();
            commit();
          }
        }}
        placeholder={placeholder}
        className="w-full pl-4 pr-10 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-primary/50"
      />
      <button
        type="button"
        onClick={commit}
        aria-label={t('common.list.search')}
        title={t('common.list.search')}
        className="absolute right-1 top-1/2 -translate-y-1/2 p-2 rounded-lg text-muted-foreground hover:text-foreground hover:bg-accent transition-colors"
      >
        <Search className="w-4 h-4" />
      </button>
    </div>
  );
}
