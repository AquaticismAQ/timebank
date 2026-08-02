import { useState, useEffect, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { Search, ChevronDown, ChevronUp, Download, Upload, AlertCircle, Pencil, X, Save } from 'lucide-react';
import { GRADES, GRADE_LABEL_KEYS, type Student } from '../../mock/data';
import { toast } from 'sonner';
import { useAuth, useLogs } from '../../App';
import { api } from '../../api/client';

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
  const [students, setStudents] = useState<Student[]>([]);

  useEffect(() => {
    api.students.list().then(setStudents).catch(() => {});
  }, []);

  const [search, setSearch] = useState('');
  const [filterGrade, setFilterGrade] = useState('all');
  const [filterStatus, setFilterStatus] = useState<'all' | 'onTrack' | 'below50' | 'below25'>('all');
  const [sortKey, setSortKey] = useState<SortKey>('name');
  const [sortDesc, setSortDesc] = useState(false);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [editStudent, setEditStudent] = useState<Student | null>(null);
  const [editForm, setEditForm] = useState<EditForm | null>(null);

  const openEdit = (s: Student) => {
    setEditStudent(s);
    setEditForm({
      name: s.name,
      email: s.email,
      studentNumber: s.studentNumber,
      grade: s.grade,
      className: s.className,
      completedHours: s.completedHours,
      timeCoinBalance: s.timeCoinBalance,
      memo: '',
    });
  };

  const saveEdit = () => {
    if (!editStudent || !editForm) return;
    if (editForm.completedHours < 0) { toast.error('Completed hours cannot be negative'); return; }
    if (editForm.timeCoinBalance < 0) { toast.error('Time coins cannot be negative'); return; }

    const changes: string[] = [];
    if (editForm.studentNumber !== editStudent.studentNumber)
      changes.push(`Student No. ${editStudent.studentNumber}→${editForm.studentNumber}`);
    if (editForm.completedHours !== editStudent.completedHours)
      changes.push(`Completed hours ${editStudent.completedHours}h→${editForm.completedHours}h`);
    if (editForm.timeCoinBalance !== editStudent.timeCoinBalance)
      changes.push(`Time coins ${editStudent.timeCoinBalance}→${editForm.timeCoinBalance}`);
    if (editForm.name !== editStudent.name) changes.push(`Name changed to "${editForm.name}"`);
    if (editForm.grade !== editStudent.grade || editForm.className !== editStudent.className)
      changes.push(`Class changed to ${editForm.grade} ${editForm.className}`);

    setStudents(prev => prev.map(s => s.id === editStudent.id ? { ...s, ...editForm } : s));

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
  };

  const filtered = useMemo(() => {
    let items = students.filter(s => {
      const matchSearch = !search || s.name.includes(search) || s.studentNumber.includes(search) || s.className.includes(search);
      const matchGrade = filterGrade === 'all' || s.grade === filterGrade;
      const pct = s.completedHours / s.annualRequiredHours;
      const matchStatus = filterStatus === 'all' ? true :
        filterStatus === 'onTrack' ? pct >= 0.5 :
        filterStatus === 'below50' ? pct < 0.5 :
        pct < 0.25;
      return matchSearch && matchGrade && matchStatus;
    });
    return [...items].sort((a, b) => {
      const av = a[sortKey] as string | number;
      const bv = b[sortKey] as string | number;
      return sortDesc ? (bv > av ? 1 : -1) : (av > bv ? 1 : -1);
    });
  }, [search, filterGrade, filterStatus, sortKey, sortDesc]);

  const stats = {
    total: students.length,
    onTrack: students.filter(s => (s.completedHours / s.annualRequiredHours) >= 0.5).length,
    atRisk: students.filter(s => (s.completedHours / s.annualRequiredHours) < 0.25).length,
  };

  const SortTh = ({ k, label }: { k: SortKey; label: string }) => (
    <th className="text-left px-4 py-3 cursor-pointer select-none hover:bg-muted/70 transition-colors"
      onClick={() => { if (sortKey === k) setSortDesc(!sortDesc); else { setSortKey(k); setSortDesc(false); } }}>
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
          <input type="text" value={search} onChange={e => setSearch(e.target.value)} placeholder={t('admin.students.searchPlaceholder')}
            className="w-full pl-9 pr-4 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-primary/50" />
        </div>
        <select value={filterGrade} onChange={e => setFilterGrade(e.target.value)}
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
              const annualPct = student.completedHours / student.annualRequiredHours;
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
                        <ProgressBar value={student.completedHours} max={student.annualRequiredHours}
                          color={atRisk ? 'bg-red-500' : belowHalf ? 'bg-yellow-500' : 'bg-green-500'} />
                      </div>
                      <span className={`text-xs font-medium w-8 text-right shrink-0 ${atRisk ? 'text-red-500' : belowHalf ? 'text-yellow-500' : 'text-green-500'}`}>
                        {Math.round(annualPct * 100)}%
                      </span>
                    </div>
                  </td>
                  <td className="px-4 py-3 text-sm text-foreground">{student.completedHours}h</td>
                  <td className="px-4 py-3 text-sm text-foreground">{student.timeCoinBalance}</td>
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
          const annualPct = student.completedHours / student.annualRequiredHours;
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
                      {student.completedHours}h / {student.annualRequiredHours}h · {student.timeCoinBalance} {t('admin.students.coinsUnit')}
                    </div>
                    <ProgressBar value={student.completedHours} max={student.annualRequiredHours}
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
                      <div className="flex justify-between"><span className="text-muted-foreground">{t('admin.students.completedHours')}</span><span className="text-green-600 dark:text-green-400">{student.completedHours}h</span></div>
                      <div className="flex justify-between"><span className="text-muted-foreground">{t('admin.students.pendingHours')}</span><span className="text-blue-500">{student.pendingHours}h</span></div>
                    </div>
                    <div className="space-y-1.5">
                      <div className="flex justify-between"><span className="text-muted-foreground">{t('admin.students.annualRequiredHours')}</span><span className="text-foreground">{student.annualRequiredHours}h</span></div>
                      <div className="flex justify-between"><span className="text-muted-foreground">{t('admin.students.graduationRequiredHours')}</span><span className="text-foreground">{student.stageRequiredHours}h</span></div>
                      <div className="flex justify-between"><span className="text-muted-foreground">{t('admin.students.timeCoins')}</span><span className="text-foreground">{student.timeCoinBalance}</span></div>
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
      </div>
    </div>
  );
}
