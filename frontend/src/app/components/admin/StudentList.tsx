import { useState, useEffect, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { Search, ChevronDown, ChevronUp, Pencil, Bell, FileText, Save } from 'lucide-react';
import { type Student } from '../../api/types';
import { formatCoins, minutesToHours, formatGradeClass, formatClassName } from '../../lib/format';
import { STATUS_COLOR_CLASSES } from '../../lib/statusLabels';
import { toast } from 'sonner';
import { useAuth } from '../../App';
import { api } from '../../api/client';
import { handleMutationError } from '../../api/useFieldErrors';
import { usePagedApi } from '../../api/useApi';
import { useDictionaries } from '../../api/useDictionaries';
import { PageControls } from '../PageControls';
import { ListStateBanner } from '../ListStateBanner';
import { StudentRecords } from './StudentRecords';
import { ListSearchInput } from '../ListSearchInput';
import { GradeOptions } from '../GradePicker';
import { useEscapeToClose } from '../../lib/useEscapeToClose';

type SortKey = 'name' | 'gradeLevel' | 'completedHours' | 'timeCoinBalance';

/**
 * Marks a graduate option in the one grade dropdown, so `grade_8` and
 * "graduates of grade 8" stay distinguishable in a single `<select>` value
 * (issue 114). Not a parsed id — `GRADUATE_PREFIX + gradeId` is assembled and
 * split here and nowhere else, and the id inside it is passed through opaquely.
 */
const GRADUATE_PREFIX = 'graduated:';

/**
 * Docs/issues/028: row-action color per action — icon + label, bordered,
 * never filled — matching the convention `SystemAdmin.tsx`/
 * `RoleManagement.tsx` established (`ACTION_BTN_BLUE`/etc.) for
 * Users/Roles/Templates/Rule Settings. Composed with layout-specific base
 * classes at each call site (desktop table cell vs. mobile card's
 * equal-width row), rather than one fixed className per color, since the
 * two layouts need different padding/flex shape.
 */
const ACTION_COLORS = {
  blue: 'border-blue-200 dark:border-blue-800 text-blue-600 dark:text-blue-400 hover:bg-blue-50 dark:hover:bg-blue-900/20',
  amber: 'border-amber-200 dark:border-amber-800 text-amber-600 dark:text-amber-400 hover:bg-amber-50 dark:hover:bg-amber-900/20',
  cyan: 'border-cyan-200 dark:border-cyan-800 text-cyan-600 dark:text-cyan-400 hover:bg-cyan-50 dark:hover:bg-cyan-900/20',
} as const;

function ProgressBar({ value, max, color = 'bg-primary' }: { value: number; max: number; color?: string }) {
  const pct = Math.min((value / max) * 100, 100);
  return (
    <div className="h-1.5 bg-muted rounded-full overflow-hidden">
      <div className={`h-full ${color} rounded-full`} style={{ width: `${pct}%` }} />
    </div>
  );
}

type EditForm = {
  name: string;
  studentNumber: string;
  gradeId: string;
  classId: string;
  completedHours: number;
  timeCoinBalance: number;
  memo: string;
};

export function StudentList() {
  const { t } = useTranslation();
  const { user } = useAuth();
  const isAdmin = user?.roles?.includes('admin') ?? false;
  const { grades, classes, lifecycle } = useDictionaries();

  const [search, setSearch] = useState('');
  /**
   * One control answers "which people am I looking at" (issue 114).
   *
   * `all` or a grade id means **current students**; `GRADUATE_PREFIX + gradeId`
   * means that grade's graduates. They share a dropdown because they are the same
   * question asked twice, and because a separate status select would make four
   * filters plus a search on one row for a cohort that is read rarely.
   *
   * The two cannot simply both be grade values: graduation leaves `gradeId` on
   * the grade the student finished, so "Grade 8" would otherwise return this
   * year's grade 8 *and* last year's graduates in one list.
   */
  const [filterGrade, setFilterGrade] = useState('all');
  const [filterClass, setFilterClass] = useState('all');
  const [filterStatus, setFilterStatus] = useState<'all' | 'onTrack' | 'below50' | 'below25'>('all');
  const [sortKey, setSortKey] = useState<SortKey>('name');
  const [sortDesc, setSortDesc] = useState(false);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [editStudent, setEditStudent] = useState<Student | null>(null);
  const [recordsStudent, setRecordsStudent] = useState<Student | null>(null);
  const [editForm, setEditForm] = useState<EditForm | null>(null);

  const { items, page, loading, error, params, setPage, setPageSize, setSearch: setApiSearch, setFilter, setSort, refetch } =
    usePagedApi<Student>(api.students.list, {});

  // Only `name` maps onto a real, sortable backend column (`StudentController.SORTABLE` only allows
  // id/studentNumber/displayName/createdAt) — the wire property is `displayName`, not `name`. `grade` has
  // no sortable column at all (grade_id sorts alphabetically as a string, which puts grade_10/11 before
  // grade_6/7/8/9 — not the pedagogical order — so the backend deliberately doesn't offer it) and
  // `completedHours`/`timeCoinBalance` are record-derived aggregates with nothing to sort on server-side;
  // all three stay client-side (current page only). Sending an unsupported `sort` value 400s
  // ("Cannot sort by '...'") — this used to happen for both `name` and `grade`.
  const CLIENT_ONLY_SORT_KEYS: SortKey[] = ['gradeLevel', 'completedHours', 'timeCoinBalance'];
  const SORT_WIRE_KEY: Partial<Record<SortKey, string>> = { name: 'displayName' };

  const [students, setStudents] = useState<Student[]>([]);
  // keep a local editable copy of the current page so admin edits reflect immediately without a refetch

  // `GET /students` never carries a balance (StudentListItemDto has no such
  // field — issue 006) — fetched separately and merged in, the same pattern
  // AccountManagement.tsx already uses, rather than coupling the roster
  // endpoint to account-ledger data.
  const [balanceMap, setBalanceMap] = useState<Record<string, number>>({});
  useEffect(() => {
    if (items.length === 0) return;
    api.accounts.list({ pageSize: 200 }).then(result => {
      const map: Record<string, number> = {};
      for (const acct of result.data) map[acct.studentId] = acct.balance;
      setBalanceMap(map);
    }).catch(() => {});
  }, [items]);

  // `GET /students` carries no service hours either — the same
  // StudentListItemDto gap as the balance above — so the Service Hours column
  // and Annual Progress rendered "—" and 0% for every student regardless of
  // their actual data, while the student's own portal showed the real figures
  // from `/students/{id}/progress`. Merged in from that same endpoint here.
  //
  // There is no bulk progress endpoint, so this is one request per student.
  // Concurrency is capped because a page can hold 100 students; a failed row
  // is left blank rather than failing the whole list.
  const [progressMap, setProgressMap] = useState<Record<string, { completedMinutes: number; annualRequiredMinutes: number }>>({});

  // Escape closes the topmost open dialog (issue 102 §5).
  useEscapeToClose(() => setEditStudent(null), Boolean(editStudent));
  useEffect(() => {
    if (items.length === 0) return;
    let cancelled = false;
    const queue = [...items];
    const map: Record<string, { completedMinutes: number; annualRequiredMinutes: number }> = {};
    const worker = async () => {
      while (queue.length > 0 && !cancelled) {
        const student = queue.shift();
        if (!student) return;
        try {
          const p = await api.students.progressSummary(student.id);
          map[student.id] = { completedMinutes: p.completedMinutes, annualRequiredMinutes: p.annualRequiredMinutes };
        } catch {
          // leave this row's hours blank
        }
      }
    };
    Promise.all(Array.from({ length: Math.min(6, items.length) }, worker))
      .then(() => { if (!cancelled) setProgressMap(map); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [items]);

  useEffect(() => {
    setStudents(items.map(s => ({
      ...s,
      timeCoinBalance: balanceMap[s.id] ?? s.timeCoinBalance,
      completedMinutes: progressMap[s.id]?.completedMinutes ?? s.completedMinutes,
      annualRequiredMinutes: progressMap[s.id]?.annualRequiredMinutes ?? s.annualRequiredMinutes,
    })));
  }, [items, balanceMap, progressMap]);

  const handleSearchChange = (value: string) => {
    setSearch(value);
    setApiSearch(value);
  };

  const handleGradeChange = (value: string) => {
    setFilterGrade(value);
    const graduateGradeId = value.startsWith(GRADUATE_PREFIX) ? value.slice(GRADUATE_PREFIX.length) : null;
    setFilter('gradeId', graduateGradeId ?? (value === 'all' ? undefined : value));
    // Absent means current students server-side, so only the graduate options
    // ever name a status. Sending `active` for the rest would wrongly exclude
    // `inactive` students, who are still on the roster.
    setFilter('status', graduateGradeId ? 'graduated' : undefined);
    // A class belongs to exactly one grade — a class selected under the
    // previous grade is meaningless (or invalid) once the grade changes.
    setFilterClass('all');
    setFilter('classId', undefined);
  };

  const handleClassChange = (value: string) => {
    setFilterClass(value);
    setFilter('classId', value === 'all' ? undefined : value);
  };

  // A scoped teacher's dropdown only offers the grades they're bonded to
  // (issue 001/004) — an admin, or a teacher with no bond yet, sees every grade.
  const availableGrades = useMemo(() => {
    if (user && user.roles.includes('teacher') && !user.roles.includes('admin') && user.scopeGradeIds.length > 0) {
      return grades.filter(g => user.scopeGradeIds.includes(g.id));
    }
    return grades;
  }, [grades, user]);

  /**
   * The grades a student can graduate from, read from `GET /lifecycle/config`
   * rather than written here as `[8, 12]`. The backend graduates on
   * `AnnualRolloverRepository.EXIT_LEVELS`; a second copy in this component would
   * be a second source of truth for a fact only the rollover really owns, and it
   * would be wrong on the day a school's stage structure differs.
   *
   * Empty if the config could not be loaded — no options rather than guessed
   * ones, since a guess that disagrees with the rollover is worse than an absent
   * feature.
   */
  const graduateGrades = useMemo(() => {
    const exitLevels = lifecycle?.exitGradeLevels ?? [];
    // `sequence` is the grade level (grade_8 → 8), which is also what the
    // backend's exit levels are expressed in.
    return availableGrades.filter(g => exitLevels.includes(g.sequence));
  }, [availableGrades, lifecycle]);

  const viewingGraduates = filterGrade.startsWith(GRADUATE_PREFIX);

  const availableClasses = useMemo(
    () => classes.filter(c => filterGrade === 'all' || c.gradeId === filterGrade),
    [classes, filterGrade],
  );

  const handleSortClick = (k: SortKey) => {
    const nextDesc = sortKey === k ? !sortDesc : false;
    setSortKey(k);
    setSortDesc(nextDesc);
    if (!CLIENT_ONLY_SORT_KEYS.includes(k)) {
      const wireKey = SORT_WIRE_KEY[k] ?? k;
      setSort(nextDesc ? `-${wireKey}` : wireKey);
    } else {
      setSort(undefined);
    }
  };

  const openEdit = (s: Student) => {
    setEditStudent(s);
    setEditForm({
      name: s.name,
      studentNumber: s.studentNumber,
      gradeId: s.gradeId,
      classId: s.classId,
      completedHours: Math.round(((s.completedMinutes ?? 0) / 60) * 100) / 100,
      timeCoinBalance: (s.timeCoinBalance ?? 0) / 100,
      memo: '',
    });
  };

  const saveEdit = async () => {
    if (!editStudent || !editForm) return;
    if (editForm.completedHours < 0) { toast.error(t('admin.students.completedHoursNegative')); return; }
    if (editForm.timeCoinBalance < 0) { toast.error(t('admin.students.timeCoinsNegative')); return; }

    const newGradeName = grades.find(g => g.id === editForm.gradeId)?.name ?? editForm.gradeId;
    const newClassName = classes.find(c => c.id === editForm.classId)?.name ?? editForm.classId;

    try {
      // studentNumber is only ever actually different here when isAdmin (the
      // input is disabled otherwise, bound straight to editStudent's own
      // value) — Docs/issues/037-*.md, this field used to be silently
      // dropped from the request entirely.
      const hasProfileChanges = editForm.studentNumber !== editStudent.studentNumber ||
        editForm.name !== editStudent.name ||
        editForm.gradeId !== editStudent.gradeId || editForm.classId !== editStudent.classId;

      if (hasProfileChanges) {
        // Resolve from the (possibly just-changed) form value first, not the
        // student's stale id — `editStudent.gradeId` is always truthy for a
        // real student, so putting it first meant a grade/class change in
        // this form was silently never sent to the backend even though the
        // list below optimistically showed it as changed.
        await api.students.update(editStudent.id, {
          studentNumber: editForm.studentNumber,
          displayName: editForm.name,
          gradeId: editForm.gradeId || editStudent.gradeId || undefined,
          classId: editForm.classId || editStudent.classId || undefined,
          version: editStudent.version ?? 1,
        });
      }

      setStudents(prev => prev.map(s => s.id === editStudent.id ? {
        ...s,
        studentNumber: editForm.studentNumber,
        name: editForm.name,
        gradeId: editForm.gradeId,
        grade: newGradeName,
        classId: editForm.classId,
        className: newClassName,
      } : s));

      toast.success(t('admin.students.toastUpdated', { name: editForm.name }));
      setEditStudent(null);
      setEditForm(null);
      refetch();
    } catch (err) {
      handleMutationError(err, { refetch, fallbackMessage: String(err) });
    }
  };

  const sortValue = (s: Student, key: SortKey): string | number =>
    key === 'completedHours' ? (s.completedMinutes ?? 0) : key === 'timeCoinBalance' ? (s.timeCoinBalance ?? 0) : s[key];

  // Search and grade filtering now happen server-side (see handleSearchChange/handleGradeChange above).
  // The status bucket (onTrack/below50/below25) is derived from a ratio the backend doesn't expose as a
  // filterable field, so it's still applied client-side to whatever page came back.
  // Sorting: server-sortable keys arrive pre-sorted in `students`; completedHours/timeCoinBalance have no
  // backend column to sort on, so they're sorted locally, within the current page only.
  const filtered = useMemo(() => {
    const statusFiltered = students.filter(s => {
      const pct = (s.completedMinutes ?? 0) / (s.annualRequiredMinutes ?? 1);
      return filterStatus === 'all' ? true :
        filterStatus === 'onTrack' ? pct >= 0.5 :
        filterStatus === 'below50' ? pct < 0.5 :
        pct < 0.25;
    });
    if (!CLIENT_ONLY_SORT_KEYS.includes(sortKey)) return statusFiltered;
    return [...statusFiltered].sort((a, b) => {
      const av = sortValue(a, sortKey);
      const bv = sortValue(b, sortKey);
      return sortDesc ? (bv > av ? 1 : -1) : (av > bv ? 1 : -1);
    });
  }, [students, filterStatus, sortKey, sortDesc]);

  /**
   * The purge dates for the cohorts actually on screen — 31 August of the year
   * after each `graduationYear` (`13-AnnualRollover.md` §6). Derived from the rows
   * rather than from today, so it names the deadline for what is being looked at;
   * usually one date, two while both cohorts are still retained.
   */
  const retentionDates = useMemo(() => {
    const years = new Set(
      filtered.map(s => s.graduationYear).filter((y): y is number => typeof y === 'number'),
    );
    return [...years].sort((a, b) => a - b).map(y => `${y + 1}-08-31`).join(', ');
  }, [filtered]);

  // Computed from the current page only (the real /students list is paginated, not fetched in full, and
  // there's no aggregate-stats endpoint) — these read as "on this page", not school-wide. `total` is kept
  // consistent with onTrack/atRisk (both page-only) rather than mixed with `page.totalItems`, so the
  // "behind" card (total - onTrack) doesn't produce a nonsense number.
  const stats = {
    total: students.length,
    onTrack: students.filter(s => (s.completedMinutes ?? 0) / (s.annualRequiredMinutes ?? 1) >= 0.5).length,
    atRisk: students.filter(s => (s.completedMinutes ?? 0) / (s.annualRequiredMinutes ?? 1) < 0.25).length,
  };

  // 05-SearchFilterSortConsistency.md §2.2 (D2): headers are a static, non-clickable indicator of the
  // active sort column — the sort-button row below is now the only way to change sort.
  const SortTh = ({ label, active }: { label: string; active: boolean }) => (
    <th className="text-left px-4 py-3">
      <div className="flex items-center gap-1 text-xs text-muted-foreground font-medium">
        {label}
        {active && <span className="text-primary">{sortDesc ? '↓' : '↑'}</span>}
      </div>
    </th>
  );

  const SORT_BUTTONS: { key: SortKey; label: string }[] = [
    { key: 'name', label: t('admin.students.sortName') },
    { key: 'gradeLevel', label: t('admin.students.sortGrade') },
    { key: 'completedHours', label: t('admin.students.sortHoursShort') },
    { key: 'timeCoinBalance', label: t('admin.students.sortCoinsShort') },
  ];

  return (
    <div className="p-4 lg:p-6 space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-foreground">{t('admin.students.title')}</h1>
          {/* The roster's size, not this page's. `students.length` is 20 —
              whatever the page holds — while the pagination control beside it
              already renders the real figure from the same response, so the
              headline said "20 名学生" under a list of 56 (issue 108). */}
          <p className="text-sm text-muted-foreground">
            {t('admin.students.count', { count: page?.totalItems ?? students.length })}
          </p>
        </div>
      </div>

      {/* Summary stats */}
      <div className="grid grid-cols-3 gap-3">
        <div className="bg-card border border-border rounded-xl p-4 text-center">
          <div className="text-2xl font-semibold text-foreground">{stats.onTrack}</div>
          <div className="text-xs text-muted-foreground">{t('admin.students.onTrack')}</div>
        </div>
        <div className="bg-card border border-border rounded-xl p-4 text-center">
          <div className="text-2xl font-semibold text-foreground">{stats.total - stats.onTrack}</div>
          <div className="text-xs text-muted-foreground">{t('admin.students.behind')}</div>
        </div>
        <div className="bg-card border border-border rounded-xl p-4 text-center">
          <div className="text-2xl font-semibold text-foreground">{stats.atRisk}</div>
          <div className="text-xs text-muted-foreground">{t('admin.students.critical')}</div>
        </div>
      </div>

      {/* Search & filters — 05-SearchFilterSortConsistency.md §2.4: search alone below lg:, filters as an
          Nx1 grid alongside it, merged into one row at lg: and up. */}
      <div className="flex flex-col gap-2 lg:flex-row lg:flex-wrap">
        <div className="relative lg:flex-1 lg:min-w-40">
          <ListSearchInput
            value={search}
            onCommit={handleSearchChange}
            placeholder={t('admin.students.searchPlaceholder')}
          />
        </div>
        <div className="grid grid-cols-3 gap-2 lg:contents">
          <select value={filterGrade} onChange={e => handleGradeChange(e.target.value)}
            className="px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none">
            <option value="all">{t('admin.students.allGrades')}</option>
            {availableGrades.map(g => <option key={g.id} value={g.id}>{g.name}</option>)}
            {/* Grouped, not appended: these read as the same question about a
                different set of people, and an ungrouped list would put
                "Grade 8" and "Grade 8 graduates" next to each other with
                nothing saying they are different cohorts. Absent entirely when
                the config did not load — see `graduateGrades`. */}
            {graduateGrades.length > 0 && (
              <optgroup label={t('admin.students.graduatesGroup')}>
                {graduateGrades.map(g => (
                  <option key={g.id} value={GRADUATE_PREFIX + g.id}>
                    {t('admin.students.gradeGraduates', { grade: g.name })}
                  </option>
                ))}
              </optgroup>
            )}
          </select>
          <select value={filterClass} onChange={e => handleClassChange(e.target.value)}
            className="px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none">
            <option value="all">{t('admin.students.allClasses')}</option>
            {availableClasses.map(c => <option key={c.id} value={c.id}>{formatClassName(c.name)}</option>)}
          </select>
          <select value={filterStatus} onChange={e => setFilterStatus(e.target.value as any)}
            className="px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none">
            <option value="all">{t('admin.students.allStatuses')}</option>
            <option value="onTrack">{t('admin.students.statusOnTrack')}</option>
            <option value="below50">{t('admin.students.statusBehind')}</option>
            <option value="below25">{t('admin.students.statusCritical')}</option>
          </select>
        </div>
      </div>

      {/* Sort controls: one button per field (§2.2/D2), always an Nx1 grid (§2.5, D3: forced 4-col row with
          shortened labels rather than wrapping). */}
      <div className="grid grid-cols-4 gap-2">
        {SORT_BUTTONS.map(({ key, label }) => (
          <button key={key} onClick={() => handleSortClick(key)}
            className={`px-3 py-1.5 rounded-lg text-xs flex items-center justify-center gap-1 transition-colors ${
              sortKey === key ? 'bg-blue-100 text-blue-700 dark:bg-blue-500/20 dark:text-blue-300' : 'bg-muted text-muted-foreground hover:bg-accent'
            }`}>
            {label}
            {sortKey === key && (sortDesc ? ' ↓' : ' ↑')}
          </button>
        ))}
      </div>

      <div className="text-xs text-muted-foreground">{t('admin.students.count', { count: filtered.length })}</div>

      {/* The retention deadline, stated where the graduates are read rather than
          left in a design doc. A cohort's records are purged on 31 August of the
          year after `graduationYear` (13-AnnualRollover.md §6) — roughly a year.
          Without this, the first time anyone learns there was a deadline is when
          a transcript request comes back empty. */}
      {viewingGraduates && filtered.length > 0 && (
        <div className="rounded-xl border border-amber-200 dark:border-amber-800 bg-amber-50 dark:bg-amber-900/20 px-4 py-3 text-xs text-amber-800 dark:text-amber-300">
          {t('admin.students.graduateRetentionNotice', { dates: retentionDates })}
        </div>
      )}

      <ListStateBanner error={error} empty={!loading && filtered.length === 0} emptyLabel="No students" />

      {/* Desktop: sortable table */}
      <div className="hidden lg:block bg-card border border-border rounded-2xl overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-muted/50 border-b border-border">
            <tr>
              <SortTh label={t('admin.students.sortName')} active={sortKey === 'name'} />
              <SortTh label={t('admin.students.sortGrade')} active={sortKey === 'gradeLevel'} />
              <th className="text-left px-4 py-3 text-xs text-muted-foreground font-medium">{t('admin.students.annualProgress')}</th>
              <SortTh label={t('admin.students.sortHours')} active={sortKey === 'completedHours'} />
              <SortTh label={t('admin.students.sortCoins')} active={sortKey === 'timeCoinBalance'} />
              <th className="text-left px-4 py-3 text-xs text-muted-foreground font-medium">{t('admin.students.status')}</th>
              <th className="text-left px-4 py-3 text-xs text-muted-foreground font-medium">{t('admin.students.actions')}</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {filtered.map(student => {
              const annualPct = (student.completedMinutes ?? 0) / (student.annualRequiredMinutes ?? 1);
              const atRisk = annualPct < 0.25;
              const belowHalf = annualPct < 0.5;
              // Read off the row, not off the filter: a row says what it is
              // regardless of how it was asked for.
              const graduated = student.status === 'graduated';
              return (
                <tr key={student.id} className={`hover:bg-muted/30 transition-colors ${atRisk && !graduated ? 'bg-red-50/30 dark:bg-red-900/10' : ''}`}>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2">
                      <div className="w-8 h-8 bg-accent rounded-full flex items-center justify-center shrink-0">
                        <span className="text-foreground text-xs font-medium">{student.name[0]}</span>
                      </div>
                      <div>
                        <div className="text-sm text-foreground font-medium">{student.name}</div>
                        <div className="text-xs text-muted-foreground">{student.studentNumber}</div>
                      </div>
                    </div>
                  </td>
                  <td className="px-4 py-3 text-sm text-muted-foreground">{formatGradeClass(student.gradeLevel, student.className)}</td>
                  <td className="px-4 py-3 min-w-32">
                    <div className="flex items-center gap-2">
                      <div className="flex-1">
                        <ProgressBar value={student.completedMinutes ?? 0} max={student.annualRequiredMinutes ?? 1}
                          color={atRisk ? 'bg-red-500' : belowHalf ? 'bg-yellow-500' : 'bg-green-500'} />
                      </div>
                      <span className={`text-xs font-medium w-8 text-right shrink-0 ${atRisk ? 'text-red-500' : belowHalf ? 'text-yellow-500' : 'text-green-500'}`}>
                        {Math.round(annualPct * 100)}%
                      </span>
                    </div>
                  </td>
                  <td className="px-4 py-3 text-sm text-foreground">{student.completedMinutes !== undefined ? `${minutesToHours(student.completedMinutes)}h` : '—'}</td>
                  <td className="px-4 py-3 text-sm text-foreground">{student.timeCoinBalance !== undefined ? formatCoins(student.timeCoinBalance) : '—'}</td>
                  <td className="px-4 py-3">
                    {/* A graduate has no annual requirement left to be on track
                        for, so the progress verdict is replaced rather than
                        shown alongside — and the badge names the cohort year,
                        which is what tells two graduated cohorts apart. */}
                    {graduated ? (
                      <span className={`text-xs px-2 py-0.5 rounded-full whitespace-nowrap ${STATUS_COLOR_CLASSES.gray}`}>
                        {t('admin.students.graduatedIn', { year: student.graduationYear ?? '—' })}
                      </span>
                    ) : (
                      <span className={`text-xs px-2 py-0.5 rounded-full whitespace-nowrap ${STATUS_COLOR_CLASSES[atRisk ? 'red' : belowHalf ? 'yellow' : 'green']}`}>
                        {t(atRisk ? 'admin.students.critical' : belowHalf ? 'admin.students.behind' : 'admin.students.onTrack')}
                      </span>
                    )}
                  </td>
                  <td className="px-4 py-3">
                    {/* Records stays, and is the whole reason to look at a
                        graduate — reading their history before it is purged.
                        Edit and Remind go: the rollover deactivates the login,
                        so a reminder reaches nobody and editing a left student's
                        placement is changing history. */}
                    <div className="flex gap-1">
                      {!graduated && (
                        <>
                          <button onClick={() => openEdit(student)}
                            className={`text-xs px-2 py-1 rounded-lg border transition-colors flex items-center gap-1 ${ACTION_COLORS.blue}`}>
                            <Pencil className="w-3 h-3" /> {t('admin.students.edit')}
                          </button>
                          <button onClick={() => toast.success(t('admin.students.toastRemindSent', { name: student.name }))}
                            className={`text-xs px-2 py-1 rounded-lg border transition-colors flex items-center gap-1 ${ACTION_COLORS.amber}`}>
                            <Bell className="w-3 h-3" /> {t('admin.students.remind')}
                          </button>
                        </>
                      )}
                      <button onClick={() => setRecordsStudent(student)}
                        className={`text-xs px-2 py-1 rounded-lg border transition-colors flex items-center gap-1 ${ACTION_COLORS.cyan}`}>
                        <FileText className="w-3 h-3" /> {t('admin.students.records')}
                      </button>
                    </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
        <div className="px-4 pb-4">
          <PageControls page={page} onPageChange={setPage} pageSize={params.pageSize ?? 20} onPageSizeChange={setPageSize} />
        </div>
      </div>

      {/* Edit student modal */}
      {editStudent && editForm && (
        <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4">
          <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" />
          <div className="relative bg-card w-full sm:max-w-md sm:rounded-2xl rounded-t-2xl shadow-xl flex flex-col max-h-[90vh]">

            {/* Header */}
            <div className="flex items-center gap-3 px-5 py-4 border-b border-border shrink-0">
              <div className="w-9 h-9 bg-[oklch(0.488_0.243_264.376)] rounded-full flex items-center justify-center shrink-0">
                <span className="text-white text-sm font-medium">{editStudent.name[0]}</span>
              </div>
              <div className="flex-1 min-w-0">
                <h3 className="text-foreground leading-tight">{t('admin.students.editTitle')}</h3>
                <p className="text-xs text-muted-foreground">{editStudent.studentNumber} · {formatGradeClass(editStudent.gradeLevel, editStudent.className)}</p>
              </div>
            </div>

            {/* Form body */}
            <div className="overflow-y-auto px-5 py-4 space-y-4 overscroll-contain">

              {/* Identity fields */}
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs text-muted-foreground mb-1.5">{t('admin.students.name')}</label>
                  <input type="text" value={editForm.name}
                    onChange={e => setEditForm(f => f && ({ ...f, name: e.target.value }))}
                    maxLength={100}
                    className="w-full px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-[oklch(0.488_0.243_264.376)]/40" />
                </div>
                <div>
                  <label className="block text-xs text-muted-foreground mb-1.5">
                    {t('admin.students.studentNumber')}{!isAdmin && <span className="ml-1 text-muted-foreground/60">({t('admin.students.readOnly')})</span>}
                  </label>
                  <input
                    type="text"
                    value={isAdmin ? editForm.studentNumber : editStudent.studentNumber}
                    disabled={!isAdmin}
                    onChange={e => isAdmin && setEditForm(f => f && ({ ...f, studentNumber: e.target.value }))}
                    maxLength={32}
                    className={`w-full px-3 py-2.5 rounded-xl border border-border text-sm ${
                      isAdmin
                        ? 'bg-input-background dark:bg-muted text-foreground focus:outline-none focus:ring-2 focus:ring-[oklch(0.488_0.243_264.376)]/40'
                        : 'bg-muted text-muted-foreground cursor-not-allowed'
                    }`}
                  />
                </div>
              </div>


              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs text-muted-foreground mb-1.5">{t('admin.students.grade')}</label>
                  <select value={editForm.gradeId}
                    onChange={e => {
                      const gradeId = e.target.value;
                      // A class belongs to exactly one grade — switching grade without
                      // resetting classId would let a saved edit pair a student with a
                      // class from their *previous* grade.
                      const stillValid = classes.some(c => c.id === editForm.classId && c.gradeId === gradeId);
                      const fallback = classes.find(c => c.gradeId === gradeId)?.id ?? '';
                      setEditForm(f => f && ({ ...f, gradeId, classId: stillValid ? f.classId : fallback }));
                    }}
                    className="w-full px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-[oklch(0.488_0.243_264.376)]/40">
                    {/* Grouped by stage (issue 114) — moving a student between
                        stages is not the same kind of edit as moving them within
                        one, and the grouping says so before the save. */}
                    <GradeOptions grades={grades} />
                  </select>
                </div>
                <div>
                  <label className="block text-xs text-muted-foreground mb-1.5">{t('admin.students.class')}</label>
                  <select value={editForm.classId}
                    onChange={e => setEditForm(f => f && ({ ...f, classId: e.target.value }))}
                    className="w-full px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-[oklch(0.488_0.243_264.376)]/40">
                    {classes.filter(c => c.gradeId === editForm.gradeId).map(c => <option key={c.id} value={c.id}>{formatClassName(c.name)}</option>)}
                  </select>
                </div>
              </div>

              {/* Service data */}
              <div className="border-t border-border pt-4">
                <p className="text-xs text-muted-foreground mb-3">{t('admin.students.manualAdjustment')}</p>
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label className="block text-xs text-muted-foreground mb-1.5">{t('admin.students.completedHours')}</label>
                    <input type="number" min={0} step={0.5} value={editForm.completedHours}
                      onChange={e => setEditForm(f => f && ({ ...f, completedHours: Number(e.target.value) }))}
                      className="w-full px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-[oklch(0.488_0.243_264.376)]/40" />
                  </div>
                  <div>
                    <label className="block text-xs text-muted-foreground mb-1.5">{t('admin.students.timeCoins')}</label>
                    <input type="number" min={0} step={0.5} value={editForm.timeCoinBalance}
                      onChange={e => setEditForm(f => f && ({ ...f, timeCoinBalance: Number(e.target.value) }))}
                      className="w-full px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-[oklch(0.488_0.243_264.376)]/40" />
                  </div>
                </div>
              </div>

              {/* Memo */}
              <div>
                <label className="block text-xs text-muted-foreground mb-1.5">
                  {t('admin.students.memo')} <span className="ml-1 text-muted-foreground/60">({t('admin.students.willBeLogged')})</span>
                </label>
                <textarea
                  rows={3}
                  value={editForm.memo}
                  onChange={e => setEditForm(f => f && ({ ...f, memo: e.target.value }))}
                  placeholder={t('admin.students.memoPlaceholder')}
                  className="w-full px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm resize-none focus:outline-none focus:ring-2 focus:ring-[oklch(0.488_0.243_264.376)]/40"
                />
              </div>

              <div className="bg-muted/50 rounded-xl px-4 py-3 text-xs text-muted-foreground">
                {t('admin.students.enrolled')} {editStudent.joinedAt} · {t('admin.students.requirements')}
              </div>
            </div>

            {/* Footer */}
            <div className="px-5 py-4 border-t border-border flex gap-2 shrink-0">
              <button onClick={() => setEditStudent(null)}
                className="flex-1 py-2.5 rounded-xl border border-border text-sm text-foreground hover:bg-accent">
                {t('common.cancel')}
              </button>
              <button onClick={saveEdit}
                className="flex-1 py-2.5 rounded-xl bg-[oklch(0.488_0.243_264.376)] text-white text-sm hover:opacity-90 transition-opacity">
                {t('admin.students.saveChanges')}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Mobile: expandable cards */}
      <div className="lg:hidden space-y-2">
        {filtered.map(student => {
          const annualPct = (student.completedMinutes ?? 0) / (student.annualRequiredMinutes ?? 1);
          const isExpanded = expandedId === student.id;
          const atRisk = annualPct < 0.25;
          const belowHalf = annualPct < 0.5;
          const graduated = student.status === 'graduated';

          return (
            <div key={student.id} className={`bg-card border rounded-xl overflow-hidden ${atRisk && !graduated ? 'border-red-200 dark:border-red-800' : 'border-border'}`}>
              <button className="w-full p-4 text-left" onClick={() => setExpandedId(isExpanded ? null : student.id)}>
                <div className="flex items-center gap-3">
                  <div className="w-10 h-10 bg-accent rounded-full flex items-center justify-center shrink-0">
                    <span className="text-foreground text-sm font-medium">{student.name[0]}</span>
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 flex-wrap mb-0.5">
                      <span className="text-sm text-foreground font-medium">{student.name}</span>
                      <span className="text-xs text-muted-foreground">{formatGradeClass(student.gradeLevel, student.className)}</span>
                      {graduated ? (
                        <span className={`text-xs px-2 py-0.5 rounded-full whitespace-nowrap ${STATUS_COLOR_CLASSES.gray}`}>
                          {t('admin.students.graduatedIn', { year: student.graduationYear ?? '—' })}
                        </span>
                      ) : atRisk && (
                        <span className={`text-xs px-2 py-0.5 rounded-full whitespace-nowrap ${STATUS_COLOR_CLASSES.red}`}>
                          {t('admin.students.critical')}
                        </span>
                      )}
                    </div>
                    <div className="text-xs text-muted-foreground mb-1.5">
                      {student.completedMinutes !== undefined ? `${minutesToHours(student.completedMinutes)}h` : '—'} / {student.annualRequiredMinutes !== undefined ? `${minutesToHours(student.annualRequiredMinutes)}h` : '—'} · {student.timeCoinBalance !== undefined ? formatCoins(student.timeCoinBalance) : '—'} {t('admin.students.coinsUnit')}
                    </div>
                    <ProgressBar value={student.completedMinutes ?? 0} max={student.annualRequiredMinutes ?? 1}
                      color={atRisk ? 'bg-red-500' : belowHalf ? 'bg-yellow-500' : 'bg-green-500'} />
                  </div>
                  <div className="flex items-center gap-1 text-muted-foreground shrink-0">
                    <span className={`text-sm font-medium ${atRisk ? 'text-red-500' : belowHalf ? 'text-yellow-500' : 'text-green-500'}`}>
                      {Math.round(annualPct * 100)}%
                    </span>
                    {isExpanded ? <ChevronUp className="w-4 h-4" /> : <ChevronDown className="w-4 h-4" />}
                  </div>
                </div>
              </button>

              {isExpanded && (
                <div className="px-4 pb-4 border-t border-border pt-3 space-y-3">
                  <div className="grid grid-cols-2 gap-3 text-xs">
                    <div className="space-y-1.5">
                      <div className="flex justify-between"><span className="text-muted-foreground">{t('admin.students.studentNumber')}</span><span className="text-foreground">{student.studentNumber}</span></div>
                      <div className="flex justify-between"><span className="text-muted-foreground">{t('admin.students.completedHours')}</span><span className="text-green-600 dark:text-green-400">{student.completedMinutes !== undefined ? `${minutesToHours(student.completedMinutes)}h` : '—'}</span></div>
                      <div className="flex justify-between"><span className="text-muted-foreground">{t('admin.students.pendingHours')}</span><span className="text-blue-500">{student.pendingMinutes !== undefined ? `${minutesToHours(student.pendingMinutes)}h` : '—'}</span></div>
                    </div>
                    <div className="space-y-1.5">
                      <div className="flex justify-between"><span className="text-muted-foreground">{t('admin.students.annualRequiredHours')}</span><span className="text-foreground">{student.annualRequiredMinutes !== undefined ? `${minutesToHours(student.annualRequiredMinutes)}h` : '—'}</span></div>
                      <div className="flex justify-between"><span className="text-muted-foreground">{t('admin.students.graduationRequiredHours')}</span><span className="text-foreground">{student.stageRequiredMinutes !== undefined ? `${minutesToHours(student.stageRequiredMinutes)}h` : '—'}</span></div>
                      <div className="flex justify-between"><span className="text-muted-foreground">{t('admin.students.timeCoins')}</span><span className="text-foreground">{student.timeCoinBalance !== undefined ? formatCoins(student.timeCoinBalance) : '—'}</span></div>
                      <div className="flex justify-between"><span className="text-muted-foreground">{t('admin.students.enrolled')}</span><span className="text-foreground">{student.joinedAt}</span></div>
                    </div>
                  </div>
                  {/* Same rule as the desktop row: Records survives, Edit and
                      Remind do not, because the account is deactivated. */}
                  <div className="flex gap-2">
                    {!graduated && (
                      <>
                        <button onClick={() => openEdit(student)}
                          className={`flex-1 py-2 rounded-lg border text-xs transition-colors flex items-center justify-center gap-1 ${ACTION_COLORS.blue}`}>
                          <Pencil className="w-3 h-3" /> {t('admin.students.edit')}
                        </button>
                        <button onClick={() => toast.success(t('admin.students.toastRemindSent', { name: student.name }))}
                          className={`flex-1 py-2 rounded-lg border text-xs transition-colors flex items-center justify-center gap-1 ${ACTION_COLORS.amber}`}>
                          <Bell className="w-3 h-3" /> {t('admin.students.remind')}
                        </button>
                      </>
                    )}
                    <button onClick={() => setRecordsStudent(student)}
                      className={`flex-1 py-2 rounded-lg border text-xs transition-colors flex items-center justify-center gap-1 ${ACTION_COLORS.cyan}`}>
                      <FileText className="w-3 h-3" /> {t('admin.students.records')}
                    </button>
                  </div>
                </div>
              )}
            </div>
          );
        })}
        <PageControls page={page} onPageChange={setPage} pageSize={params.pageSize ?? 20} onPageSizeChange={setPageSize} />
      </div>

      {recordsStudent && (
        <StudentRecords studentId={recordsStudent.id} studentName={recordsStudent.name} onClose={() => setRecordsStudent(null)} />
      )}
    </div>
  );
}
