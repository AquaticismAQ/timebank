import { useState, useRef, useEffect, useLayoutEffect } from 'react';
import { Plus, Search, X, FileText, AlertTriangle, ChevronDown, ChevronUp, Upload, Send, Trash2, Pencil, RotateCcw, Gavel, Eye } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { RECORD_STATUS_LABELS } from '../../lib/statusLabels';
import { type RecordStatus, type ServiceRecord, type EvidenceFile, type Application, type Project } from '../../api/types';
import { localDateTimeToISO, isoToLocalDateTime, addOneDay } from '../../lib/datetime';
import { formatCoins, minutesToHours, formatGradeClass } from '../../lib/format';
import { toast } from 'sonner';
import { api } from '../../api/client';
import { ApiError, mapApiError, newIdempotencyKey } from '../../api/http';
import { useFieldErrors, FieldError, handleMutationError } from '../../api/useFieldErrors';
import { usePagedApi } from '../../api/useApi';
import { useDictionaries } from '../../api/useDictionaries';
import { PageControls } from '../PageControls';
import { ListStateBanner } from '../ListStateBanner';
import { EvidenceLink } from '../EvidenceLink';
import { SearchPicker, type SearchPickerOption } from '../SearchPicker';
import { ConfirmDeleteDialog } from '../shared/ConfirmDeleteDialog';
import { ServiceRecordDetail } from './ServiceRecordDetail';
import { OverlapBlockedDialog } from '../shared/OverlapBlockedDialog';
import { reportLocalizedValidity, clearCustomValidity } from '../../lib/validity';
import { TimeInput } from '../shared/TimeInput';
import { CaseFilingModal } from './CaseFilingModal';
import { ListSearchInput } from '../ListSearchInput';

const colorMap: Record<string, string> = {
  gray: 'bg-muted text-muted-foreground',
  blue: 'bg-blue-100 dark:bg-blue-900/30 text-blue-700 dark:text-blue-400',
  yellow: 'bg-yellow-100 dark:bg-yellow-900/30 text-yellow-700 dark:text-yellow-400',
  green: 'bg-green-100 dark:bg-green-900/30 text-green-700 dark:text-green-400',
  orange: 'bg-orange-100 dark:bg-orange-900/30 text-orange-700 dark:text-orange-400',
  red: 'bg-red-100 dark:bg-red-900/30 text-red-700 dark:text-red-400',
  purple: 'bg-purple-100 dark:bg-purple-900/30 text-purple-700 dark:text-purple-400',
};

// Row-action color per action — icon + label, bordered, never filled —
// matching the convention `StudentList.tsx`/`SystemAdmin.tsx`/`RoleManagement.tsx`
// established (`ACTION_COLORS`/`ACTION_BTN_BLUE`) for Admin Portal tables.
const ACTION_COLORS = {
  blue: 'border-blue-200 dark:border-blue-800 text-blue-600 dark:text-blue-400 hover:bg-blue-50 dark:hover:bg-blue-900/20',
  green: 'border-green-200 dark:border-green-800 text-green-600 dark:text-green-400 hover:bg-green-50 dark:hover:bg-green-900/20',
  red: 'border-red-200 dark:border-red-800 text-red-600 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-900/20',
  amber: 'border-amber-200 dark:border-amber-800 text-amber-600 dark:text-amber-400 hover:bg-amber-50 dark:hover:bg-amber-900/20',
  purple: 'border-purple-200 dark:border-purple-800 text-purple-600 dark:text-purple-400 hover:bg-purple-50 dark:hover:bg-purple-900/20',
} as const;

type ViewMode = 'list' | 'new';
type SupervisorKind = '' | 'internalUser' | 'student' | 'externalManual';

const STEP_KEYS = ['source', 'serviceInfo', 'evidence', 'confirmSubmit'];

// Same fields ServiceRecordController's SORTABLE set allows (id/serviceDate/status/createdAt) —
// all 3 are real backend columns, unlike ProjectCenter's enrolledCount, so no client-only split needed.
type SortKey = 'serviceDate' | 'createdAt' | 'status';
const SORT_KEYS: SortKey[] = ['serviceDate', 'createdAt', 'status'];

export function ServiceRecords() {
  const { t } = useTranslation();
  const { serviceCategories } = useDictionaries();
  // Newest first by *creation*, not by service date. A student's own list is a
  // work queue — the record they just filed, or the one that came back to them,
  // is what they opened this page for. Sorting by service date instead buries a
  // record written up for last month's session below every record for a more
  // recent one, however long ago it was entered. Service date is still one click
  // away on the sort row.
  const { items: records, page, loading, error, params, setFilter, setSearch, setSort, setPage, setPageSize, refetch } =
    usePagedApi(api.serviceRecords.list, { sort: '-createdAt' });
  const [view, setView] = useState<ViewMode>('list');
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [step, setStep] = useState(0);
  const [saving, setSaving] = useState(false);
  const submitKeyRef = useRef(newIdempotencyKey());
  const fieldErrors = useFieldErrors();

  // Case filing (appeal/supplement) — 06-StudentCaseFiling.md. `caseFiling.record` is
  // null for a standalone/unattached supplement request (§2.3), set for a per-record
  // Appeal or Supplement button.
  const [caseFiling, setCaseFiling] = useState<{ type: 'appeal' | 'supplement'; record: ServiceRecord | null } | null>(null);

  // Evidence upload — the "evidence" step existed as a purely decorative
  // dropzone (no <input>, no handler) even though `api.files` was fully
  // wired in client.ts; submit requires at least one evidence file
  // (`ServiceRecordServiceImpl`'s EVIDENCE_REQUIRED check), so no record
  // could ever actually be submitted through the real UI. Caught only by
  // driving the browser through the flow, not by source review.
  const [evidenceFiles, setEvidenceFiles] = useState<EvidenceFile[]>([]);
  const [uploading, setUploading] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const formRef = useRef<HTMLFormElement>(null);

  // I3.2: reject obviously-bad files before ever hitting the network
  // (matches the real backend limits — FileServiceImpl's 20MB default and
  // its image/jpeg|png + application/pdf allow-list — rather than letting
  // every oversized/wrong-type file round-trip just to fail).
  const MAX_EVIDENCE_BYTES = 20 * 1024 * 1024;
  const ALLOWED_EVIDENCE_TYPES = ['image/jpeg', 'image/png', 'application/pdf'];

  const handleFileSelect = async (fileList: FileList | null) => {
    if (!fileList || fileList.length === 0) return;
    setUploading(true);
    try {
      for (const file of Array.from(fileList)) {
        if (file.size > MAX_EVIDENCE_BYTES) {
          toast.error(t('student.records.evidenceTooLarge', { name: file.name }));
          continue;
        }
        if (!ALLOWED_EVIDENCE_TYPES.includes(file.type)) {
          toast.error(t('student.records.evidenceBadType', { name: file.name }));
          continue;
        }
        try {
          const uploaded = await api.files.upload(file, 'serviceRecordEvidence');
          setEvidenceFiles(prev => [...prev, uploaded]);
        } catch (err) {
          // A scan rejection (e.g. content doesn't match the declared type)
          // surfaces here per-file, not as a mysterious submit-time failure.
          const message = err instanceof ApiError ? mapApiError(err).message : t('common.unknownError');
          toast.error(t('student.records.evidenceRejected', { name: file.name, reason: message }));
        }
      }
    } finally {
      setUploading(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  };

  const removeEvidenceFile = (id: string) => setEvidenceFiles(prev => prev.filter(f => f.id !== id));

  // New record form state — issue 03: only 'project'/'self' are selectable for a
  // fresh record ('external' only ever appears here when resubmitting an existing
  // historical record, never as a chooseable option — see sourceOptions below).
  const EMPTY_FORM = {
    // Reported directly by the user: "School Project" should be the default
    // selection, since most records are project-sourced (issue 062).
    sourceType: 'project' as 'project' | 'external' | 'self',
    projectId: '',
    serviceDate: '',
    startTime: '',
    endTime: '',
    endsNextDay: false,
    location: '',
    organizationName: '',
    categoryId: 'cat_community',
    description: '',
    supervisorKind: '' as SupervisorKind,
    supervisorSubjectId: '',
    supervisorName: '',
    supervisorContact: '',
    includesTravelTime: false,
    travelTimeReason: '',
  };
  const [formData, setFormData] = useState(EMPTY_FORM);
  const [supervisorOption, setSupervisorOption] = useState<SearchPickerOption[]>([]);

  // Gates the "Next" button on the current step's own required fields being
  // filled — only they're mounted at any given time (conditional per-step
  // rendering), so `checkValidity()` here naturally scopes to just them,
  // the same way `reportValidity()` on click already does below.
  const [nextEnabled, setNextEnabled] = useState(false);
  // `createDraft`/`updateDraft` require serviceDate/startAt/endAt even for a
  // draft, and those live on the Service Info step — so "Save Draft" is only
  // a real action once they exist. Before that it 422s
  // (`serviceDate/startAt/endAt: NOTNULL`), which is why it was hidden on the
  // source step entirely and is disabled until the window is filled in.
  const canSaveDraft = !!formData.serviceDate && !!formData.startTime && !!formData.endTime;
  useLayoutEffect(() => {
    // Retract any message a failed "Next" left behind first, or it would count
    // as a failure here forever and keep the button disabled (see `validity.ts`
    // — `TimeInput`'s React-driven proxy never fires the event that clears it).
    clearCustomValidity(formRef.current);
    setNextEnabled(formRef.current?.checkValidity() ?? false);
  }, [step, formData]);

  // Distinguishes "resubmitting a returned record" (PATCH the existing
  // record, then submit) from "brand new record" (POST) — without this,
  // the resubmit buttons below had nothing to route through but `create()`,
  // silently producing a duplicate record instead of fixing the returned one.
  const [editing, setEditing] = useState<{ id: string; version: number; status?: RecordStatus } | null>(null);

  // PROJECT source: the student's own confirmed registrations (issue 03 §3.1 — only
  // a project the student is actually CONFIRMED for is selectable), loaded once per
  // "new record" session rather than per-keystroke since a student's own confirmed
  // project list is small and doesn't need a search-as-you-type picker.
  const [confirmedProjects, setConfirmedProjects] = useState<Application[]>([]);
  useEffect(() => {
    if (view !== 'new') return;
    api.applications.list({ filter: { status: 'confirmed' }, pageSize: 100 })
      .then(res => setConfirmedProjects(res.data))
      .catch(() => setConfirmedProjects([]));
  }, [view]);

  // The full project detail (host/partner org, owner-as-supervisor, category, location,
  // time range) is display-only here — the backend re-derives and snapshots all of it
  // itself at create/submit time regardless of what this fetch shows (§3.1/§5.2).
  const [selectedProject, setSelectedProject] = useState<Project | null>(null);
  useEffect(() => {
    if (formData.sourceType !== 'project' || !formData.projectId) { setSelectedProject(null); return; }
    let cancelled = false;
    api.projects.get(formData.projectId).then(p => { if (!cancelled) setSelectedProject(p); }).catch(() => { if (!cancelled) setSelectedProject(null); });
    return () => { cancelled = true; };
  }, [formData.sourceType, formData.projectId]);

  // Reported directly by the user: the record's date/start/end time should
  // follow the selected project's own activity window automatically —
  // always, and every time the pick changes, not just once. Skipped
  // entirely while editing an existing record: its real saved date/time
  // are what should show, not the project's defaults (issue 062).
  //
  // Also sets `endsNextDay` when the project's own end date differs from
  // its start date (issue 063) — without this, e.g. a project spanning
  // 16:00 to a later day's 01:00 autofills a start/end time pair that
  // *looks* like a same-day 16:00→01:00 range, which is a negative
  // duration; the duration preview below correctly hides a negative value,
  // so it silently never appeared until the student happened to toggle
  // this checkbox themselves with no indication that was the reason.
  useEffect(() => {
    if (!selectedProject || editing) return;
    const start = isoToLocalDateTime(selectedProject.startAt);
    const end = isoToLocalDateTime(selectedProject.endAt);
    setFormData(f => ({ ...f, serviceDate: start.date, startTime: start.time, endTime: end.time, endsNextDay: start.date !== end.date }));
  }, [selectedProject, editing]);

  const durationMinutes = formData.startTime && formData.endTime
    ? Math.round((new Date(`2000-01-0${formData.endsNextDay ? 2 : 1}T${formData.endTime}`).getTime() - new Date('2000-01-01T' + formData.startTime).getTime()) / 60000)
    : null;

  const openNewRecord = () => {
    setEditing(null);
    setFormData(EMPTY_FORM);
    setEvidenceFiles([]);
    setSupervisorOption([]);
    setSelectedProject(null);
    setStep(0);
    setView('new');
  };

  const openResubmit = async (rec: ServiceRecord) => {
    await openEdit(rec, t('student.records.loadedReturned'));
  };

  const openEditDraft = async (rec: ServiceRecord) => {
    await openEdit(rec, t('student.records.draftLoaded'));
  };

  const openEdit = async (rec: ServiceRecord, toastMsg: string) => {
    // `list()`'s real-branch mapping always returns `evidenceFiles: []` (no
    // denormalized name/size/url without a per-file `GET /files/{id}` —
    // see `fromServiceRecordDto`'s own note in client.ts); only `.get(id)`
    // resolves them. Pre-filling straight from the list row would silently
    // drop the student's already-uploaded evidence every time.
    let full: ServiceRecord;
    try {
      full = await api.serviceRecords.get(rec.id);
    } catch (err) {
      if (err instanceof ApiError) toast.error(mapApiError(err).message);
      else toast.error(t('common.unknownError'));
      return;
    }
    setEditing({ id: full.id, version: full.version, status: full.status });
    const start = isoToLocalDateTime(full.startAt);
    const end = isoToLocalDateTime(full.endAt);
    setFormData({
      sourceType: full.sourceType,
      projectId: full.projectId || '',
      serviceDate: full.serviceDate || start.date,
      startTime: start.time,
      endTime: end.time,
      endsNextDay: end.date !== start.date,
      location: full.location,
      organizationName: full.organizationName,
      categoryId: full.serviceCategoryId,
      description: full.description,
      supervisorKind: (full.supervisorKind as SupervisorKind) || '',
      supervisorSubjectId: full.supervisorSubjectId || '',
      supervisorName: full.supervisorName,
      supervisorContact: full.supervisorContact,
      includesTravelTime: full.includesTravelTime ?? false,
      travelTimeReason: full.travelTimeReason || '',
    });
    setSupervisorOption(
      (full.supervisorKind === 'internalUser' || full.supervisorKind === 'student') && full.supervisorSubjectId
        ? [{ id: full.supervisorSubjectId, label: full.supervisorName }]
        : [],
    );
    setEvidenceFiles(full.evidenceFiles);
    setStep(0);
    setView('new');
    toast.info(toastMsg);
  };

  const buildRecordPayload = () => {
    const endDate = formData.endsNextDay ? addOneDay(formData.serviceDate) : formData.serviceDate;
    const startTime = formData.startTime ? localDateTimeToISO(formData.serviceDate, formData.startTime) : undefined;
    const endTime = formData.endTime ? localDateTimeToISO(endDate, formData.endTime) : undefined;
    return {
      sourceType: formData.sourceType,
      projectId: formData.sourceType === 'project' ? formData.projectId : undefined,
      serviceDate: formData.serviceDate,
      startAt: startTime,
      endAt: endTime,
      location: formData.location,
      organizationName: formData.organizationName,
      serviceCategoryId: formData.categoryId,
      description: formData.description,
      supervisorKind: formData.supervisorKind || undefined,
      supervisorSubjectId: formData.supervisorSubjectId || undefined,
      supervisorName: formData.supervisorName,
      supervisorContact: formData.supervisorContact,
      evidenceFiles,
      includesTravelTime: formData.includesTravelTime,
      travelTimeReason: formData.travelTimeReason || undefined,
    };
  };

  // `editing` set -> PATCH the returned record being resubmitted; unset -> a fresh POST.
  const saveRecord = (): Promise<ServiceRecord> => editing
    ? api.serviceRecords.update(editing.id, { ...buildRecordPayload(), version: editing.version })
    : api.serviceRecords.create(buildRecordPayload());

  // Issue 069: an overlap with one of the student's own non-draft records is a
  // hard block at submit (it used to sail through and reach the reviewer flagged
  // as an anomaly). It gets a dialog rather than the usual inline field error,
  // because by this point the wizard has already *created* the draft — so the
  // student has a real decision to make about it, not just a field to correct.
  // No `message` is carried from the server: every submission-validation message
  // in `runSubmissionValidation` is a hardcoded English literal regardless of
  // `Accept-Language`, so this dialog renders its own translated copy instead.
  const [overlapBlock, setOverlapBlock] = useState<
    { recordId: string; canDelete: boolean; attempted: { date: string; startTime: string; endTime: string; endDate: string }; conflict: ServiceRecord | null } | null
  >(null);

  const overlapErrorOf = (err: unknown) =>
    err instanceof ApiError ? err.fieldErrors?.find(f => f.code === 'OVERLAPPING_RECORD') : undefined;

  /**
   * The server names the clashing record by id only (see `OVERLAPPING_RECORD_ID`
   * in `runSubmissionValidation`); resolve it here so the dialog can show *which*
   * record and *when*, formatted in local time. A failed lookup is not fatal —
   * the dialog still blocks and explains, just without the comparison rows.
   */
  const openOverlapBlock = async (
    err: unknown,
    recordId: string,
    canDelete: boolean,
    attempted: { date: string; startTime: string; endTime: string; endDate: string },
  ) => {
    const conflictId = err instanceof ApiError
      ? err.fieldErrors?.find(f => f.code === 'OVERLAPPING_RECORD_ID')?.message
      : undefined;
    setOverlapBlock({ recordId, canDelete, attempted, conflict: null });
    if (!conflictId) return;
    try {
      const conflict = await api.serviceRecords.get(conflictId);
      setOverlapBlock(prev => (prev && prev.recordId === recordId ? { ...prev, conflict } : prev));
    } catch {
      // Leave `conflict` null — the block itself still stands.
    }
  };

  /** Wizard path: the attempted window is whatever is in the form right now. */
  const openOverlapBlockFromForm = (err: unknown, recordId: string, canDelete: boolean) =>
    openOverlapBlock(err, recordId, canDelete, {
      date: formData.serviceDate,
      startTime: formData.startTime,
      endTime: formData.endTime,
      endDate: formData.endsNextDay ? addOneDay(formData.serviceDate) : formData.serviceDate,
    });

  /** List path: the wizard is closed, so the attempted window comes off the stored record. */
  const openOverlapBlockFromRow = (err: unknown, recordId: string) => {
    const row = records.find(r => r.id === recordId);
    const start = row ? isoToLocalDateTime(row.startAt) : null;
    const end = row ? isoToLocalDateTime(row.endAt) : null;
    return openOverlapBlock(err, recordId, true, {
      date: start?.date ?? '',
      startTime: start?.time ?? '',
      endTime: end?.time ?? '',
      endDate: end?.date ?? '',
    });
  };

  const handleSubmitRecord = async (e: React.FormEvent) => {
    e.preventDefault();
    // The form is `noValidate`, so this is the check — the browser no longer
    // makes one of its own on submit (issue 118).
    if (!reportLocalizedValidity(formRef.current, t)) return;
    setSaving(true);
    let savedRecordId: string | null = null;
    try {
      const record = await saveRecord();
      savedRecordId = record.id;
      // A submit-time-only validation failure below must not leave `editing`
      // holding the pre-save version — a retry's PATCH would send it and get
      // a spurious 409 from the server, which has already moved on (issue 014).
      if (editing) setEditing({ id: record.id, version: record.version, status: editing.status });
      await api.serviceRecords.submit(record.id, submitKeyRef.current);
      submitKeyRef.current = newIdempotencyKey();
      fieldErrors.clearAll();
      toast.success(t('student.records.submitSuccess'));
      setView('list');
      setStep(0);
      setEvidenceFiles([]);
      setEditing(null);
      refetch();
    } catch (err) {
      const overlap = overlapErrorOf(err);
      if (overlap && savedRecordId) {
        // A resubmitted record is RETURNED, not DRAFT — `deleteDraft` only
        // permits deleting a draft (issue 059), so the delete choice is
        // genuinely unavailable there rather than merely hidden.
        await openOverlapBlockFromForm(err, savedRecordId, editing?.status !== 'returned');
      } else {
        handleMutationError(err, { fieldErrors, fallbackMessage: t('common.unknownError') });
      }
    } finally {
      setSaving(false);
    }
  };

  /** Shared by both overlap-dialog choices: either way the wizard closes and the list refreshes. */
  const closeAfterOverlapChoice = () => {
    setOverlapBlock(null);
    setView('list');
    setStep(0);
    setEvidenceFiles([]);
    setEditing(null);
    fieldErrors.clearAll();
    refetch();
  };

  const handleOverlapDelete = async () => {
    if (!overlapBlock) return;
    try {
      await api.serviceRecords.delete(overlapBlock.recordId);
      toast.success(t('student.records.draftDeleted'));
    } catch (err) {
      if (err instanceof ApiError) toast.error(mapApiError(err).message);
      else toast.error(t('common.unknownError'));
    }
    closeAfterOverlapChoice();
  };

  const handleOverlapKeepDraft = () => {
    toast.success(t('student.records.draftSaved'));
    closeAfterOverlapChoice();
  };

  /**
   * Save without submitting.
   *
   * What that *means* depends on what is open (issue 143). A new record becomes
   * a draft. **A returned record does not** — it is PATCHed in place and stays
   * RETURNED, so calling it "Save Draft" and toasting "Draft saved" described
   * something that had not happened, and left students looking for a draft that
   * was never created.
   */
  const revising = editing?.status === 'returned';
  const handleSaveDraft = async () => {
    setSaving(true);
    try {
      await saveRecord();
      fieldErrors.clearAll();
      toast.success(t(revising ? 'student.records.recordSaved' : 'student.records.draftSaved'));
      // Close the wizard, not just reset it. The two lines below tear down the
      // state the form needs (uploaded evidence, and the `editing` link that
      // makes a later submit a PATCH rather than a POST), so leaving the form on
      // screen afterwards left it looking usable while silently dropping the
      // evidence and turning the next submit into a duplicate record (issue 073).
      setView('list');
      setStep(0);
      setEvidenceFiles([]);
      setEditing(null);
      refetch();
    } catch (err) {
      handleMutationError(err, { fieldErrors, fallbackMessage: t('common.unknownError') });
    } finally {
      setSaving(false);
    }
  };

  const [deleteTargetId, setDeleteTargetId] = useState<string | null>(null);
  // Issue 072: read-only detail view for any non-draft record (a draft's Edit
  // opens the wizard, which already shows everything a View would).
  const [viewRecordId, setViewRecordId] = useState<string | null>(null);

  const handleDeleteDraft = async (id: string) => {
    try {
      await api.serviceRecords.delete(id);
      toast.success(t('student.records.draftDeleted'));
      refetch();
    } catch (err) {
      if (err instanceof ApiError) toast.error(mapApiError(err).message);
      else toast.error(t('common.unknownError'));
    }
  };

  const handleSubmitDraft = async (id: string) => {
    const key = newIdempotencyKey();
    try {
      await api.serviceRecords.submit(id, key);
      toast.success(t('student.records.submittedForReview'));
      refetch();
    } catch (err) {
      const overlap = overlapErrorOf(err);
      // Submitting straight from the list only ever targets a DRAFT, so both
      // choices are available here (issue 069). `formData` belongs to the wizard
      // and is stale on this path, so the attempted window comes off the row.
      if (overlap) await openOverlapBlockFromRow(err, id);
      else if (err instanceof ApiError) toast.error(mapApiError(err).message);
      else toast.error(t('common.unknownError'));
    }
  };

  const handleRevert = async (id: string) => {
    try {
      await api.serviceRecords.withdraw(id);
      toast.success(t('student.records.revertedToDraft'));
      refetch();
    } catch (err) {
      if (err instanceof ApiError) toast.error(mapApiError(err).message);
      else toast.error(t('common.unknownError'));
    }
  };

  // Only 'project'/'self' are ever chooseable — 'external' is historical-only (§7.3):
  // it can only appear here already-selected-and-locked, when resubmitting an
  // existing record created before this change, never as a fresh pick.
  const sourceOptions: { value: 'project' | 'self' | 'external'; label: string; desc: string }[] = [
    { value: 'project', label: t('student.records.sourceProject'), desc: t('student.records.sourceProjectDesc') },
    { value: 'self', label: t('student.records.sourceSelf'), desc: t('student.records.sourceSelfDesc') },
  ];
  if (editing && formData.sourceType === 'external') {
    sourceOptions.unshift({ value: 'external', label: t('student.records.sourceExternal'), desc: t('student.records.sourceExternalDesc') });
  }

  const fetchSupervisorOptions = async (search: string): Promise<SearchPickerOption[]> => {
    if (formData.supervisorKind === 'internalUser') {
      const users = await api.users.directory('teacher', search);
      return users.map(u => ({ id: u.id, label: u.displayName }));
    }
    if (formData.supervisorKind === 'student') {
      const students = await api.students.directory(search);
      return students.map(s => ({ id: s.id, label: s.displayName, sublabel: formatGradeClass(s.gradeLevel, s.className) }));
    }
    return [];
  };

  // Confirm-step summary — resolved once here rather than repeated inline
  // conditionals, since PROJECT sources every field from `selectedProject`.
  const confirmLocation = formData.sourceType === 'project' ? selectedProject?.location ?? '' : formData.location;
  const confirmOrganization = formData.sourceType === 'project'
    ? [selectedProject?.hostOrganizationName, selectedProject?.organizationName].filter(Boolean).join(' / ')
    : formData.organizationName;
  const confirmSupervisorName = formData.sourceType === 'project' ? selectedProject?.ownerName ?? '' : formData.supervisorName;

  // Rendered in both returns below: the wizard's `view === 'new'` branch returns
  // early, so a dialog declared only at the end of the list branch is unreachable
  // from exactly the screen that raises it (caught live — the block fired, the
  // conflict lookup ran, and nothing appeared).
  const overlapDialog = overlapBlock && (
    <OverlapBlockedDialog
      attempted={overlapBlock.attempted}
      conflict={overlapBlock.conflict}
      canDelete={overlapBlock.canDelete}
      onDelete={handleOverlapDelete}
      onKeepDraft={handleOverlapKeepDraft}
      onClose={() => setOverlapBlock(null)}
    />
  );

  if (view === 'new') {
    return (
      <div className="px-4 py-4">
        {/* Header */}
        <div className="flex items-center gap-3 mb-6">
          <button onClick={() => { setView('list'); setStep(0); setEditing(null); }} className="text-muted-foreground hover:text-foreground p-1">
            <X className="w-5 h-5" />
          </button>
          <h2 className="text-foreground flex-1">{t(editing ? (editing.status === 'draft' ? 'student.records.editDraftTitle' : 'student.records.editTitle') : 'student.records.submitTitle')}</h2>
        </div>

        {/* Step indicator */}
        <div className="flex items-center gap-2 mb-6 overflow-x-auto pb-1">
          {STEP_KEYS.map((sk, i) => (
            <div key={i} className="flex items-center gap-2 shrink-0">
              <div className={`w-6 h-6 rounded-full flex items-center justify-center text-xs ${
                i < step ? 'bg-green-500 text-white' : i === step ? 'bg-[oklch(0.488_0.243_264.376)] text-white' : 'bg-muted text-muted-foreground'
              }`}>
                {i < step ? '✓' : i + 1}
              </div>
              <span className={`text-xs whitespace-nowrap ${i === step ? 'text-foreground' : 'text-muted-foreground'}`}>{t(`student.records.step_${sk}`)}</span>
              {i < STEP_KEYS.length - 1 && <div className="w-6 h-px bg-border" />}
            </div>
          ))}
        </div>

        {/* `noValidate` turns off the browser's *automatic* submit-time check,
            which would otherwise beat us to it with its own English message —
            explicit `checkValidity()`/`reportValidity()` calls still work, and
            `handleSubmitRecord` makes one. */}
        <form ref={formRef} onSubmit={handleSubmitRecord} noValidate className="space-y-4">
          {/* Step 0: Source */}
          {step === 0 && (
            <div className="space-y-4">
              <div>
                <label className="block text-sm text-foreground mb-2">{t('student.records.serviceSource')}</label>
                {editing && <p className="text-xs text-muted-foreground mb-2">{t('student.records.sourceLocked')}</p>}
                <div className="space-y-2">
                  {sourceOptions.map(opt => (
                    <button
                      type="button"
                      key={opt.value}
                      disabled={!!editing}
                      onClick={() => setFormData(f => ({ ...f, sourceType: opt.value }))}
                      className={`w-full text-left p-4 rounded-xl border-2 transition-colors ${
                        formData.sourceType === opt.value ? 'border-[oklch(0.488_0.243_264.376)] bg-blue-50 dark:bg-blue-900/20' : 'border-border hover:border-border/80 hover:bg-accent/50'
                      } ${editing ? 'opacity-60 cursor-not-allowed' : ''}`}
                    >
                      <div className="text-sm text-foreground font-medium">{opt.label}</div>
                      <div className="text-xs text-muted-foreground mt-0.5">{opt.desc}</div>
                    </button>
                  ))}
                </div>
              </div>
            </div>
          )}

          {/* Step 1: Service info */}
          {step === 1 && (
            <div className="space-y-4">
              {formData.sourceType === 'project' && (
                <div>
                  <label className="block text-xs text-muted-foreground mb-1.5">{t('student.records.selectProject')} *</label>
                  <select required disabled={!!editing} value={formData.projectId}
                    onChange={e => { fieldErrors.clearField('projectId'); setFormData(f => ({ ...f, projectId: e.target.value })); }}
                    className="w-full h-12 px-4 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground focus:outline-none focus:ring-2 focus:ring-primary/50 text-sm disabled:opacity-60">
                    <option value="">{t('student.records.selectProjectPlaceholder')}</option>
                    {confirmedProjects.map(a => <option key={a.projectId} value={a.projectId}>{a.projectTitle}</option>)}
                  </select>
                  <FieldError message={fieldErrors.getError('projectId')} />
                  {selectedProject && (
                    <div className="mt-3 bg-muted rounded-xl p-3 space-y-1.5 text-xs">
                      <div className="flex justify-between gap-3">
                        <span className="text-muted-foreground shrink-0">{t('student.records.organization')}</span>
                        <span className="text-foreground text-right">
                          {[selectedProject.hostOrganizationName, selectedProject.organizationName].filter(Boolean).join(' / ')}
                        </span>
                      </div>
                      <div className="flex justify-between gap-3">
                        <span className="text-muted-foreground shrink-0">{t('student.records.serviceCategory')}</span>
                        <span className="text-foreground">{selectedProject.categoryName}</span>
                      </div>
                      <div className="flex justify-between gap-3">
                        <span className="text-muted-foreground shrink-0">{t('student.records.serviceLocation')}</span>
                        <span className="text-foreground text-right">{selectedProject.location}</span>
                      </div>
                    </div>
                  )}
                </div>
              )}
              <div>
                <label className="block text-xs text-muted-foreground mb-1.5">{t('student.records.serviceDate')} *</label>
                <input type="date" required value={formData.serviceDate} onChange={e => { fieldErrors.clearField('serviceDate'); setFormData(f => ({ ...f, serviceDate: e.target.value })); }}
                  className="w-full h-12 px-4 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground focus:outline-none focus:ring-2 focus:ring-primary/50 text-sm" />
                <FieldError message={fieldErrors.getError('serviceDate')} />
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs text-muted-foreground mb-1.5">{t('student.records.startTime')} *</label>
                  <TimeInput required value={formData.startTime} onChange={v => { fieldErrors.clearField('startAt'); setFormData(f => ({ ...f, startTime: v })); }} />
                  <FieldError message={fieldErrors.getError('startAt', 'startTime')} />
                </div>
                <div>
                  <label className="block text-xs text-muted-foreground mb-1.5">{t('student.records.endTime')} *</label>
                  <TimeInput required value={formData.endTime} onChange={v => { fieldErrors.clearField('endAt'); setFormData(f => ({ ...f, endTime: v })); }} />
                  <FieldError message={fieldErrors.getError('endAt', 'endTime')} />
                </div>
              </div>
              <div>
                <label className="flex items-center gap-2 text-sm text-foreground">
                  <input type="checkbox" checked={formData.endsNextDay}
                    onChange={e => setFormData(f => ({ ...f, endsNextDay: e.target.checked }))}
                    className="w-4 h-4 rounded border-border" />
                  {t('student.records.endsNextDay')}
                </label>
              </div>
              {durationMinutes !== null && durationMinutes > 0 && (
                <p className="text-xs text-muted-foreground">{t('student.records.durationPreview', { hours: minutesToHours(durationMinutes) })}</p>
              )}
              {formData.sourceType === 'self' && (
                <>
                  <div>
                    <label className="block text-xs text-muted-foreground mb-1.5">{t('student.records.serviceLocation')} *</label>
                    <input type="text" required placeholder={t('student.records.locationPlaceholder')} value={formData.location} onChange={e => { fieldErrors.clearField('location'); setFormData(f => ({ ...f, location: e.target.value })); }}
                      maxLength={255}
                      className="w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50 text-sm" />
                    <FieldError message={fieldErrors.getError('location')} />
                  </div>
                  <div>
                    <label className="block text-xs text-muted-foreground mb-1.5">{t('student.records.orgName')}</label>
                    <input type="text" placeholder={t('student.records.locationPlaceholder')} value={formData.organizationName} onChange={e => { fieldErrors.clearField('organizationName'); setFormData(f => ({ ...f, organizationName: e.target.value })); }}
                      maxLength={255}
                      className="w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50 text-sm" />
                    <FieldError message={fieldErrors.getError('organizationName')} />
                  </div>
                  <div>
                    <label className="block text-xs text-muted-foreground mb-1.5">{t('student.records.serviceCategory')}</label>
                    <select value={formData.categoryId} onChange={e => { fieldErrors.clearField('serviceCategoryId'); setFormData(f => ({ ...f, categoryId: e.target.value })); }}
                      className="w-full h-12 px-4 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground focus:outline-none focus:ring-2 focus:ring-primary/50 text-sm">
                      {serviceCategories.map(cat => (
                        <option key={cat.id} value={cat.id}>{cat.name}</option>
                      ))}
                    </select>
                    <FieldError message={fieldErrors.getError('serviceCategoryId', 'categoryId')} />
                  </div>
                </>
              )}
              <div>
                <label className="flex items-center gap-2 text-sm text-foreground">
                  <input type="checkbox" checked={formData.includesTravelTime}
                    onChange={e => { fieldErrors.clearField('travelTimeReason'); setFormData(f => ({ ...f, includesTravelTime: e.target.checked })); }}
                    className="w-4 h-4 rounded border-border" />
                  {t('student.records.includesTravelTime')}
                </label>
              </div>
              {formData.includesTravelTime && (
                <div>
                  <label className="block text-xs text-muted-foreground mb-1.5">{t('student.records.travelTimeReason')} *</label>
                  <textarea rows={2} value={formData.travelTimeReason}
                    onChange={e => { fieldErrors.clearField('travelTimeReason'); setFormData(f => ({ ...f, travelTimeReason: e.target.value })); }}
                    maxLength={500}
                    className="w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground focus:outline-none focus:ring-2 focus:ring-primary/50 text-sm resize-none" />
                  <FieldError message={fieldErrors.getError('travelTimeReason')} />
                </div>
              )}
              <div>
                <label className="block text-xs text-muted-foreground mb-1.5">{t('student.records.serviceDesc')} *</label>
                <textarea
                  required
                  rows={10}
                  placeholder={t('student.records.descPlaceholder')}
                  value={formData.description}
                  onChange={e => { fieldErrors.clearField('description'); setFormData(f => ({ ...f, description: e.target.value })); }}
                  maxLength={2000}
                  className="w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50 text-sm font-mono resize-none overflow-y-auto whitespace-pre-wrap"
                />
                <FieldError message={fieldErrors.getError('description')} />
              </div>
            </div>
          )}

          {/* Step 2: Supervisor + Evidence */}
          {step === 2 && (
            <div className="space-y-4">
              {formData.sourceType === 'project' && (
                <div className="bg-muted rounded-xl p-3 text-xs space-y-1">
                  <div className="text-muted-foreground">{t('student.records.supervisor')}</div>
                  <div className="text-foreground">{selectedProject?.ownerName ?? '—'}</div>
                </div>
              )}
              {formData.sourceType === 'self' && (
                <>
                  <div>
                    <label className="block text-xs text-muted-foreground mb-1.5">{t('student.records.supervisorKind')} *</label>
                    <select required value={formData.supervisorKind}
                      onChange={e => {
                        fieldErrors.clearField('supervisorKind');
                        const kind = e.target.value as SupervisorKind;
                        setSupervisorOption([]);
                        setFormData(f => ({ ...f, supervisorKind: kind, supervisorSubjectId: '', supervisorName: '', supervisorContact: '' }));
                      }}
                      className="w-full h-12 px-4 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground focus:outline-none focus:ring-2 focus:ring-primary/50 text-sm">
                      <option value="">{t('student.records.supervisorKindPlaceholder')}</option>
                      <option value="internalUser">{t('student.records.supervisorKindInternal')}</option>
                      <option value="student">{t('student.records.supervisorKindStudent')}</option>
                      <option value="externalManual">{t('student.records.supervisorKindExternal')}</option>
                    </select>
                    <FieldError message={fieldErrors.getError('supervisorKind')} />
                  </div>
                  {(formData.supervisorKind === 'internalUser' || formData.supervisorKind === 'student') && (
                    <div>
                      <label className="block text-xs text-muted-foreground mb-1.5">{t('student.records.supervisorName')} *</label>
                      <SearchPicker
                        selected={supervisorOption}
                        onChange={opts => {
                          fieldErrors.clearField('supervisorSubjectId');
                          setSupervisorOption(opts);
                          setFormData(f => ({ ...f, supervisorSubjectId: opts[0]?.id ?? '', supervisorName: opts[0]?.label ?? '' }));
                        }}
                        fetchOptions={fetchSupervisorOptions}
                        placeholder={t('student.records.supervisorPlaceholder')}
                        searchPlaceholder={t('student.records.supervisorSearchPlaceholder')}
                        noResultsLabel={t('student.proposals.noResults')}
                        loadingLabel={t('common.loading')}
                      />
                      <FieldError message={fieldErrors.getError('supervisorSubjectId')} />
                    </div>
                  )}
                  {formData.supervisorKind === 'externalManual' && (
                    <>
                      <div>
                        <label className="block text-xs text-muted-foreground mb-1.5">{t('student.records.supervisorName')} *</label>
                        <input type="text" required placeholder={t('student.records.supervisorPlaceholder')} value={formData.supervisorName}
                          onChange={e => { fieldErrors.clearField('supervisorName'); setFormData(f => ({ ...f, supervisorName: e.target.value })); }}
                          maxLength={255}
                          className="w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50 text-sm" />
                        <FieldError message={fieldErrors.getError('supervisorName')} />
                      </div>
                      <div>
                        <label className="block text-xs text-muted-foreground mb-1.5">{t('student.records.contactInfo')} *</label>
                        <input type="text" required placeholder={t('student.records.contactPlaceholder')} value={formData.supervisorContact}
                          onChange={e => { fieldErrors.clearField('supervisorContact'); setFormData(f => ({ ...f, supervisorContact: e.target.value })); }}
                          maxLength={255}
                          className="w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50 text-sm" />
                        <FieldError message={fieldErrors.getError('supervisorContact')} />
                      </div>
                    </>
                  )}
                </>
              )}
              {formData.sourceType === 'external' && (
                <>
                  <div>
                    <label className="block text-xs text-muted-foreground mb-1.5">{t('student.records.supervisorName')} *</label>
                    <input type="text" required placeholder={t('student.records.supervisorPlaceholder')} value={formData.supervisorName} onChange={e => { fieldErrors.clearField('supervisorName'); setFormData(f => ({ ...f, supervisorName: e.target.value })); }}
                      maxLength={255}
                      className="w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50 text-sm" />
                    <FieldError message={fieldErrors.getError('supervisorName')} />
                  </div>
                  <div>
                    <label className="block text-xs text-muted-foreground mb-1.5">{t('student.records.contactInfo')}</label>
                    <input type="text" placeholder={t('student.records.contactPlaceholder')} value={formData.supervisorContact} onChange={e => { fieldErrors.clearField('supervisorContact'); setFormData(f => ({ ...f, supervisorContact: e.target.value })); }}
                      maxLength={255}
                      className="w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50 text-sm" />
                    <FieldError message={fieldErrors.getError('supervisorContact')} />
                  </div>
                </>
              )}
              <div>
                <label className="block text-xs text-muted-foreground mb-2">{t('student.records.uploadEvidence')}</label>
                <input ref={fileInputRef} type="file" multiple accept="image/*,.pdf" className="hidden"
                  onChange={e => handleFileSelect(e.target.files)} />
                <div onClick={() => !uploading && fileInputRef.current?.click()}
                  className={`border-2 border-dashed border-border rounded-xl p-6 text-center transition-colors cursor-pointer ${uploading ? 'opacity-50 pointer-events-none' : 'hover:border-primary/50'}`}>
                  <Upload className="w-8 h-8 text-muted-foreground mx-auto mb-2" />
                  <p className="text-sm text-foreground">{uploading ? t('common.saving', { defaultValue: 'Saving...' }) : t('student.records.uploadClick')}</p>
                  <p className="text-xs text-muted-foreground mt-1">{t('student.records.uploadFormats')}</p>
                  <p className="text-xs text-muted-foreground">{t('student.records.uploadAccepted')}</p>
                </div>
                {evidenceFiles.length > 0 && (
                  <div className="mt-3 space-y-1.5">
                    {evidenceFiles.map(f => (
                      <div key={f.id} className="flex items-center justify-between px-3 py-2 rounded-lg bg-muted text-xs">
                        <span className="text-foreground truncate">{f.name}</span>
                        <button type="button" onClick={() => removeEvidenceFile(f.id)} className="text-muted-foreground hover:text-destructive ml-2"><X className="w-3.5 h-3.5" /></button>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>
          )}

          {/* Step 3: Confirm */}
          {step === 3 && (
            <div className="space-y-4">
              <div className="bg-muted rounded-xl p-4 space-y-3 text-sm">
                <div className="flex justify-between">
                  <span className="text-muted-foreground">{t('student.records.serviceSource')}</span>
                  <span className="text-foreground">{t(`student.records.source_${formData.sourceType}`)}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-muted-foreground">{t('student.records.serviceDate')}</span>
                  <span className="text-foreground">{formData.serviceDate || '—'}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-muted-foreground">{t('student.records.time')}</span>
                  <span className="text-foreground">
                    {formData.startTime} - {formData.endTime || '—'}{formData.endsNextDay && ` (${t('student.records.endsNextDay')})`}
                    {durationMinutes !== null && durationMinutes > 0 && ` (${minutesToHours(durationMinutes)} ${t('common.hours')})`}
                  </span>
                </div>
                <div className="flex justify-between">
                  <span className="text-muted-foreground">{t('student.records.serviceLocation')}</span>
                  <span className="text-foreground text-right max-w-[60%]">{confirmLocation || '—'}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-muted-foreground">{t('student.records.orgName')}</span>
                  <span className="text-foreground">{confirmOrganization || '—'}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-muted-foreground">{t('student.records.supervisorName')}</span>
                  <span className="text-foreground">{confirmSupervisorName || '—'}</span>
                </div>
              </div>
              <div className="bg-yellow-50 dark:bg-yellow-900/20 border border-yellow-200 dark:border-yellow-800 rounded-xl p-4 text-xs text-yellow-800 dark:text-yellow-300">
                ⚠️ {t('student.records.submitWarning')}
              </div>
            </div>
          )}

          {/* Navigation buttons */}
          <div className="flex gap-3 pt-2">
            {step > 0 && (
              <button type="button" onClick={() => setStep(s => s - 1)}
                className="flex-1 py-3 rounded-xl border border-border text-foreground hover:bg-accent transition-colors text-sm">
                {t('common.previous')}
              </button>
            )}
            {/* Sits between Previous and Next/Confirm Submit. Absent on the source
                step: nothing savable exists yet there, and pressing it only ever
                produced a validation error. */}
            {step > 0 && (
              <button type="button" onClick={handleSaveDraft} disabled={saving || !canSaveDraft}
                title={canSaveDraft ? undefined : t('student.records.saveDraftNeedsWindow')}
                className="flex-1 py-3 rounded-xl border border-border text-muted-foreground hover:bg-accent hover:text-foreground transition-colors text-sm disabled:opacity-50 disabled:hover:bg-transparent">
                {t(revising ? 'student.records.saveRecord' : 'student.records.saveDraft')}
              </button>
            )}
            {step < STEP_KEYS.length - 1 ? (
              // Each step's required fields are only mounted for that step, so a
              // browser-native submit at the final step never sees earlier steps'
              // `required` inputs at all and silently lets them through empty
              // (e.g. issue 058 — a student could skip picking a project entirely).
              // Checking validity here, while those fields are still mounted, is
              // what actually catches it.
              // The explicit `key`s matter: without them React reconciles these
              // two branches onto the *same* <button> DOM node and only patches
              // its attributes, so clicking "Next" on the evidence step would
              // flip that very node's `type` to "submit" before the in-flight
              // click's default action ran — submitting the record immediately
              // and skipping the confirm/declaration step entirely (issue 066).
              <button key="next" type="button" disabled={!nextEnabled} onClick={() => { if (reportLocalizedValidity(formRef.current, t)) setStep(s => s + 1); }}
                className="flex-1 py-3 rounded-xl bg-[oklch(0.488_0.243_264.376)] text-white hover:opacity-90 transition-opacity text-sm disabled:opacity-50">
                {t('common.next')}
              </button>
            ) : (
              <button key="submit" type="submit"
                className="flex-1 py-3 rounded-xl bg-[oklch(0.488_0.243_264.376)] text-white hover:opacity-90 transition-opacity text-sm flex items-center justify-center gap-2">
                <Send className="w-4 h-4" />
                {t('student.records.confirmSubmit')}
              </button>
            )}
          </div>
        </form>
        {overlapDialog}
      </div>
    );
  }

  if (loading && records.length === 0) return <div className="p-4 lg:p-6 text-muted-foreground">{t('common.loading')}</div>;

  // Docs/optimization/05-SearchFilterSortConsistency.md's admin-page convention, reused here
  // (issue 053/054): `params.sort`-derived active sort key/direction, one button per field.
  const sortKey = (params.sort?.replace(/^-/, '') as SortKey) ?? 'serviceDate';
  const sortDesc = params.sort?.startsWith('-') ?? true;
  const handleSortClick = (key: SortKey) => {
    const nextDesc = sortKey === key ? !sortDesc : true;
    setSort(nextDesc ? `-${key}` : key);
  };
  const hasActiveFilters = !!(params.search || params.filter?.status || params.filter?.serviceCategoryId);
  const clearFilters = () => { setSearch(''); setFilter('status', undefined); setFilter('serviceCategoryId', undefined); };

  return (
    <div className="p-4 lg:p-6 space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-foreground">{t('student.records.title')}</h2>
        <div className="flex gap-2">
          {/* The standalone "file a case" entry point lives on /student/cases now
              (issue 081). The per-record Appeal/Supplement actions stay on their
              own rows, where the student already has the context for them. */}
          <button
            onClick={openNewRecord}
            className="flex items-center gap-1.5 px-3 py-2 bg-[oklch(0.488_0.243_264.376)] text-white rounded-xl text-sm hover:opacity-90 transition-opacity"
          >
            <Plus className="w-4 h-4" />
            {t('student.records.newRecord')}
          </button>
        </div>
      </div>

      {/* Search & filters — Docs/optimization/05-SearchFilterSortConsistency.md §2.4: search alone
          below lg:, filters as an Nx1 grid alongside it, merged into one row at lg: and up. */}
      <div className="flex flex-col gap-2 lg:flex-row lg:flex-wrap">
        <div className="relative lg:flex-1 lg:min-w-48">
          <ListSearchInput
            value={params.search ?? ''}
            onCommit={setSearch}
            placeholder={t('student.records.searchPlaceholder')}
          />
        </div>
        <div className="grid grid-cols-2 gap-2 lg:contents">
          <select value={params.filter?.status ?? 'all'} onChange={e => setFilter('status', e.target.value === 'all' ? undefined : e.target.value)}
            className="px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none">
            <option value="all">{t('student.records.allStatuses')}</option>
            <option value="draft">{t('student.records.tabDraft')}</option>
            <option value="submitted">{t('student.records.tabSubmitted')}</option>
            <option value="underReview">{t('student.records.tabUnderReview')}</option>
            <option value="returned">{t('student.records.tabReturned')}</option>
            <option value="approved">{t('student.records.tabApproved')}</option>
            <option value="rejected">{t('student.records.tabRejected')}</option>
          </select>
          <select value={params.filter?.serviceCategoryId ?? 'all'} onChange={e => setFilter('serviceCategoryId', e.target.value === 'all' ? undefined : e.target.value)}
            className="px-3 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none">
            <option value="all">{t('student.records.allCategories')}</option>
            {serviceCategories.map(cat => <option key={cat.id} value={cat.id}>{cat.name}</option>)}
          </select>
        </div>
      </div>

      {/* Sort — always an Nx1 grid, no breakpoint condition (§2.5). */}
      <div className="grid grid-cols-3 gap-2">
        {SORT_KEYS.map(key => (
          <button key={key} onClick={() => handleSortClick(key)}
            className={`px-3 py-1.5 rounded-lg text-xs flex items-center justify-center gap-1 transition-colors ${sortKey === key ? 'bg-blue-100 text-blue-700 dark:bg-blue-500/20 dark:text-blue-300' : 'bg-muted text-muted-foreground hover:bg-accent'}`}>
            {key === 'serviceDate' ? t('student.records.sortServiceDate') : key === 'createdAt' ? t('student.records.sortCreated') : t('student.records.sortStatus')}
            {sortKey === key && (sortDesc ? ' ↓' : ' ↑')}
          </button>
        ))}
      </div>

      {/* Records — desktop table / mobile cards */}
      <ListStateBanner error={error} empty={!loading && records.length === 0}
        emptyLabel={t(hasActiveFilters ? 'common.list.emptyNoResults' : 'student.records.noRecords')}
        hasActiveFilters={hasActiveFilters} onClearFilters={clearFilters} />
      {!loading && !error && records.length === 0 && !hasActiveFilters && (
        <div className="text-center -mt-8">
          <button onClick={openNewRecord} className="text-primary text-sm hover:underline">+ {t('student.records.submitFirst')}</button>
        </div>
      )}
      {records.length > 0 && (
        <>
          {/* Desktop table */}
          <div className="hidden lg:block bg-card border border-border rounded-2xl overflow-hidden">
            <table className="w-full text-sm">
              <thead className="bg-muted/50 border-b border-border">
                <tr>
                  <th className="text-left px-4 py-3 text-xs text-muted-foreground font-medium">{t('student.records.colProject')}</th>
                  <th className="text-left px-4 py-3 text-xs text-muted-foreground font-medium">{t('student.records.colCategory')}</th>
                  <th className="text-left px-4 py-3 text-xs text-muted-foreground font-medium">{t('student.records.colDate')}</th>
                  <th className="text-left px-4 py-3 text-xs text-muted-foreground font-medium">{t('student.records.colHours')}</th>
                  <th className="text-left px-4 py-3 text-xs text-muted-foreground font-medium">{t('student.records.colStatus')}</th>
                  <th className="text-left px-4 py-3 text-xs text-muted-foreground font-medium">{t('student.records.colCoins')}</th>
                  <th className="text-right px-4 py-3 text-xs text-muted-foreground font-medium">{t('student.records.colActions')}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {records.map(rec => {
                  const statusInfo = RECORD_STATUS_LABELS[rec.status];
                  return (
                    <tr key={rec.id} className="hover:bg-muted/30 transition-colors group">
                      <td className="px-4 py-3">
                        <div className="text-sm text-foreground font-medium">{rec.projectTitle || rec.organizationName}</div>
                        {rec.recordNumber && <div className="text-xs text-muted-foreground">{rec.recordNumber}</div>}
                        {rec.status === 'returned' && (rec.returnReason || rec.reviewSummary) && (
                          <div className="text-xs text-orange-600 dark:text-orange-400 flex items-start gap-1 mt-0.5">
                            <AlertTriangle className="w-3 h-3 shrink-0 mt-0.5" />
                            <span className="max-w-sm whitespace-pre-wrap">{rec.returnReason || rec.reviewSummary}</span>
                          </div>
                        )}
                      </td>
                      <td className="px-4 py-3 text-xs text-muted-foreground">{rec.serviceCategoryName}</td>
                      <td className="px-4 py-3 text-xs text-muted-foreground">{rec.serviceDate}</td>
                      <td className="px-4 py-3 text-xs text-muted-foreground">{minutesToHours(rec.durationMinutes)}h</td>
                      <td className="px-4 py-3">
                        <span className={`text-xs px-2 py-0.5 rounded-full whitespace-nowrap ${colorMap[statusInfo.color]}`}>{t(statusInfo.label)}</span>
                      </td>
                      <td className="px-4 py-3 text-xs text-muted-foreground">
                        {rec.creditedUnits ? <span className="text-yellow-600 dark:text-yellow-400">+{formatCoins(rec.creditedUnits)}</span> : '—'}
                      </td>
                      <td className="px-4 py-3">
                        <div className="flex gap-1 justify-end">
                          {rec.status !== 'draft' && (
                            <button onClick={() => setViewRecordId(rec.id)}
                              className={`text-xs px-2 py-1 rounded-lg border transition-colors flex items-center gap-1 ${ACTION_COLORS.blue}`}>
                              <Eye className="w-3 h-3" /> {t('common.view')}
                            </button>
                          )}
                          {/* A returned record's one action is the edit form: it
                              opens 编辑退回记录 with the record loaded, and the
                              resubmit happens at the end of it. It used to be
                              named "Resubmit", which reads as sending it again
                              unchanged. Same wording as the proposal flow, which
                              already had this exact situation (issue 142). */}
                          {rec.status === 'returned' && (
                            <button onClick={() => openResubmit(rec)}
                              className={`text-xs px-2 py-1 rounded-lg border transition-colors flex items-center gap-1 ${ACTION_COLORS.green}`}>
                              <Pencil className="w-3 h-3" /> {t('student.records.reviseResubmit')}
                            </button>
                          )}
                          {rec.status === 'draft' && (
                            <>
                              <button onClick={() => openEditDraft(rec)}
                                className={`text-xs px-2 py-1 rounded-lg border transition-colors flex items-center gap-1 ${ACTION_COLORS.blue}`}>
                                <Pencil className="w-3 h-3" /> {t('student.records.edit')}
                              </button>
                              <button onClick={() => setDeleteTargetId(rec.id)}
                                className={`text-xs px-2 py-1 rounded-lg border transition-colors flex items-center gap-1 ${ACTION_COLORS.red}`}>
                                <Trash2 className="w-3 h-3" /> {t('common.delete')}
                              </button>
                              <button onClick={() => handleSubmitDraft(rec.id)}
                                className={`text-xs px-2 py-1 rounded-lg border transition-colors flex items-center gap-1 ${ACTION_COLORS.green}`}>
                                <Send className="w-3 h-3" /> {t('common.submit')}
                              </button>
                            </>
                          )}
                          {rec.status === 'submitted' && (
                            <button onClick={() => handleRevert(rec.id)}
                              className={`text-xs px-2 py-1 rounded-lg border transition-colors flex items-center gap-1 ${ACTION_COLORS.amber}`}>
                              <RotateCcw className="w-3 h-3" /> {t('student.records.revert')}
                            </button>
                          )}
                          {rec.status === 'rejected' && (
                            <button onClick={() => setCaseFiling({ type: 'appeal', record: rec })}
                              className={`text-xs px-2 py-1 rounded-lg border transition-colors flex items-center gap-1 ${ACTION_COLORS.purple}`}>
                              <Gavel className="w-3 h-3" /> {t('student.records.appeal')}
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>

          {/* Mobile cards */}
          <div className="lg:hidden space-y-3">
            {records.map(rec => {
              const statusInfo = RECORD_STATUS_LABELS[rec.status];
              const isExpanded = expandedId === rec.id;
              return (
                <div key={rec.id} className="bg-card border border-border rounded-2xl overflow-hidden">
                  <div className="p-4">
                    <div className="flex items-start gap-3 mb-3">
                      <div className="w-10 h-10 rounded-xl bg-accent flex items-center justify-center shrink-0">
                        <FileText className="w-5 h-5 text-muted-foreground" />
                      </div>
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2 mb-0.5">
                          <span className={`text-xs px-2 py-0.5 rounded-full whitespace-nowrap ${colorMap[statusInfo.color]}`}>{t(statusInfo.label)}</span>
                          {rec.creditedUnits && <span className="text-xs text-yellow-600 dark:text-yellow-400">+{formatCoins(rec.creditedUnits)} {t('common.coins')}</span>}
                        </div>
                        <div className="text-sm text-foreground font-medium truncate">{rec.projectTitle || rec.organizationName}</div>
                        <div className="text-xs text-muted-foreground">{rec.serviceDate} · {minutesToHours(rec.durationMinutes)} {t('common.hours')} · {rec.serviceCategoryName}</div>
                        {rec.recordNumber && <div className="text-xs text-muted-foreground">{rec.recordNumber}</div>}
                      </div>
                    </div>
                    {rec.status === 'returned' && (rec.returnReason || rec.reviewSummary) && (
                      <div className="bg-orange-50 dark:bg-orange-900/20 border border-orange-200 dark:border-orange-800 rounded-lg px-3 py-2 mb-3">
                        <div className="text-xs text-orange-700 dark:text-orange-300 flex items-start gap-1.5">
                          <AlertTriangle className="w-3.5 h-3.5 shrink-0 mt-0.5" />
                          {rec.returnReason || rec.reviewSummary}
                        </div>
                      </div>
                    )}
                    {isExpanded && (
                      <div className="border-t border-border pt-3 mb-3 space-y-2 text-xs text-muted-foreground">
                        <p><span className="font-medium text-foreground">{t('student.records.description')}:</span>{rec.description}</p>
                        <p><span className="font-medium text-foreground">{t('student.records.serviceLocation')}:</span>{rec.location}</p>
                        <p><span className="font-medium text-foreground">{t('student.records.orgName')}:</span>{rec.organizationName}</p>
                        <p><span className="font-medium text-foreground">{t('student.records.supervisorName')}:</span>{rec.supervisorName} {rec.supervisorContact}</p>
                        {rec.includesTravelTime && rec.travelTimeReason && (
                          <p><span className="font-medium text-foreground">{t('student.records.travelTimeReason')}:</span>{rec.travelTimeReason}</p>
                        )}
                        {rec.reviewSummary && <p><span className="font-medium text-foreground">{t('student.records.reviewNotes')}:</span>{rec.reviewSummary}</p>}
                        {rec.evidenceFiles.length > 0 && (
                          <div className="flex flex-wrap items-center gap-2">
                            <span className="font-medium text-foreground">{t('student.records.evidence')}:</span>
                            {rec.evidenceFiles.map(f => <EvidenceLink key={f.id} fileId={f.id} name={f.name} />)}
                          </div>
                        )}
                        <div className="mt-3 space-y-2">
                          <div className="font-medium text-foreground">{t('student.records.timeline')}</div>
                          {[
                            { label: t('student.records.timelineDraft'), time: rec.createdAt },
                            rec.submittedAt && { label: t('student.records.timelineSubmitted'), time: rec.submittedAt },
                            rec.reviewedAt && { label: t(`student.records.timelineReview_${rec.status}`), time: rec.reviewedAt },
                          ].filter(Boolean).map((evt, i) => (
                            <div key={i} className="flex items-center gap-2">
                              <div className="w-1.5 h-1.5 rounded-full bg-border" />
                              <span className="text-foreground">{(evt as any).label}</span>
                              <span className="text-muted-foreground ml-auto">{(evt as any).time?.slice(0, 10)}</span>
                            </div>
                          ))}
                        </div>
                      </div>
                    )}
                    <div className="flex items-center justify-between">
                      <button onClick={() => setExpandedId(isExpanded ? null : rec.id)}
                        className="text-xs text-muted-foreground hover:text-foreground flex items-center gap-1">
                        {isExpanded ? <><ChevronUp className="w-3 h-3" /> {t('common.collapse')}</> : <><ChevronDown className="w-3 h-3" /> {t('common.details')}</>}
                      </button>
                      <div className="flex gap-2">
                        {rec.status !== 'draft' && (
                          <button onClick={() => setViewRecordId(rec.id)}
                            className={`text-xs px-3 py-1.5 rounded-lg border transition-colors flex items-center gap-1 ${ACTION_COLORS.blue}`}>
                            <Eye className="w-3 h-3" /> {t('common.view')}
                          </button>
                        )}
                        {rec.status === 'returned' && (
                          <button onClick={() => openResubmit(rec)}
                            className={`text-xs px-3 py-1.5 rounded-lg border transition-colors flex items-center gap-1 ${ACTION_COLORS.green}`}>
                            <Pencil className="w-3 h-3" /> {t('student.records.reviseResubmit')}
                          </button>
                        )}
                        {rec.status === 'draft' && (
                          <>
                            <button onClick={() => openEditDraft(rec)}
                              className={`text-xs px-3 py-1.5 rounded-lg border transition-colors flex items-center gap-1 ${ACTION_COLORS.blue}`}>
                              <Pencil className="w-3 h-3" /> {t('student.records.edit')}
                            </button>
                            <button onClick={() => setDeleteTargetId(rec.id)}
                              className={`text-xs px-3 py-1.5 rounded-lg border transition-colors flex items-center gap-1 ${ACTION_COLORS.red}`}>
                              <Trash2 className="w-3 h-3" /> {t('common.delete')}
                            </button>
                            <button onClick={() => handleSubmitDraft(rec.id)}
                              className={`text-xs px-3 py-1.5 rounded-lg border transition-colors flex items-center gap-1 ${ACTION_COLORS.green}`}>
                              <Send className="w-3 h-3" /> {t('common.submit')}
                            </button>
                          </>
                        )}
                        {rec.status === 'submitted' && (
                          <button onClick={() => handleRevert(rec.id)}
                            className={`text-xs px-3 py-1.5 rounded-lg border transition-colors flex items-center gap-1 ${ACTION_COLORS.amber}`}>
                            <RotateCcw className="w-3 h-3" /> {t('student.records.revert')}
                          </button>
                        )}
                        {rec.status === 'rejected' && (
                          <button onClick={() => setCaseFiling({ type: 'appeal', record: rec })}
                            className={`text-xs px-3 py-1.5 rounded-lg border transition-colors flex items-center gap-1 ${ACTION_COLORS.purple}`}>
                            <Gavel className="w-3 h-3" /> {t('student.records.appeal')}
                          </button>
                        )}
                      </div>
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        </>
      )}
      <PageControls page={page} onPageChange={setPage} pageSize={params.pageSize ?? 20} onPageSizeChange={setPageSize} />

      {/* No `allowDraft`: this page lists records, not cases, so a saved case
          draft would vanish from view the moment the dialog closed (issue 143).
          My Cases, which does list them, keeps the button. */}
      {caseFiling && (
        <CaseFilingModal
          type={caseFiling.type}
          record={caseFiling.record}
          onClose={() => setCaseFiling(null)}
          onFiled={() => setCaseFiling(null)}
        />
      )}

      {deleteTargetId && (
        <ConfirmDeleteDialog
          title={t('student.records.deleteRecord')}
          message={t('student.records.confirmDeleteRecord')}
          onCancel={() => setDeleteTargetId(null)}
          onConfirm={() => { const id = deleteTargetId; setDeleteTargetId(null); handleDeleteDraft(id); }}
        />
      )}

      {overlapDialog}

      {viewRecordId && (
        <ServiceRecordDetail recordId={viewRecordId} onClose={() => setViewRecordId(null)} />
      )}
    </div>
  );
}
