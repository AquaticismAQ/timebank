import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Clock } from 'lucide-react';
import { Popover, PopoverContent, PopoverTrigger } from '../ui/popover';

const HOURS = Array.from({ length: 24 }, (_, i) => String(i).padStart(2, '0'));
const MINUTES = Array.from({ length: 60 }, (_, i) => String(i).padStart(2, '0'));

/**
 * A click-to-pick HH:MM time field — two scrollable digit columns in a
 * popover (the same "digital clock" shape MUI's `TimePicker` uses), not a
 * native `<input type="time">`. The native control's own picker UI is an
 * analog/scroll-wheel widget on iOS Safari and some Android browsers, and
 * turned out to be unreliable at committing a value at all in this app's
 * actual use (issue 061's digit-only text-box replacement fixed the
 * commit-reliability problem but was still unpopular as an interaction —
 * this replaces it with a proper picker instead of a better text box).
 *
 * A hidden `required` proxy input mirrors the picked value so this keeps
 * participating in the wizard's existing `reportValidity()`/`checkValidity()`
 * gating (issue 058/061) without that gating needing to know this isn't a
 * plain `<input>` anymore.
 */
export function TimeInput({ value, onChange, required }: {
  /** "HH:MM", or "" when empty. */
  value: string;
  onChange: (value: string) => void;
  required?: boolean;
}) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const [hh, mm] = value ? value.split(':') : ['', ''];
  const hourListRef = useRef<HTMLDivElement>(null);
  const minuteListRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const scrollToSelected = (container: HTMLDivElement | null) => {
      container?.querySelector('[data-selected="true"]')?.scrollIntoView({ block: 'center' });
    };
    // Next frame, so the popover's own open-transition has laid out first.
    const raf = requestAnimationFrame(() => {
      scrollToSelected(hourListRef.current);
      scrollToSelected(minuteListRef.current);
    });
    return () => cancelAnimationFrame(raf);
  }, [open]);

  const pickHour = (h: string) => onChange(`${h}:${mm || '00'}`);
  const pickMinute = (m: string) => onChange(`${hh || '00'}:${m}`);

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button type="button"
          className="w-full h-12 px-4 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm text-left focus:outline-none focus:ring-2 focus:ring-primary/50 flex items-center justify-between gap-2">
          <span className={value ? '' : 'text-muted-foreground'}>{value || 'HH:MM'}</span>
          <Clock className="w-4 h-4 text-muted-foreground shrink-0" />
        </button>
      </PopoverTrigger>
      <PopoverContent className="w-40 p-0" align="start">
        <div className="flex divide-x divide-border h-56">
          <div ref={hourListRef} className="flex-1 overflow-y-auto py-1">
            {HOURS.map(h => (
              <button key={h} type="button" data-selected={h === hh} onClick={() => pickHour(h)}
                className={`w-full py-1.5 text-center text-sm ${h === hh ? 'bg-primary/10 text-primary font-medium' : 'text-foreground hover:bg-accent'}`}>
                {h}
              </button>
            ))}
          </div>
          <div ref={minuteListRef} className="flex-1 overflow-y-auto py-1">
            {MINUTES.map(m => (
              <button key={m} type="button" data-selected={m === mm} onClick={() => pickMinute(m)}
                className={`w-full py-1.5 text-center text-sm ${m === mm ? 'bg-primary/10 text-primary font-medium' : 'text-foreground hover:bg-accent'}`}>
                {m}
              </button>
            ))}
          </div>
        </div>
        <button type="button" onClick={() => setOpen(false)}
          className="w-full py-2 text-center text-sm text-primary border-t border-border hover:bg-accent">
          {t('common.done')}
        </button>
      </PopoverContent>
      {/* Deliberately not `readOnly`: a readonly control is "immutable" per the
          HTML spec and is therefore *always* considered valid regardless of
          `required`, which would silently defeat the whole point of this proxy.
          The no-op onChange just satisfies React's controlled-input contract —
          nothing actually calls it, since real edits happen through the picker. */}
      {required && <input type="text" required value={value} onChange={() => {}} tabIndex={-1} aria-hidden="true" className="sr-only" />}
    </Popover>
  );
}
