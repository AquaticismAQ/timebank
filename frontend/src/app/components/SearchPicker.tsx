import { useEffect, useRef, useState } from 'react';
import { Check, ChevronDown, Plus, X } from 'lucide-react';
import { Popover, PopoverContent, PopoverTrigger } from './ui/popover';
import { Command, CommandEmpty, CommandInput, CommandItem, CommandList } from './ui/command';

export interface SearchPickerOption {
  id: string;
  label: string;
  sublabel?: string;
}

/** Sentinel id for a "create new" selection — callers check `selected[0]?.id === CREATE_NEW_ID` to tell it apart from a real picked option. */
export const CREATE_NEW_ID = '__new__';

/**
 * A debounced async search picker (single or multi), backed by real
 * directory/organization search endpoints instead of free text
 * (`Docs/optimization/01-i3-partner-school-terminology.md`). Generic on
 * purpose — used for co-organizers, a supervisor, and a partner
 * organization, each with a different `fetchOptions`.
 */
export function SearchPicker({
  selected,
  onChange,
  multi = false,
  fetchOptions,
  placeholder,
  searchPlaceholder,
  noResultsLabel,
  loadingLabel,
  allowCreateNew = false,
  createNewLabel,
  disabled = false,
}: {
  selected: SearchPickerOption[];
  onChange: (selected: SearchPickerOption[]) => void;
  multi?: boolean;
  fetchOptions: (search: string) => Promise<SearchPickerOption[]>;
  placeholder: string;
  searchPlaceholder: string;
  noResultsLabel: string;
  loadingLabel: string;
  allowCreateNew?: boolean;
  createNewLabel?: (search: string) => string;
  disabled?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState('');
  const [options, setOptions] = useState<SearchPickerOption[]>([]);
  const [loading, setLoading] = useState(false);
  const fetchOptionsRef = useRef(fetchOptions);
  fetchOptionsRef.current = fetchOptions;

  useEffect(() => {
    if (!search) {
      setOptions([]);
      return;
    }
    let cancelled = false;
    setLoading(true);
    const timer = setTimeout(() => {
      fetchOptionsRef.current(search)
        .then(results => { if (!cancelled) setOptions(results); })
        .finally(() => { if (!cancelled) setLoading(false); });
    }, 250);
    return () => { cancelled = true; clearTimeout(timer); };
  }, [search]);

  const toggle = (opt: SearchPickerOption) => {
    if (multi) {
      const exists = selected.some(s => s.id === opt.id);
      onChange(exists ? selected.filter(s => s.id !== opt.id) : [...selected, opt]);
      setSearch('');
    } else {
      onChange([opt]);
      setOpen(false);
      setSearch('');
    }
  };

  const remove = (id: string) => onChange(selected.filter(s => s.id !== id));

  const exactMatch = options.some(o => o.label.toLowerCase() === search.trim().toLowerCase());
  const showCreateNew = allowCreateNew && search.trim().length > 0 && !exactMatch;

  const resultsList = (
    <Command shouldFilter={false}>
      {/* maxLength only matters when allowCreateNew turns this search text into a persisted name (Docs/issues/036-*.md); Organization.name has no explicit column length, so 255 is Hibernate's implicit default. */}
      <CommandInput value={search} onValueChange={setSearch} placeholder={searchPlaceholder} maxLength={allowCreateNew ? 255 : undefined} />
      <CommandList>
        {loading && <div className="py-6 text-center text-sm text-muted-foreground">{loadingLabel}</div>}
        {!loading && search && options.length === 0 && !showCreateNew && <CommandEmpty>{noResultsLabel}</CommandEmpty>}
        {!loading && options.map(opt => (
          <CommandItem key={opt.id} value={opt.id} onSelect={() => toggle(opt)}>
            <div className="flex-1 min-w-0">
              <div className="truncate">{opt.label}</div>
              {opt.sublabel && <div className="text-xs text-muted-foreground truncate">{opt.sublabel}</div>}
            </div>
            {selected.some(s => s.id === opt.id) && <Check className="w-4 h-4 shrink-0" />}
          </CommandItem>
        ))}
        {!loading && showCreateNew && createNewLabel && (
          <CommandItem value={`create-${search}`} onSelect={() => toggle({ id: CREATE_NEW_ID, label: search.trim() })}>
            <Plus className="w-4 h-4 shrink-0" />
            <span className="truncate">{createNewLabel(search.trim())}</span>
          </CommandItem>
        )}
      </CommandList>
    </Command>
  );

  if (multi) {
    return (
      <div>
        {selected.length > 0 && (
          <div className="flex flex-wrap gap-1.5 mb-1.5">
            {selected.map(s => (
              <span key={s.id} className="inline-flex items-center gap-1 pl-2 pr-1 py-1 rounded-lg bg-muted text-xs text-foreground">
                {s.label}
                <button type="button" onClick={() => remove(s.id)} className="p-0.5 text-muted-foreground hover:text-foreground">
                  <X className="w-3 h-3" />
                </button>
              </span>
            ))}
          </div>
        )}
        <Popover open={open} onOpenChange={setOpen}>
          <PopoverTrigger asChild>
            <button
              type="button"
              disabled={disabled}
              className="w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm text-left focus:outline-none focus:ring-2 focus:ring-primary/50 flex items-center justify-between gap-2 disabled:opacity-50"
            >
              <span className="text-muted-foreground">{placeholder}</span>
              <ChevronDown className="w-4 h-4 text-muted-foreground shrink-0" />
            </button>
          </PopoverTrigger>
          <PopoverContent className="p-0 w-[--radix-popover-trigger-width]" align="start">
            {resultsList}
          </PopoverContent>
        </Popover>
      </div>
    );
  }

  const current = selected[0];
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button
          type="button"
          disabled={disabled}
          className="w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm text-left focus:outline-none focus:ring-2 focus:ring-primary/50 flex items-center justify-between gap-2 disabled:opacity-50"
        >
          <span className={`truncate ${current ? '' : 'text-muted-foreground'}`}>
            {current ? (current.id === CREATE_NEW_ID ? `+ ${current.label}` : current.label) : placeholder}
          </span>
          <span className="flex items-center gap-1 shrink-0">
            {current && (
              <span
                role="button"
                tabIndex={0}
                onClick={e => { e.stopPropagation(); remove(current.id); }}
                onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.stopPropagation(); remove(current.id); } }}
                className="p-0.5 text-muted-foreground hover:text-foreground"
              >
                <X className="w-3.5 h-3.5" />
              </span>
            )}
            <ChevronDown className="w-4 h-4 text-muted-foreground" />
          </span>
        </button>
      </PopoverTrigger>
      <PopoverContent className="p-0 w-[--radix-popover-trigger-width]" align="start">
        {resultsList}
      </PopoverContent>
    </Popover>
  );
}
