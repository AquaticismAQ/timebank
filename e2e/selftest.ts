import { SlotAllocator } from './src/harness/slots.js';

/**
 * Checks for the harness's own pure logic, which nothing else exercises.
 *
 * The slot allocator is the one piece that can fail silently: hand out a window
 * the student already occupies and the run dies much later, at submit, with an
 * `OVERLAPPING_RECORD` that looks like a product bug. It takes no UI, so it can
 * be tested directly — and every check here is paired with a control, because an
 * assertion that would pass on a do-nothing implementation proves nothing.
 *
 * `pnpm selftest`
 */
let failures = 0;

function check(name: string, ok: boolean, detail = ''): void {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
  if (!ok) failures++;
}

type Row = { id: string; serviceDate: string; startAt: string; endAt: string; status: string };

function allocatorWith(rows: Row[]): SlotAllocator {
  return new SlotAllocator({ get: async () => rows } as never, 'std_selftest');
}

const SLOTS: Array<[string, string]> = [['09:00', '11:00'], ['13:00', '15:00'], ['15:30', '17:30'], ['19:00', '20:30']];
const daysAgo = (n: number): string => new Date(Date.now() - n * 86_400_000).toISOString().slice(0, 10);
const row = (date: string, from: string, to: string, status: string): Row => ({
  id: `rec_${date}_${from}`, serviceDate: date,
  startAt: new Date(`${date}T${from}:00`).toISOString(),
  endAt: new Date(`${date}T${to}:00`).toISOString(),
  status,
});
const fullDay = (date: string, status: string): Row[] => SLOTS.map(([f, t]) => row(date, f, t, status));

async function allocate(rows: Row[], count: number) {
  const a = allocatorWith(rows);
  await a.load();
  return a.allocate(count);
}

const [one, two] = [daysAgo(1), daysAgo(2)];

// 1. Occupied days are skipped — with a control proving they are reachable at all.
const avoided = await allocate([...fullDay(one, 'APPROVED'), ...fullDay(two, 'APPROVED')], 6);
check('skips days fully occupied by APPROVED records',
  !avoided.some(s => s.date === one || s.date === two),
  avoided.map(s => `${s.date} ${s.startHHMM}`).join(' '));

const control = await allocate([], 6);
check('control: those days are reachable when nothing is taken',
  control.some(s => s.date === one) && control.some(s => s.date === two),
  control.map(s => `${s.date} ${s.startHHMM}`).join(' '));

// 2. REJECTED is outside DUPLICATE_CHECK_STATUSES, so it must not block. Isolated
//    from check 1, where APPROVED rows would have hidden the difference.
const rejectedOnly = await allocate(fullDay(one, 'REJECTED'), 6);
check('REJECTED records do not block their window',
  rejectedOnly.some(s => s.date === one),
  rejectedOnly.map(s => `${s.date} ${s.startHHMM}`).join(' '));

// 3. Casing is tolerated in both directions. Note it is *check 1* that guards the
//    original bug — it uses the UPPER_SNAKE the raw API actually returns, which a
//    camelCase-only comparison matched nothing of, silently handing out occupied
//    windows. This one covers the other direction.
const camel = await allocate(fullDay(one, 'approved'), 6);
check('status matching is case-insensitive', !camel.some(s => s.date === one));

// 4. Allocated windows never overlap each other.
const many = await allocate([], 12);
const spans = many.map(s => [Date.parse(`${s.date}T${s.startHHMM}:00`), Date.parse(`${s.date}T${s.endHHMM}:00`)]);
const selfOverlap = spans.some(([aS, aE], i) => spans.some(([bS, bE], j) => i !== j && aS < bE && aE > bS));
check('allocated windows do not overlap each other', !selfOverlap);

// 5. Nothing is ever placed in the future — `serviceDate` cannot be.
check('never allocates a window in the future',
  spans.every(([, end]) => end <= Date.now()));

// 6. Running out is an error, not a silent short list.
let threw = false;
try {
  const a = allocatorWith([]);
  await a.load();
  a.allocate(100, 1);   // 1 day back cannot hold 100 windows
} catch {
  threw = true;
}
check('refuses rather than returning fewer windows than asked for', threw);

console.log(failures === 0 ? '\nall self-checks passed' : `\n${failures} self-check(s) failed`);
process.exitCode = failures === 0 ? 0 : 1;
