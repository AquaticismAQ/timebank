import { useEffect, useState } from 'react';
import i18n from '../../i18n';
import { api } from './client';
import type { Grade, SchoolClass, AcademicYear, ServiceCategory } from './types';

/**
 * Grades/classes/academic years/service categories (contract §2.10) barely
 * change at runtime, so every screen sharing one fetch-once-per-page-load
 * cache is simpler and cheaper than each screen re-fetching the same four
 * near-static lists on its own mount. Module-level, not a Context — nothing
 * here needs to be provider-scoped or mockable per test, it's the same for
 * every logged-in user.
 *
 * The cache is language-keyed: dictionary `name` fields are resolved
 * server-side per `Accept-Language`, so switching language must trigger a
 * re-fetch rather than serving stale names from the previous language.
 * Resolves issue 011 (grade dropdown staleness) and issue 024 (service
 * category dropdown staleness) — both share this cache.
 */
let cache: Promise<{
  grades: Grade[];
  classes: SchoolClass[];
  academicYears: AcademicYear[];
  serviceCategories: ServiceCategory[];
}> | null = null;
let cacheLanguage: string | null = null;

/** Clear the dictionary cache so the next access re-fetches with the current language. */
export function clearDictionaryCache(): void {
  cache = null;
  cacheLanguage = null;
}

function loadDictionaries() {
  const lang = i18n.language;
  if (!cache || cacheLanguage !== lang) {
    cache = Promise.all([
      api.dictionaries.grades(),
      api.dictionaries.classes(),
      api.dictionaries.academicYears(),
      api.dictionaries.serviceCategories(),
    ]).then(([grades, classes, academicYears, serviceCategories]) => ({ grades, classes, academicYears, serviceCategories }))
      .catch(err => { cache = null; cacheLanguage = null; throw err; });
    cacheLanguage = lang;
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
  }, [i18n.language]);

  return state;
}
