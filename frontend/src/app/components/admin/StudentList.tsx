import { useState, useEffect, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { Search, ChevronDown, ChevronUp, Download, Upload, AlertCircle, Pencil, X, Save } from 'lucide-react';
import { GRADES, GRADE_LABEL_KEYS, gradeNameToId, classNameToId } from '../../mock/data';
import { type Student } from '../../api/types';
import { formatCoins, minutesToHours } from '../../lib/format';
import { toast } from 'sonner';
import { useAuth, useLogs } from '../../App';
import { api } from '../../api/client';
import { handleMutationError } from '../../api/useFieldErrors';
import { usePagedApi } from '../../api/useApi';
import { PageControls } from '../PageControls';
import { ListStateBanner } from '../ListStateBanner';

type SortKey = 'name' | 'grade' | 'completedHours' | 'timeCoinBalance';

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
  email: string;
  studentNumber: string;
  grade: string;
  className: string;
  completedHours: number;
  timeCoinBalance: number;
  memo: string;
};

export function StudentList() {
  const { t } = useTranslation();
  const { user } = useAuth();
  const isAdmin = user?.roles?.includes('admin') ?? false;
  const { addLog } = useLogs();

  const [search, setSearch] = useState('');
  const [filterGrade, setFilterGrade] = useState('all');
  const [filterStatus, setFilterStatus] = useState<'all' | 'onTrack' | 'below50' | 'below25'>('all');
  const [sortKey, setSortKey] = useState<SortKey>('name');
  const [sortDesc, setSortDesc] = useState(false);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [editStudent, setEditStudent] = useState<Student | null>(null);
  const [editForm, setEditForm] = useState<EditForm | null>(null);

  const { items, page, loading, error, setPage, setSearch: setApiSearch, setFilter, setSort, refetch } =
    usePagedApi<Student>(api.students.list, {});

  // Only `name` maps onto a real, sortable backend column (`StudentController.SORTABLE` only allows
  // id/studentNumber/displayName/createdAt) — the wire property is `displayName`, not `name`. `grade` has
  // no sortable column at all (grade_id sorts alphabetically as a string, which puts grade_10/11 before
  // grade_6/7/8/9 — not the pedagogical order — so the backend deliberately doesn't offer it) and
  // `completedHours`/`timeCoinBalance` are record-derived aggregates with nothing to sort on server-side;
  // all three stay client-side (current page only). Sending an unsupported `sort` value 400s
  // ("Cannot sort by '...'") — this used to happen for both `name` and `grade`.
  const CLIENT_ONLY_SORT_KEYS: SortKey[] = ['grade', 'completedHours', 'timeCoinBalance'];
  const SORT_WIRE_KEY: Partial<Record<SortKey, string>> = { name: 'displayName' };

  const [students, setStudents] = useState<Student[]>([]);
  // keep a local editable copy of the current page so admin edits reflect immediately without a refetch
  useEffect(() => { setStudents(items); }, [items]);

  const handleSearchChange = (value: string) => {
    setSearch(value);
    setApiSearch(value);
  };

  const handleGradeChange = (value: string) => {
    setFilterGrade(value);
    setFilter('gradeId', value === 'all' ? undefined : gradeNameToId(value));
  };

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
      email: s.email,
      studentNumber: s.studentNumber,
      grade: s.grade,
      className: s.className,
      completedHours: (s.completedMinutes ?? 0) / 60,
      timeCoinBalance: (s.timeCoinBalance ?? 0) / 100,
      memo: '',
    });
  };

  const saveEdit = async () => {
    if (!editStudent || !editForm) return;
    if (editForm.completedHours < 0) { toast.error('Completed hours cannot be negative'); return; }
    if (editForm.timeCoinBalance < 0) { toast.error('Time coins cannot be negative'); return; }

    const priorCompletedHours = (editStudent.completedMinutes ?? 0) / 60;
    const priorTimeCoinBalance = (editStudent.timeCoinBalance ?? 0) / 100;

    const changes: string[] = [];
    if (editForm.name !== editStudent.name) changes.push(`Name changed to "${editForm.name}"`);
    if (editForm.grade !== editStudent.grade || editForm.className !== editStudent.className)
      changes.push(`Class changed to ${editForm.grade} ${editForm.className}`);
    if (editForm.completedHours !== priorCompletedHours)
      changes.push(`Completed hours ${priorCompletedHours}h→${editForm.completedHours}h (display only — hours are record-derived)`);
    if (editForm.timeCoinBalance !== priorTimeCoinBalance)
      changes.push(`Time coins ${priorTimeCoinBalance}→${editForm.timeCoinBalance} (use Account Management for balance adjustments)`);

    try {
      const hasProfileChanges = editForm.name !== editStudent.name || editForm.email !== editStudent.email ||
        editForm.grade !== editStudent.grade || editForm.className !== editStudent.className;

      if (hasProfileChanges) {
        // Resolve from the (possibly just-changed) form value first, not the
        // student's stale id — `editStudent.gradeId` is always truthy for a
        // real student, so putting it first meant a grade/class change in
        // this form was silently never sent to the backend even though the
        // list below optimistically showed it as changed.
        await api.students.update(editStudent.id, {
          displayName: editForm.name,
          email: editForm.email,
          gradeId: gradeNameToId(editForm.grade) || editStudent.gradeId || undefined,
          classId: classNameToId(editForm.className) || editStudent.classId || undefined,
          version: editStudent.version ?? 1,
        });
      }

      setStudents(prev => prev.map(s => s.id === editStudent.id ? {
        ...s,
        name: editForm.name,
        email: editForm.email,
        grade: editForm.grade,
        className: editForm.className,
      } : s));

      addLog({
        operator: user?.displayName ?? 'Admin',
        action: `Edit student profile${changes.length ? ': ' + changes.join(', ') : ''}`,
        target: `${editStudent.name} (${editStudent.studentNumber})`,
        category: 'student',
        memo: editForm.memo.trim() || undefined,
      });

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

  // Computed from the current page only (the real /students list is paginated, not fetched in full, and
  // there's no aggregate-stats endpoint) — these read as "on this page", not school-wide. `total` is kept
  // consistent with onTrack/atRisk (both page-only) rather than mixed with `page.totalItems`, so the
  // "behind" card (total - onTrack) doesn't produce a nonsense number.
  const stats = {
    total: students.length,
    onTrack: students.filter(s => (s.completedMinutes ?? 0) / (s.annualRequiredMinutes ?? 1) >= 0.5).length,
    atRisk: students.filter(s => (s.completedMinutes ?? 0) / (s.annualRequiredMinutes ?? 1) < 0.25).length,
  };

  const SortTh = ({ k, label }: { k: SortKey; label: string }) => (
    <th className="text-left px-4 py-3 cursor-pointer select-none hover:bg-muted/70 transition-colors"
      onClick={() => handleSortClick(k)}>
      <div className="flex items-center gap-1 text-xs text-muted-foreground font-medium">
        {label}
        {sortKey === k && <span className="text-primary">{sortDesc ? '↓' : '↑'}</span>}
      </div>
    </th>
  );

  return (
    <div className="p-4 lg:p-6 space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-foreground">{t('admin.students.title')}</h1>
          <p className="text-sm text-muted-foreground">{t('admin.students.count', { count: students.length })}</p>
        </div>
        <div className="flex gap-2">
          <button onClick={() => toast.info('Bulk export coming soon')}
            className="flex items-center gap-1.5 px-3 py-2 border border-border rounded-xl text-sm text-muted-foreground hover:bg-accent">
            <Download className="w-4 h-4" /> {t('admin.students.export')}
          </button>
          <button onClick={() => toast.info('Bulk import coming soon')}
            className="flex items-center gap-1.5 px-3 py-2 bg-primary text-primary-foreground rounded-xl text-sm hover:opacity-90">
            <Upload className="w-4 h-4" /> {t('admin.students.import')}
          </button>
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

      {/* Filters */}
      <div className="flex flex-wrap gap-2">
        <div className="relative flex-1 min-w-40">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground" />
          <input type="text" value={search} onChange={e => handleSearchChange(e.target.value)} placeholder={t('admin.students.searchPlaceholder')}
            className="w-full pl-9 pr-4 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-primary/50" />
        </div>
        <select value={filterGrade} onChange={e => handleGradeChange(e.target.value)}
          className="px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none">
          <option value="all">{t('admin.students.allGrades')}</option>
          {GRADES.map(g => <option key={g} value={g}>{t(GRADE_LABEL_KEYS[g])}</option>)}
        </select>
        <select value={filterStatus} onChange={e => setFilterStatus(e.target.value as any)}
          className="px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none">
          <option value="all">{t('admin.students.allStatuses')}</option>
          <option value="onTrack">{t('admin.students.statusOnTrack')}</option>
          <option value="below50">{t('admin.students.statusBehind')}</option>
          <option value="below25">{t('admin.students.statusCritical')}</option>
        </select>
      </div>

      <div className="text-xs text-muted-foreground">{t('admin.students.count', { count: filtered.length })}</div>

      <ListStateBanner error={error} empty={!loading && filtered.length === 0} emptyLabel="No students" />

      {/* Desktop: sortable table */}
      <div className="hidden lg:block bg-card border border-border rounded-2xl overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-muted/50 border-b border-border">
            <tr>
              <SortTh k="name" label={t('admin.students.sortName')} />
              <SortTh k="grade" label={t('admin.students.sortGrade')} />
              <th className="text-left px-4 py-3 text-xs text-muted-foreground font-medium">{t('admin.students.annualProgress')}</th>
              <SortTh k="completedHours" label={t('admin.students.sortHours')} />
              <SortTh k="timeCoinBalance" label={t('admin.students.sortCoins')} />
              <th className="text-left px-4 py-3 text-xs text-muted-foreground font-medium">{t('admin.students.status')}</th>
              <th className="text-left px-4 py-3 text-xs text-muted-foreground font-medium">{t('admin.students.actions')}</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {filtered.map(student => {
              const annualPct = (student.completedMinutes ?? 0) / (student.annualRequiredMinutes ?? 1);
              const atRisk = annualPct < 0.25;
              const belowHalf = annualPct < 0.5;
              return (
                <tr key={student.id} className={`hover:bg-muted/30 transition-colors ${atRisk ? 'bg-red-50/30 dark:bg-red-900/10' : ''}`}>
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
                  <td className="px-4 py-3 text-sm text-muted-foreground">{t(GRADE_LABEL_KEYS[student.grade])} {student.className}</td>
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
                    {atRisk ? (
                      <span className="flex items-center gap-1 text-xs text-red-600 dark:text-red-400">
                        <AlertCircle className="w-3 h-3" /> {t('admin.students.critical')}
                      </span>
                    ) : belowHalf ? (
                      <span className="text-xs text-yellow-600 dark:text-yellow-400">{t('admin.students.behind')}</span>
                    ) : (
                      <span className="text-xs text-green-600 dark:text-green-400">{t('admin.students.onTrack')}</span>
                    )}
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex gap-1">
                      <button onClick={() => openEdit(student)}
                        className="text-xs px-2 py-1 rounded-lg border border-border hover:bg-accent text-muted-foreground transition-colors flex items-center gap-1">
                        <Pencil className="w-3 h-3" /> {t('admin.students.edit')}
                      </button>
                      <button onClick={() => toast.success(t('admin.students.toastRemindSent', { name: student.name }))}
                        className="text-xs px-2 py-1 rounded-lg border border-border hover:bg-accent text-muted-foreground transition-colors">
                        {t('admin.students.remind')}
                      </button>
                      <button onClick={() => toast.info(t('admin.students.recordsComingSoon'))}
                        className="text-xs px-2 py-1 rounded-lg border border-border hover:bg-accent text-muted-foreground transition-colors">
                        {t('admin.students.records')}
                      </button>
                    </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
        <div className="px-4 pb-4">
          <PageControls page={page} onPageChange={setPage} />
        </div>
      </div>

      {/* Edit student modal */}
      {editStudent && editForm && (
        <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4">
          <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" onClick={() => setEditStudent(null)} />
          <div className="relative bg-card w-full sm:max-w-md sm:rounded-2xl rounded-t-2xl shadow-xl flex flex-col max-h-[90vh]">

            {/* Header */}
            <div className="flex items-center gap-3 px-5 py-4 border-b border-border shrink-0">
              <div className="w-9 h-9 bg-[oklch(0.488_0.243_264.376)] rounded-full flex items-center justify-center shrink-0">
                <span className="text-white text-sm font-medium">{editStudent.name[0]}</span>
              </div>
              <div className="flex-1 min-w-0">
                <h3 className="text-foreground leading-tight">{t('admin.students.editTitle')}</h3>
                <p className="text-xs text-muted-foreground">{editStudent.studentNumber} · {t(GRADE_LABEL_KEYS[editStudent.grade])}</p>
              </div>
              <button onClick={() => setEditStudent(null)}
                className="p-1.5 rounded-lg hover:bg-accent text-muted-foreground transition-colors">
                <X className="w-4 h-4" />
              </button>
            </div>

            {/* Form body */}
            <div className="overflow-y-auto px-5 py-4 space-y-4">

              {/* Identity fields */}
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs text-muted-foreground mb-1.5">{t('admin.students.name')}</label>
                  <input type="text" value={editForm.name}
                    onChange={e => setEditForm(f => f && ({ ...f, name: e.target.value }))}
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
                    className={`w-full px-3 py-2.5 rounded-xl border border-border text-sm ${
                      isAdmin
                        ? 'bg-input-background dark:bg-muted text-foreground focus:outline-none focus:ring-2 focus:ring-[oklch(0.488_0.243_264.376)]/40'
                        : 'bg-muted text-muted-foreground cursor-not-allowed'
                    }`}
                  />
                </div>
              </div>

              <div>
                <label className="block text-xs text-muted-foreground mb-1.5">{t('admin.students.email')}</label>
                <input type="email" value={editForm.email}
                  onChange={e => setEditForm(f => f && ({ ...f, email: e.target.value }))}
                  className="w-full px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-[oklch(0.488_0.243_264.376)]/40" />
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs text-muted-foreground mb-1.5">{t('admin.students.grade')}</label>
                  <select value={editForm.grade}
                    onChange={e => setEditForm(f => f && ({ ...f, grade: e.target.value }))}
                    className="w-full px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-[oklch(0.488_0.243_264.376)]/40">
                    {GRADES.map(g => <option key={g} value={g}>{t(GRADE_LABEL_KEYS[g])}</option>)}
                  </select>
                </div>
                <div>
                  <label className="block text-xs text-muted-foreground mb-1.5">{t('admin.students.class')}</label>
                  <input type="text" value={editForm.className}
                    onChange={e => setEditForm(f => f && ({ ...f, className: e.target.value }))}
                    placeholder={t('admin.students.classPlaceholder')}
                    className="w-full px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-[oklch(0.488_0.243_264.376)]/40" />
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

          return (
            <div key={student.id} className={`bg-card border rounded-xl overflow-hidden ${atRisk ? 'border-red-200 dark:border-red-800' : 'border-border'}`}>
              <button className="w-full p-4 text-left" onClick={() => setExpandedId(isExpanded ? null : student.id)}>
                <div className="flex items-center gap-3">
                  <div className="w-10 h-10 bg-accent rounded-full flex items-center justify-center shrink-0">
                    <span className="text-foreground text-sm font-medium">{student.name[0]}</span>
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 flex-wrap mb-0.5">
                      <span className="text-sm text-foreground font-medium">{student.name}</span>
                      <span className="text-xs text-muted-foreground">{t(GRADE_LABEL_KEYS[student.grade])} {student.className}</span>
                      {atRisk && (
                        <span className="text-xs bg-red-100 dark:bg-red-900/30 text-red-600 dark:text-red-400 px-1.5 py-0.5 rounded flex items-center gap-0.5">
                          <AlertCircle className="w-3 h-3" /> {t('admin.students.critical')}
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
                      <div className="flex justify-between"><span className="text-muted-foreground">{t('admin.students.email')}</span><span className="text-foreground text-xs truncate ml-2">{student.email}</span></div>
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
                  <div className="flex gap-2">
                    <button onClick={() => openEdit(student)}
                      className="flex-1 py-2 rounded-lg border border-border text-xs text-foreground hover:bg-accent transition-colors flex items-center justify-center gap-1">
                      <Pencil className="w-3 h-3" /> {t('admin.students.edit')}
                    </button>
                    <button onClick={() => toast.success(t('admin.students.toastRemindSent', { name: student.name }))}
                      className="flex-1 py-2 rounded-lg border border-border text-xs text-foreground hover:bg-accent transition-colors">
                      {t('admin.students.remind')}
                    </button>
                    <button onClick={() => toast.info(t('admin.students.recordsComingSoon'))}
                      className="flex-1 py-2 rounded-lg border border-border text-xs text-foreground hover:bg-accent transition-colors">
                      {t('admin.students.records')}
                    </button>
                  </div>
                </div>
              )}
            </div>
          );
        })}
        <PageControls page={page} onPageChange={setPage} />
      </div>
    </div>
  );
}
