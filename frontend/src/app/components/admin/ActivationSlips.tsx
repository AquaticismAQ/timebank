import { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { AlertTriangle, KeyRound, Printer, X } from 'lucide-react';
import { toast } from 'sonner';
import { api } from '../../api/client';
import { ApiError, mapApiError } from '../../api/http';
import { useDictionaries } from '../../api/useDictionaries';
import { downloadBlob } from '../../lib/download';
import { formatClassName } from '../../lib/format';

/**
 * The admin entry point for `POST /users/activation-slips` (contract §4.15,
 * `Docs/optimization/10-StudentIdentityAndOnboarding.md` §3.4) — the only way
 * a student ever receives their first credential.
 *
 * **Why it lives in the Import/Export tab.** An admin needs this immediately
 * after importing a class roster (§3.3 creates the pending accounts, and this
 * is what turns them into something a child can hold), and that import is the
 * card directly above. Student Management was the other candidate and was rejected for
 * a reason beyond adjacency: teachers use that page with a scoped view, and
 * §2.10 gives teachers no slip path at all, so putting the control there means
 * hiding a credential-minting affordance by role inside a page built for a
 * wider audience. Here, the whole surrounding tab is already an
 * administrator's.
 *
 * **What the admin must not be able to miss.** Issuing retires whatever code
 * each student holds now (§2.7 — a lost slip has to stop working), so a
 * regeneration by an admin who just wanted "a fresh copy" silently kills paper
 * already in students' hands. That consequence is stated twice: once inline and
 * always visible, before the button is ever pressed, and again — naming the
 * class and the number of students — in a confirm step that cannot be
 * dismissed by clicking the backdrop.
 */

const SLIPS_PER_PAGE_VALUES = [1, 2] as const;

/** §2.8, D-8: a closed choice of 1 or 2, never a free number. The union is the whole rule, matching the backend's enum. */
type SlipsPerPage = (typeof SLIPS_PER_PAGE_VALUES)[number];

/** `GET /users` and `GET /students` both cap at 100 rows per page (`PageQueryArgumentResolver.MAX_PAGE_SIZE`). */
const MAX_PAGE_SIZE = 100;

/**
 * Enough pages to index every account in a school several times over, and a
 * hard stop so a misconfigured deployment cannot turn one preview into an
 * unbounded request loop. Hitting it degrades to "count unavailable" rather
 * than to a confidently wrong number — see `SlipPreview.incomplete` below.
 */
const MAX_INDEX_PAGES = 20;

/** The backend's own ceiling (`CredentialSlipServiceImpl.MAX_CLASS_SIZE`), so a roster read here can never be shorter than the batch it previews. */
const MAX_CLASS_SIZE = 200;

interface AccountIndex {
  /** Account status keyed by the `Student` row the account is linked to. */
  statusByStudentId: Map<string, string>;
  truncated: boolean;
}

interface SlipPreview {
  rosterSize: number;
  /** Everyone who will get a slip: §2.7's `PENDING_ACTIVATION` + `ACTIVATION_EXPIRED`. */
  issuable: number;
  pending: number;
  expired: number;
  activated: number;
  noAccount: number;
  otherStatus: number;
  /** True when the account index ran out of pages before every roster row was resolved, so the numbers above cannot be trusted. */
  incomplete: boolean;
}

/**
 * Builds `studentId -> account status` for one class.
 *
 * <p>This used to page the whole school at {@code MAX_PAGE_SIZE} and join in
 * the browser, because `GET /users` took no filters — the workaround issue 094
 * §2 cited as its reason for existing. It now asks the server for the one class
 * it needs (issue 094 added `filter[classId]`), so the cost no longer scales
 * with the size of the school, and `truncated` can only be reached by a class
 * larger than a page rather than by a school larger than {@code
 * MAX_INDEX_PAGES} pages.</p>
 */
async function loadAccountIndex(classId: string): Promise<AccountIndex> {
  const statusByStudentId = new Map<string, string>();
  let pageNumber = 1;
  let truncated = false;
  for (;;) {
    const result = await api.users.list({
      page: pageNumber,
      pageSize: MAX_PAGE_SIZE,
      filter: { role: 'student', classId },
    });
    for (const user of result.data) {
      if (user.studentId) statusByStudentId.set(user.studentId, user.status);
    }
    if (pageNumber >= result.page.totalPages) break;
    if (pageNumber >= MAX_INDEX_PAGES) { truncated = true; break; }
    pageNumber += 1;
  }
  return { statusByStudentId, truncated };
}

/**
 * The class roster, filtered exactly as the backend filters it
 * (`CredentialSlipServiceImpl.roster`: class + student status `ACTIVE`), so the
 * preview counts the same population the batch will.
 */
async function loadRoster(classId: string): Promise<string[]> {
  const ids: string[] = [];
  let pageNumber = 1;
  for (;;) {
    const result = await api.students.list({
      page: pageNumber,
      pageSize: MAX_PAGE_SIZE,
      filter: { classId, status: 'active' },
    });
    for (const student of result.data) ids.push(student.id);
    if (pageNumber >= result.page.totalPages || ids.length >= MAX_CLASS_SIZE) break;
    pageNumber += 1;
  }
  return ids;
}

export function ActivationSlips() {
  const { t } = useTranslation();
  const { grades, classes } = useDictionaries();

  const [gradeId, setGradeId] = useState('');
  const [classId, setClassId] = useState('');
  const [slipsPerPage, setSlipsPerPage] = useState<SlipsPerPage>(1);

  const [accountIndex, setAccountIndex] = useState<AccountIndex | null>(null);
  const [indexFailed, setIndexFailed] = useState(false);
  // Tagged with the class it belongs to: without the tag, the render between a
  // class change and its effect firing shows the *previous* class's count under
  // the new class's name, which on this screen is exactly the wrong thing to be
  // briefly wrong about.
  const [roster, setRoster] = useState<{ classId: string; ids: string[] } | null>(null);
  const [rosterFailed, setRosterFailed] = useState(false);
  const [loadingPreview, setLoadingPreview] = useState(false);

  const [confirmOpen, setConfirmOpen] = useState(false);
  const [generating, setGenerating] = useState(false);

  // Now keyed on the selected class rather than loaded once for the whole
  // school (issue 094 §7): the index is only ever consulted for the class in
  // the picker, so fetching the rest was work whose only effect was latency.
  const refreshAccountIndex = useCallback(() => {
    if (!classId) { setAccountIndex(null); setIndexFailed(false); return; }
    setIndexFailed(false);
    loadAccountIndex(classId)
      .then(setAccountIndex)
      .catch(() => { setAccountIndex(null); setIndexFailed(true); });
  }, [classId]);

  useEffect(refreshAccountIndex, [refreshAccountIndex]);

  useEffect(() => {
    if (!classId) { setRoster(null); setRosterFailed(false); return; }
    let cancelled = false;
    setLoadingPreview(true);
    setRosterFailed(false);
    loadRoster(classId)
      .then(ids => { if (!cancelled) setRoster({ classId, ids }); })
      .catch(() => { if (!cancelled) { setRoster(null); setRosterFailed(true); } })
      .finally(() => { if (!cancelled) setLoadingPreview(false); });
    return () => { cancelled = true; };
  }, [classId]);

  const availableClasses = useMemo(
    () => classes.filter(c => !gradeId || c.gradeId === gradeId),
    [classes, gradeId],
  );

  const selectedClass = useMemo(() => classes.find(c => c.id === classId), [classes, classId]);
  const selectedClassLabel = selectedClass ? formatClassName(selectedClass.name) : '';

  const preview = useMemo((): SlipPreview | null => {
    if (!roster || roster.classId !== classId || !accountIndex) return null;
    let pending = 0, expired = 0, activated = 0, noAccount = 0, otherStatus = 0;
    for (const studentId of roster.ids) {
      const status = accountIndex.statusByStudentId.get(studentId);
      if (status === undefined) noAccount += 1;
      else if (status === 'pendingActivation') pending += 1;
      else if (status === 'activationExpired') expired += 1;
      else if (status === 'active') activated += 1;
      else otherStatus += 1;
    }
    return {
      rosterSize: roster.ids.length,
      issuable: pending + expired,
      pending, expired, activated, noAccount, otherStatus,
      // A truncated index makes every unresolved row look like "no account",
      // which would understate the slip count. Say so instead of guessing.
      incomplete: accountIndex.truncated && noAccount > 0,
    };
  }, [roster, classId, accountIndex]);

  const previewUnavailable = indexFailed || rosterFailed || (preview?.incomplete ?? false);
  /** Either read is still in flight — the account index is loaded once on mount and can outlast the roster read. */
  const previewPending = classId !== '' && !previewUnavailable && (loadingPreview || preview === null);
  /** Narrowed for the copy that interpolates a number: never show a count that was not actually counted. */
  const countIsKnown = preview !== null && !previewUnavailable;
  const nothingToIssue = countIsKnown && preview.issuable === 0;
  const canGenerate = classId !== '' && !generating && !nothingToIssue && !previewPending;

  const handleGenerate = async () => {
    if (!classId) return;
    setGenerating(true);
    try {
      const { blob, filename } = await api.users.issueActivationSlips(classId, slipsPerPage);
      downloadBlob(blob, filename);
      // The response carries no slip count, so the number in the toast is the
      // one previewed — and when there was no preview to trust, the toast says
      // what happened without inventing a count.
      toast.success(countIsKnown
        ? t('admin.system.slips.toastGenerated', { count: preview.issuable, className: selectedClassLabel })
        : t('admin.system.slips.toastGeneratedUnknown', { className: selectedClassLabel }));
      // Issuing does not move an account's status (an expired account stays
      // expired until it is redeemed), so the counts above are still correct —
      // but the index is re-read anyway, because minutes may have passed since
      // the page was opened and a student may have activated in between.
      refreshAccountIndex();
    } catch (err) {
      if (err instanceof ApiError) {
        // The server's messages are English literals, not translated copy
        // (`ApiException.stateConflict("Every student in this class has...")`),
        // so the four outcomes worth naming get their own bilingual text and
        // everything else falls back to what the server said.
        if (err.code === 'FORBIDDEN') toast.error(t('admin.system.slips.errorForbidden'));
        else if (err.code === 'RESOURCE_NOT_FOUND') toast.error(t('admin.system.slips.errorClassNotFound'));
        // Not a failure: a class where everyone has already activated is the
        // system working. It is also the one case where nothing was retired,
        // which is what an admin needs to hear.
        else if (err.code === 'STATE_CONFLICT') toast.info(t('admin.system.slips.errorAllActivated'));
        else if (err.code === 'INVALID_REQUEST') toast.error(t('admin.system.slips.errorRejected', { message: err.message }));
        else toast.error(mapApiError(err).message);
      } else {
        toast.error(t('common.unknownError'));
      }
    } finally {
      setGenerating(false);
      // Closed on every outcome, not just success: three of the four refusals
      // worth naming are definitive (not an admin, no such class, everyone has
      // already activated), and leaving the confirm open after one of those
      // invites a retry that cannot do anything but fail the same way. The
      // toast carries the outcome either way.
      setConfirmOpen(false);
    }
  };

  const breakdownRows: { key: string; label: string; value: number }[] = preview ? [
    { key: 'roster', label: t('admin.system.slips.rowRoster'), value: preview.rosterSize },
    { key: 'pending', label: t('admin.system.slips.rowPending'), value: preview.pending },
    { key: 'expired', label: t('admin.system.slips.rowExpired'), value: preview.expired },
    { key: 'activated', label: t('admin.system.slips.rowActivated'), value: preview.activated },
    { key: 'noAccount', label: t('admin.system.slips.rowNoAccount'), value: preview.noAccount },
    { key: 'other', label: t('admin.system.slips.rowOther'), value: preview.otherStatus },
  ].filter(row => row.key === 'roster' || row.value > 0) : [];

  return (
    <div className="bg-card border border-border rounded-2xl p-5 sm:col-span-2">
      <KeyRound className="w-8 h-8 text-amber-500 mb-3" />
      <h3 className="text-foreground mb-1">{t('admin.system.slips.title')}</h3>
      <p className="text-xs text-muted-foreground mb-4">{t('admin.system.slips.desc')}</p>

      <div className="space-y-4">
        {/* Class picker — stacked on a phone, side by side from sm: up. */}
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
          <div>
            <label className="block text-xs text-muted-foreground mb-1.5" htmlFor="slips-grade">
              {t('admin.system.slips.gradeLabel')}
            </label>
            <select id="slips-grade" value={gradeId}
              onChange={e => { setGradeId(e.target.value); setClassId(''); }}
              className="w-full px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-[oklch(0.488_0.243_264.376)]/40">
              <option value="">{t('admin.system.slips.selectGrade')}</option>
              {grades.map(g => <option key={g.id} value={g.id}>{g.name}</option>)}
            </select>
          </div>
          <div>
            <label className="block text-xs text-muted-foreground mb-1.5" htmlFor="slips-class">
              {t('admin.system.slips.classLabel')}
            </label>
            <select id="slips-class" value={classId} onChange={e => setClassId(e.target.value)}
              className="w-full px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-[oklch(0.488_0.243_264.376)]/40">
              <option value="">{t('admin.system.slips.selectClass')}</option>
              {availableClasses.map(c => <option key={c.id} value={c.id}>{formatClassName(c.name)}</option>)}
            </select>
          </div>
        </div>

        {/* How many slips this will actually produce, before anything is minted. */}
        {!classId && (
          <p className="text-xs text-muted-foreground">{t('admin.system.slips.pickClassHint')}</p>
        )}
        {previewPending && (
          <p className="text-xs text-muted-foreground">{t('admin.system.slips.counting')}</p>
        )}
        {classId && previewUnavailable && (
          <p className="text-xs text-muted-foreground bg-muted rounded-xl px-3 py-2.5">
            {t('admin.system.slips.countUnavailable')}
          </p>
        )}
        {classId && countIsKnown && (
          nothingToIssue ? (
            <p className="text-xs text-foreground bg-muted rounded-xl px-3 py-2.5">
              {t('admin.system.slips.nothingToIssue')}
            </p>
          ) : (
            <div className="rounded-xl border border-border bg-muted/40 px-4 py-3 space-y-2">
              <div className="flex items-baseline gap-2">
                <span className="text-2xl font-semibold text-foreground">{preview.issuable}</span>
                <span className="text-sm text-muted-foreground">{t('admin.system.slips.slipsToPrint')}</span>
              </div>
              <dl className="space-y-1">
                {breakdownRows.map(row => (
                  <div key={row.key} className="flex items-baseline justify-between gap-3 text-xs">
                    <dt className="text-muted-foreground">{row.label}</dt>
                    <dd className="text-foreground tabular-nums">{row.value}</dd>
                  </div>
                ))}
              </dl>
              <p className="text-xs text-muted-foreground">{t('admin.system.slips.fewerNote')}</p>
            </div>
          )
        )}

        {/* §2.8: a closed choice, presented as one. */}
        <div>
          <span className="block text-xs text-muted-foreground mb-1.5">{t('admin.system.slips.perPageLabel')}</span>
          <div className="grid grid-cols-2 gap-2">
            {SLIPS_PER_PAGE_VALUES.map(value => (
              <button key={value} type="button" onClick={() => setSlipsPerPage(value)}
                aria-pressed={slipsPerPage === value}
                className={`py-2.5 rounded-xl border text-sm transition-colors ${
                  slipsPerPage === value
                    ? 'border-[oklch(0.488_0.243_264.376)] bg-[oklch(0.488_0.243_264.376)]/10 text-foreground'
                    : 'border-border text-muted-foreground hover:bg-accent'
                }`}>
                {t(value === 1 ? 'admin.system.slips.perPageOne' : 'admin.system.slips.perPageTwo')}
              </button>
            ))}
          </div>
          <p className="mt-1.5 text-xs text-muted-foreground">{t('admin.system.slips.perPageHint')}</p>
        </div>

        {/* The consequence, stated before the button is ever pressed — not only
            in the confirm step, where an admin who has already decided reads
            past it. */}
        <div className="flex gap-2.5 rounded-xl border border-amber-200 dark:border-amber-800 bg-amber-50 dark:bg-amber-900/20 px-3 py-2.5">
          <AlertTriangle className="w-4 h-4 text-amber-600 dark:text-amber-400 shrink-0 mt-0.5" />
          <div className="space-y-1.5 text-xs text-amber-900 dark:text-amber-200">
            <p>{t('admin.system.slips.retireWarning')}</p>
            <p>{t('admin.system.slips.expiryNote')}</p>
          </div>
        </div>

        <button type="button" onClick={() => setConfirmOpen(true)} disabled={!canGenerate}
          className="w-full py-2.5 bg-[oklch(0.488_0.243_264.376)] text-white rounded-xl text-sm hover:opacity-90 disabled:opacity-40 flex items-center justify-center gap-2">
          <Printer className="w-4 h-4" />
          {t(generating ? 'admin.system.slips.generating' : 'admin.system.slips.generate')}
        </button>

        <p className="text-xs text-muted-foreground">{t('admin.system.slips.adminOnlyNote')}</p>
      </div>

      {confirmOpen && (
        <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4">
          {/* No backdrop dismissal: a stray tap must not be able to stand in for
              a decision that retires credentials already handed out. */}
          <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" />
          <div className="relative bg-card w-full sm:max-w-md sm:rounded-2xl rounded-t-2xl shadow-xl flex flex-col max-h-[92vh]">
            <div className="flex items-center gap-3 px-5 py-4 border-b border-border shrink-0">
              <AlertTriangle className="w-5 h-5 text-amber-500 shrink-0" />
              <h3 className="flex-1 text-foreground text-sm">{t('admin.system.slips.confirmTitle')}</h3>
              <button type="button" onClick={() => setConfirmOpen(false)} disabled={generating}
                className="p-1.5 rounded-lg hover:bg-accent text-muted-foreground disabled:opacity-40">
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="overflow-y-auto px-5 py-4 space-y-3 text-sm">
              <p className="text-foreground">
                {countIsKnown
                  ? t('admin.system.slips.confirmLead', { count: preview.issuable, className: selectedClassLabel })
                  : t('admin.system.slips.confirmLeadUnknown', { className: selectedClassLabel })}
              </p>
              <p className="rounded-xl border border-amber-200 dark:border-amber-800 bg-amber-50 dark:bg-amber-900/20 px-3 py-2.5 text-xs text-amber-900 dark:text-amber-200">
                {t('admin.system.slips.confirmRetire')}
              </p>
              {countIsKnown && preview.activated > 0 && (
                <p className="text-xs text-muted-foreground">
                  {t('admin.system.slips.confirmSpared', { count: preview.activated })}
                </p>
              )}
              <p className="text-xs text-muted-foreground">{t('admin.system.slips.expiryNote')}</p>
            </div>

            <div className="px-5 py-4 border-t border-border shrink-0 flex flex-col-reverse sm:flex-row gap-2">
              <button type="button" onClick={() => setConfirmOpen(false)} disabled={generating}
                className="flex-1 py-2.5 rounded-xl border border-border text-sm text-foreground hover:bg-accent disabled:opacity-40">
                {t('common.cancel')}
              </button>
              <button type="button" onClick={handleGenerate} disabled={generating}
                className="flex-1 py-2.5 rounded-xl bg-amber-600 text-white text-sm hover:opacity-90 disabled:opacity-40">
                {t(generating ? 'admin.system.slips.generating' : 'admin.system.slips.confirmAction')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
