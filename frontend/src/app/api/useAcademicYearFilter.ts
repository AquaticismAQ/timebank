import { useEffect, useState } from 'react';
import { useDictionaries } from './useDictionaries';
import type { AcademicYear } from './types';
import type { ListParams } from '../lib/pagination';

/**
 * The academic-year dropdown shared by the review screens (issue 115), which
 * default to the current year rather than to every year.
 *
 * A review queue is a "what is in front of me now" screen, so showing every year
 * it has ever held buries this year's work under closed items. The *endpoint*
 * still reads an absent filter as every year — it is shared with the students'
 * own case and proposal lists, where a pinned year would hide someone's own
 * history — so the default lives here, in the screens that want it.
 *
 * `applied` distinguishes "not chosen yet" from "chose All years". Without it the
 * effect cannot tell the two apart, and re-applies the current year the moment a
 * user picks All — a control that undoes itself.
 *
 * It starts true when the URL already names a year, because these screens sync
 * their filters to the query string: a copied link pinned to 2025-2026 must open
 * on 2025-2026, not be overwritten by the default a moment after it loads.
 *
 * One known limit: a link copied while All years was selected carries no year
 * key, so reopening it applies the current year again. Distinguishing the two
 * would need a sentinel in the query string that the server would then have to
 * reject, and a fresh page load landing on the current year is the defensible
 * reading of an absent parameter here.
 */
export function useAcademicYearFilter(
  params: ListParams,
  setFilter: (key: string, value: string | undefined) => void,
): {
  academicYears: AcademicYear[];
  value: string;
  onChange: (value: string) => void;
} {
  const { academicYears } = useDictionaries();
  const [applied, setApplied] = useState(() => params.filter?.academicYearId !== undefined);

  useEffect(() => {
    if (applied) return;
    // The dictionary is fetched, so at first render there is no year to name —
    // hence an effect rather than an initial state value.
    const current = academicYears.find(y => y.current);
    if (!current) return;
    setApplied(true);
    setFilter('academicYearId', current.id);
    // setFilter is stable per usePagedApi's useCallback; listing it would not
    // change when this runs.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [academicYears, applied]);

  return {
    academicYears,
    // Blank until the default lands, so the control never briefly shows "All
    // years" while the request that says otherwise is already in flight.
    value: params.filter?.academicYearId ?? (applied ? 'all' : ''),
    onChange: (value: string) => {
      setApplied(true);
      setFilter('academicYearId', value === 'all' ? undefined : value);
    },
  };
}
