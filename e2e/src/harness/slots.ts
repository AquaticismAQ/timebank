import type { Api, ServiceRecordRow } from './api.js';

export interface Slot {
  /** `YYYY-MM-DD`, always today or earlier. */
  date: string;
  startHHMM: string;
  endHHMM: string;
}

/**
 * Finds service-record time windows the student does not already occupy.
 *
 * This is the one piece of machinery re-runnability depends on. Overlapping
 * windows are a hard block at submit, and the records that block are permanent:
 * only a DRAFT can ever be deleted, so every approved or returned record consumes
 * its window forever. (REJECTED is deliberately outside the duplicate check, so a
 * rejected record frees its window again.)
 *
 * Capacity therefore comes from *dates*, not from cleanup. `serviceDate` may be
 * today or any past day, so the allocator takes today first — which keeps a demo's
 * story straight — and walks backwards only when today is full.
 */
export class SlotAllocator {
  private taken: Array<{ start: number; end: number }> = [];

  /**
   * Windows used by records that block a later overlapping one — the backend's
   * DUPLICATE_CHECK_STATUSES (`ServiceRecordServiceImpl:70`).
   *
   * UPPER_SNAKE because this reads the API directly, which returns the raw enum
   * name; only the frontend's own client maps those to camelCase. Getting this
   * wrong is silent and total: no existing record matches, every window looks
   * free, and the run fails later at submit with OVERLAPPING_RECORD.
   */
  private static readonly BLOCKING = new Set([
    'SUBMITTED', 'UNDER_REVIEW', 'APPROVED', 'RETURNED', 'CORRECTED',
  ]);

  /** Each ~2h, inside a plausible school day, so the demo reads as real service. */
  private static readonly DAILY_SLOTS: Array<[string, string]> = [
    // All two hours, so every record a run files is worth the same and the demo
    // does not silently change its own numbers between runs.
    ['09:00', '11:00'], ['13:00', '15:00'], ['15:30', '17:30'], ['19:00', '21:00'],
  ];

  constructor(private readonly api: Api, private readonly studentId: string) {}

  async load(): Promise<void> {
    // `page[size]`, not `size` — the plain name is silently ignored and the
    // request comes back with the default 20 rows. That is invisible and
    // dangerous here: the allocator would believe the student had only twenty
    // records, hand out a window one of the others already occupies, and the run
    // would die at submit with OVERLAPPING_RECORD looking like a product fault.
    const rows = await this.api.get<ServiceRecordRow[]>(
      `/service-records?page%5Bsize%5D=200&filter%5BstudentId%5D=${encodeURIComponent(this.studentId)}`,
    );
    this.taken = rows
      .filter(r => SlotAllocator.BLOCKING.has(r.status.toUpperCase()))
      .map(r => ({ start: Date.parse(r.startAt), end: Date.parse(r.endAt) }))
      .filter(w => Number.isFinite(w.start) && Number.isFinite(w.end));
  }

  /**
   * @param count how many windows this run needs
   * @param maxDaysBack how far into the past to look before giving up
   * @returns the windows, earliest-dated last (so the run files today first)
   */
  allocate(count: number, maxDaysBack = 400): Slot[] {
    const found: Slot[] = [];
    for (let back = 0; back <= maxDaysBack && found.length < count; back++) {
      const day = new Date();
      day.setDate(day.getDate() - back);
      const date = toIsoDate(day);
      for (const [startHHMM, endHHMM] of SlotAllocator.DAILY_SLOTS) {
        if (found.length >= count) break;
        const start = Date.parse(`${date}T${startHHMM}:00`);
        const end = Date.parse(`${date}T${endHHMM}:00`);
        // A record cannot be in the future, so a slot later today is unusable.
        if (end > Date.now()) continue;
        if (this.taken.some(w => start < w.end && end > w.start)) continue;
        found.push({ date, startHHMM, endHHMM });
        this.taken.push({ start, end });
      }
    }
    if (found.length < count) {
      throw new Error(
        `Could not find ${count} free service-record window(s) within ${maxDaysBack} days for this student. `
        + 'Every approved or returned record blocks its window permanently — see e2e/README.md.',
      );
    }
    return found;
  }

  /** True when the allocator had to leave today, which is worth saying out loud. */
  static driftedFromToday(slots: Slot[]): boolean {
    const today = toIsoDate(new Date());
    return slots.some(s => s.date !== today);
  }
}

function toIsoDate(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}
