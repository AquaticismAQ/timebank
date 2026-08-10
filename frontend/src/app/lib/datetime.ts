/**
 * `<input type="date">`/`<input type="datetime-local">` values are wall-clock
 * LOCAL time with no timezone info. Naively appending `Z` (as if the value
 * were already UTC) silently shifts every submitted instant by the
 * browser's UTC offset — 8 hours for a China-based user. `Date`'s
 * `(year, month, day, hour, minute)` constructor correctly interprets its
 * arguments as local time, so building through it and calling
 * `toISOString()` does the conversion properly.
 */
export function localDateTimeToISO(dateStr: string, timeStr?: string): string {
  const [year, month, day] = dateStr.split('-').map(Number);
  const [hour, minute] = (timeStr ?? '00:00').split(':').map(Number);
  return new Date(year, month - 1, day, hour, minute, 0, 0).toISOString();
}

/** Same conversion for a single `<input type="datetime-local">` value ("YYYY-MM-DDTHH:mm"). */
export function localDatetimeLocalToISO(value: string): string {
  const [dateStr, timeStr] = value.split('T');
  return localDateTimeToISO(dateStr, timeStr);
}

/** Inverse of `localDateTimeToISO` — splits a UTC instant back into local `<input type="date">`/`<input type="time">` values, for pre-filling an edit form from a stored record. */
export function isoToLocalDateTime(iso: string): { date: string; time: string } {
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  const date = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  const time = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  return { date, time };
}

/** Adds one calendar day to a `<input type="date">`-shaped "YYYY-MM-DD" string, for an overnight service window's end date. */
export function addOneDay(dateStr: string): string {
  const [year, month, day] = dateStr.split('-').map(Number);
  const d = new Date(year, month - 1, day + 1);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}
