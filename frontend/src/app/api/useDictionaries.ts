import { useEffect, useState } from 'react';
import { api } from './client';
import type { Grade, SchoolClass, AcademicYear, ServiceCategory } from './types';

/**
 * Grades/classes/academic years/service categories (contract §2.10) barely
 * change at runtime, so every screen sharing one fetch-once-per-page-load
 * cache is simpler and cheaper than each screen re-fetching the same four
 * near-static lists on its own mount. Module-level, not a Context — nothing
 * here needs to be provider-scoped or mockable per test, it's the same for
 * every logged-in user.
 */
let cache: Promise<{
  grades: Grade[];
  classes: SchoolClass[];
  academicYears: AcademicYear[];
  serviceCategories: ServiceCategory[];
}> | null = null;

function loadDictionaries() {
  if (!cache) {
    cache = Promise.all([
      api.dictionaries.grades(),
      api.dictionaries.classes(),
      api.dictionaries.academicYears(),
      api.dictionaries.serviceCategories(),
    ]).then(([grades, classes, academicYears, serviceCategories]) => ({ grades, classes, academicYears, serviceCategories }))
      .catch(err => { cache = null; throw err; });
  }
  return cache;
}

export interface Dictionaries {
  grades: Grade[];
  classes: SchoolClass[];
  academicYears: AcademicYear[];
  serviceCategories: ServiceCategory[];
  loading: boolean;
}

export function useDictionaries(): Dictionaries {
  const [state, setState] = useState<Dictionaries>({ grades: [], classes: [], academicYears: [], serviceCategories: [], loading: true });

  useEffect(() => {
    let cancelled = false;
    loadDictionaries().then(data => {
      if (!cancelled) setState({ ...data, loading: false });
    }).catch(() => {
      if (!cancelled) setState(s => ({ ...s, loading: false }));
    });
    return () => { cancelled = true; };
  }, []);

  return state;
}
