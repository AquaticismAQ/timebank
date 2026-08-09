import { useState, useRef } from 'react';
import { Plus, ChevronDown, ChevronUp, ChevronRight, Edit, Send, Copy, X, FileText, CheckCircle, AlertTriangle, Clock, RotateCcw } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { PROPOSAL_STATUS_LABELS } from '../../lib/statusLabels';
import { useDictionaries } from '../../api/useDictionaries';
import { type EventProposal, type ProposalStatus } from '../../api/types';
import { toast } from 'sonner';
import { api } from '../../api/client';
import { newIdempotencyKey } from '../../api/http';
import { useFieldErrors, FieldError, handleMutationError } from '../../api/useFieldErrors';
import { usePagedApi } from '../../api/useApi';
import { minutesToHours, formatGradeClass } from '../../lib/format';
import { PageControls } from '../PageControls';
import { ListStateBanner } from '../ListStateBanner';
import { SearchPicker, CREATE_NEW_ID, type SearchPickerOption } from '../SearchPicker';
import { useAuth } from '../../App';

const colorMap: Record<string, string> = {
  gray: 'bg-muted text-muted-foreground',
  blue: 'bg-blue-100 dark:bg-blue-900/30 text-blue-700 dark:text-blue-400',
  yellow: 'bg-yellow-100 dark:bg-yellow-900/30 text-yellow-700 dark:text-yellow-400',
  green: 'bg-green-100 dark:bg-green-900/30 text-green-700 dark:text-green-400',
  orange: 'bg-orange-100 dark:bg-orange-900/30 text-orange-700 dark:text-orange-400',
  red: 'bg-red-100 dark:bg-red-900/30 text-red-700 dark:text-red-400',
};

const STEP_KEYS = ['overview', 'implementation', 'organization', 'safety', 'recruitment', 'confirm'];

type ViewMode = 'list' | 'form' | 'detail';

const EMPTY_FORM = {
  title: '',
  goal: '',
  categoryId: 'cat_education',
  description: '',
  expectedOutcomes: '',
  plannedDate: '',
  location: '',
  activityFormat: '',
  estimatedDurationHours: 2,
  estimatedCapacity: 20,
  targetParticipants: '',
  volunteerTasks: '',
  coOrganizerStudents: [] as SearchPickerOption[],
  mentorTeacherOption: [] as SearchPickerOption[],
  partnerOrgOption: [] as SearchPickerOption[],
  contactInfo: '',
  riskMeasures: '',
  materialNeeds: '',
  budgetNeeds: '',
  recruitmentMethod: '',
  eligibility: '',
  proofMethod: '',
  suggestedHours: 2,
};

export function EventProposals() {
  const { t } = useTranslation();
  const { serviceCategories } = useDictionaries();
  const { user } = useAuth();
  const [view, setView] = useState<ViewMode>('list');
  const [filterStatus, setFilterStatus] = useState<ProposalStatus | 'all'>('all');
  const [detailId, setDetailId] = useState<string | null>(null);
  const [step, setStep] = useState(0);
  const [formData, setFormData] = useState(EMPTY_FORM);
  const [declared, setDeclared] = useState(false);
  // Which real proposal (if any) handleSaveDraft/handleSubmit should PATCH
  // instead of creating a new one — null means "New Proposal" or "Copy",
  // both of which produce a fresh draft. Previously `openForm` had no way
  // to distinguish "edit this one" from "copy this one," so Edit Draft
  // silently created a duplicate instead of patching the original (found
  // 2026-08-06, `Docs/implementation/00-Status.md` §30).
  const [editing, setEditing] = useState<{ id: string; version: number } | null>(null);
  const { items: proposals, page, loading, error, setFilter, setPage, refetch } = usePagedApi(api.eventProposals.list, {});

  const detailProposal = proposals.find(p => p.id === detailId);

  const openForm = (template?: EventProposal, mode: 'edit' | 'copy' = 'copy') => {
    if (template) {
      setEditing(mode === 'edit' ? { id: template.id, version: template.version ?? 0 } : null);
      setFormData({
        title: mode === 'copy' ? template.title + ' (Copy)' : template.title,
        goal: template.goal,
        categoryId: template.categoryId,
        description: template.description,
        expectedOutcomes: template.expectedOutcomes,
        plannedDate: template.plannedDate,
        location: template.location,
        activityFormat: template.activityFormat,
        estimatedDurationHours: template.estimatedDurationMinutes / 60,
        estimatedCapacity: template.estimatedCapacity,
        targetParticipants: template.targetParticipants,
        volunteerTasks: template.volunteerTasks,
        coOrganizerStudents: (template.coOrganizerStudentIds ?? []).map((id, i) => ({
          id, label: template.coOrganizerNames?.[i] || id,
        })),
        mentorTeacherOption: template.proposedSupervisorId
          ? [{ id: template.proposedSupervisorId, label: template.mentorTeacher || template.proposedSupervisorId }]
          : [],
        partnerOrgOption: template.organizationId
          ? [{ id: template.organizationId, label: template.partnerOrg || template.organizationId }]
          : [],
        contactInfo: template.contactInfo,
        riskMeasures: template.riskMeasures,
        materialNeeds: template.materialNeeds,
        budgetNeeds: template.budgetNeeds,
        recruitmentMethod: template.recruitmentMethod,
        eligibility: template.eligibility,
        proofMethod: template.proofMethod,
        suggestedHours: template.suggestedMinutes / 60,
      });
    } else {
      setEditing(null);
      setFormData(EMPTY_FORM);
    }
    setStep(0);
    setDeclared(false);
    setView('form');
  };

  const [saving, setSaving] = useState(false);
  const submitKeyRef = useRef(newIdempotencyKey());
  const fieldErrors = useFieldErrors();

  const formToApiData = (): Partial<EventProposal> => ({
    title: formData.title,
    goal: formData.goal,
    categoryId: formData.categoryId,
    description: formData.description,
    expectedOutcomes: formData.expectedOutcomes,
    plannedDate: formData.plannedDate,
    location: formData.location,
    activityFormat: formData.activityFormat,
    estimatedDurationMinutes: formData.estimatedDurationHours * 60,
    estimatedCapacity: formData.estimatedCapacity,
    targetParticipants: formData.targetParticipants,
    volunteerTasks: formData.volunteerTasks,
    coOrganizers: formData.coOrganizerStudents.map(s => s.label).join(', '),
    coOrganizerStudentIds: formData.coOrganizerStudents.map(s => s.id),
    mentorTeacher: formData.mentorTeacherOption[0]?.label ?? '',
    proposedSupervisorId: formData.mentorTeacherOption[0]?.id,
    partnerOrg: formData.partnerOrgOption[0]?.label ?? '',
    organizationId: formData.partnerOrgOption[0] && formData.partnerOrgOption[0].id !== CREATE_NEW_ID
      ? formData.partnerOrgOption[0].id : undefined,
    newOrganizationName: formData.partnerOrgOption[0]?.id === CREATE_NEW_ID
      ? formData.partnerOrgOption[0].label : undefined,
    contactInfo: formData.contactInfo,
    riskMeasures: formData.riskMeasures,
    materialNeeds: formData.materialNeeds,
    budgetNeeds: formData.budgetNeeds,
    recruitmentMethod: formData.recruitmentMethod,
    eligibility: formData.eligibility,
    proofMethod: formData.proofMethod,
    suggestedMinutes: formData.suggestedHours * 60,
  });

  // `editing` set -> PATCH the real proposal being edited; unset -> a fresh
  // draft (covers both "New Proposal" and "Copy Proposal").
  const saveProposal = (): Promise<EventProposal> => editing
    ? api.eventProposals.update(editing.id, { ...formToApiData(), version: editing.version })
    : api.eventProposals.create(formToApiData());

  const handleSaveDraft = async () => {
    setSaving(true);
    try {
      await saveProposal();
      fieldErrors.clearAll();
      toast.success(t('student.proposals.draftSaved'));
      setView('list');
      setStep(0);
      refetch();
    } catch (err) {
      handleMutationError(err, { fieldErrors, refetch, fallbackMessage: t('common.unknownError') });
    } finally {
      setSaving(false);
    }
  };

  const handleSubmit = async () => {
    if (!declared) { toast.error(t('student.proposals.checkDeclaration')); return; }
    setSaving(true);
    try {
      const proposal = await saveProposal();
      await api.eventProposals.submit(proposal.id, submitKeyRef.current);
      submitKeyRef.current = newIdempotencyKey();
      fieldErrors.clearAll();
      toast.success(t('student.proposals.submitSuccess'));
      setView('list');
      setStep(0);
      refetch();
    } catch (err) {
      handleMutationError(err, { fieldErrors, refetch, fallbackMessage: t('common.unknownError') });
    } finally {
      setSaving(false);
    }
  };

  const handleWithdraw = async (p: EventProposal) => {
    try {
      await api.eventProposals.withdraw(p.id);
      toast.success(t('student.proposals.withdrawn'));
      refetch();
      setView('list');
    } catch (err) {
      handleMutationError(err, { refetch, fallbackMessage: t('common.unknownError') });
    }
  };

  const apiFieldForFormKey: Partial<Record<keyof typeof formData, string>> = {
    title: 'title',
    goal: 'objective',
    categoryId: 'categoryId',
    description: 'description',
    expectedOutcomes: 'expectedOutcome',
    plannedDate: 'plannedStartAt',
    location: 'location',
    activityFormat: 'format',
    estimatedDurationHours: 'proposedRecognizedMinutes',
    estimatedCapacity: 'capacity',
    targetParticipants: 'targetParticipants',
    volunteerTasks: 'volunteerTasks',
    coOrganizerStudents: 'coOrganizerStudentIds',
    mentorTeacherOption: 'proposedSupervisorId',
    partnerOrgOption: 'organizationId',
    contactInfo: 'externalContact',
    riskMeasures: 'safetyPlan',
    materialNeeds: 'resourceNeeds',
    budgetNeeds: 'resourceNeeds',
    recruitmentMethod: 'recruitmentPlan',
    eligibility: 'eligibility',
    proofMethod: 'evidencePlan',
    suggestedHours: 'proposedRecognizedMinutes',
  };

  const field = (
    label: string,
    key: keyof typeof formData,
    opts?: { placeholder?: string; type?: string; rows?: number; required?: boolean }
  ) => {
    const apiField = apiFieldForFormKey[key] ?? String(key);
    const errMsg = fieldErrors.getError(apiField, String(key));
    return (
    <div>
      <label className="block text-xs text-muted-foreground mb-1.5">
        {label}{opts?.required !== false && <span className="text-destructive ml-0.5">*</span>}
      </label>
      {opts?.rows ? (
        <textarea
          rows={opts.rows}
          value={String(formData[key])}
          onChange={e => { fieldErrors.clearField(apiField); setFormData(f => ({ ...f, [key]: e.target.value })); }}
          placeholder={opts.placeholder}
          className="w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm font-mono focus:outline-none focus:ring-2 focus:ring-primary/50 resize-none overflow-y-auto"
        />
      ) : (
        <input
          type={opts?.type || 'text'}
          value={String(formData[key])}
          onChange={e => { fieldErrors.clearField(apiField); setFormData(f => ({ ...f, [key]: opts?.type === 'number' ? Number(e.target.value) : e.target.value })); }}
          placeholder={opts?.placeholder}
          className="w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-primary/50"
        />
      )}
      <FieldError message={errMsg} />
    </div>
  );};

  // ── Form view ──────────────────────────────────────────────────────────────
  if (view === 'form') {
    return (
      <div className="p-4 lg:p-6 max-w-3xl mx-auto">
        {/* Header */}
        <div className="flex items-center gap-3 mb-6">
          <button onClick={() => setView('list')} className="p-2 rounded-xl hover:bg-accent text-muted-foreground">
            <X className="w-4 h-4" />
          </button>
          <div>
            <h2 className="text-foreground">{t(editing ? 'student.proposals.editProposal' : 'student.proposals.newProposal')}</h2>
            <p className="text-xs text-muted-foreground">{t('common.step')} {step + 1} / {STEP_KEYS.length}: {t(`student.proposals.step_${STEP_KEYS[step]}`)}</p>
          </div>
        </div>

        {/* Step indicator */}
        <div className="flex items-center gap-1 mb-6 overflow-x-auto pb-2">
          {STEP_KEYS.map((sk, i) => (
            <div key={i} className="flex items-center gap-1 shrink-0">
              <button
                onClick={() => i < step && setStep(i)}
                className={`w-7 h-7 rounded-full text-xs flex items-center justify-center transition-colors ${
                  i === step ? 'bg-[oklch(0.488_0.243_264.376)] text-white' :
                  i < step ? 'bg-blue-100 text-blue-700 dark:bg-blue-500/20 dark:text-blue-400 cursor-pointer hover:bg-blue-200 dark:hover:bg-blue-500/30' :
                  'bg-muted text-muted-foreground'
                }`}
              >
                {i < step ? <CheckCircle className="w-3.5 h-3.5" /> : i + 1}
              </button>
              <span className={`text-xs whitespace-nowrap hidden sm:block ${i === step ? 'text-foreground' : 'text-muted-foreground'}`}>
                {t(`student.proposals.step_${sk}`)}
              </span>
              {i < STEP_KEYS.length - 1 && <div className="w-4 h-px bg-border shrink-0 mx-1" />}
            </div>
          ))}
        </div>

        {/* Step content */}
        <div className="bg-card border border-border rounded-2xl p-5 mb-4 space-y-4">

          {step === 0 && (
            <>
              {field(t('student.proposals.eventName'), 'title', { placeholder: t('student.proposals.eventNamePlaceholder') })}
              <div>
                <label className="block text-xs text-muted-foreground mb-1.5">{t('student.proposals.serviceCategory')}<span className="text-destructive ml-0.5">*</span></label>
                <select value={formData.categoryId} onChange={e => setFormData(f => ({ ...f, categoryId: e.target.value }))}
                  className="w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-primary/50">
                  {serviceCategories.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
                </select>
              </div>
              {field(t('student.proposals.publicGoal'), 'goal', { placeholder: t('student.proposals.publicGoalPlaceholder'), rows: 3 })}
              {field(t('student.proposals.eventDescription'), 'description', { placeholder: t('student.proposals.eventDescPlaceholder'), rows: 6 })}
              {field(t('student.proposals.expectedOutcomes'), 'expectedOutcomes', { placeholder: t('student.proposals.outcomesPlaceholder'), rows: 3 })}
            </>
          )}

          {step === 1 && (
            <>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                {field(t('student.proposals.plannedDate'), 'plannedDate', { type: 'date' })}
                {field(t('student.proposals.location'), 'location', { placeholder: t('student.proposals.locationPlaceholder') })}
              </div>
              {field(t('student.proposals.activityFormat'), 'activityFormat', { placeholder: t('student.proposals.formatPlaceholder') })}
              <div className="grid grid-cols-2 gap-4">
                {field(t('student.proposals.estimatedDuration'), 'estimatedDurationHours', { type: 'number' })}
                {field(t('student.proposals.estimatedVolunteers'), 'estimatedCapacity', { type: 'number' })}
              </div>
              {field(t('student.proposals.targetParticipants'), 'targetParticipants', { placeholder: t('student.proposals.participantsPlaceholder') })}
              {field(t('student.proposals.volunteerTasks'), 'volunteerTasks', { placeholder: t('student.proposals.tasksPlaceholder'), rows: 4 })}
            </>
          )}

          {step === 2 && (
            <>
              <div className="p-3 bg-muted/50 rounded-xl text-xs text-muted-foreground">
                {t('student.proposals.initiatorAuto')}
              </div>
              <div>
                <label className="block text-xs text-muted-foreground mb-1.5">
                  {t('student.proposals.coOrganizers')}
                </label>
                <SearchPicker
                  multi
                  selected={formData.coOrganizerStudents}
                  onChange={opts => { fieldErrors.clearField('coOrganizerStudentIds'); setFormData(f => ({ ...f, coOrganizerStudents: opts })); }}
                  fetchOptions={async search => (await api.students.directory(search, user?.studentId))
                    .map(s => ({ id: s.id, label: s.displayName, sublabel: formatGradeClass(s.gradeName, s.className) }))}
                  placeholder={t('student.proposals.coOrganizersPlaceholder')}
                  searchPlaceholder={t('student.proposals.searchStudents')}
                  noResultsLabel={t('student.proposals.noResults')}
                  loadingLabel={t('common.loading')}
                />
                <FieldError message={fieldErrors.getError('coOrganizerStudentIds', 'coOrganizerStudents')} />
              </div>
              <div>
                <label className="block text-xs text-muted-foreground mb-1.5">
                  {t('student.proposals.mentorTeacher')}<span className="text-destructive ml-0.5">*</span>
                </label>
                <SearchPicker
                  selected={formData.mentorTeacherOption}
                  onChange={opts => { fieldErrors.clearField('proposedSupervisorId'); setFormData(f => ({ ...f, mentorTeacherOption: opts })); }}
                  fetchOptions={async search => (await api.users.directory('teacher', search))
                    .map(teacher => ({ id: teacher.id, label: teacher.displayName }))}
                  placeholder={t('student.proposals.mentorPlaceholder')}
                  searchPlaceholder={t('student.proposals.searchTeachers')}
                  noResultsLabel={t('student.proposals.noResults')}
                  loadingLabel={t('common.loading')}
                />
                <FieldError message={fieldErrors.getError('proposedSupervisorId', 'mentorTeacherOption')} />
              </div>
              <div>
                <label className="block text-xs text-muted-foreground mb-1.5">
                  {t('student.proposals.partnerOrg')}
                </label>
                <SearchPicker
                  selected={formData.partnerOrgOption}
                  onChange={opts => { fieldErrors.clearField('organizationId'); setFormData(f => ({ ...f, partnerOrgOption: opts })); }}
                  fetchOptions={async search => {
                    const page = await api.organizations.list({ search, pageSize: 20 });
                    return page.data.map(o => ({ id: o.id, label: o.name }));
                  }}
                  placeholder={t('student.proposals.partnerOrgPlaceholder')}
                  searchPlaceholder={t('student.proposals.searchOrganizations')}
                  noResultsLabel={t('student.proposals.noResults')}
                  loadingLabel={t('common.loading')}
                  allowCreateNew
                  createNewLabel={name => t('student.proposals.createNewOrg', { name })}
                />
                <FieldError message={fieldErrors.getError('organizationId', 'partnerOrgOption')} />
              </div>
              {field(t('student.proposals.contactInfo'), 'contactInfo', { placeholder: t('student.proposals.contactPlaceholder') })}
            </>
          )}

          {step === 3 && (
            <>
              {field(t('student.proposals.riskMeasures'), 'riskMeasures', { placeholder: t('student.proposals.riskPlaceholder'), rows: 5 })}
              {field(t('student.proposals.materialNeeds'), 'materialNeeds', { placeholder: t('student.proposals.materialPlaceholder'), rows: 3 })}
              {field(t('student.proposals.budgetNeeds'), 'budgetNeeds', { placeholder: t('student.proposals.budgetPlaceholder'), rows: 3 })}
            </>
          )}

          {step === 4 && (
            <>
              {field(t('student.proposals.recruitmentMethod'), 'recruitmentMethod', { placeholder: t('student.proposals.recruitmentPlaceholder') })}
              {field(t('student.proposals.eligibility'), 'eligibility', { placeholder: t('student.proposals.eligibilityPlaceholder') })}
              {field(t('student.proposals.verificationMethod'), 'proofMethod', { placeholder: t('student.proposals.verificationPlaceholder') })}
              {field(t('student.proposals.suggestedHours'), 'suggestedHours', { type: 'number' })}
            </>
          )}

          {step === 5 && (
            <div className="space-y-4">
              <h3 className="text-foreground">{t('student.proposals.summary')}</h3>
              <div className="space-y-3 text-sm">
                {[
                  [t('student.proposals.eventName'), formData.title || t('student.proposals.notFilled')],
                  [t('student.proposals.serviceCategory'), serviceCategories.find(c => c.id === formData.categoryId)?.name ?? formData.categoryId],
                  [t('student.proposals.plannedDate'), formData.plannedDate || t('student.proposals.notFilled')],
                  [t('student.proposals.location'), formData.location || t('student.proposals.notFilled')],
                  [t('student.proposals.estimatedDuration'), `${formData.estimatedDurationHours} ${t('common.hours')}`],
                  [t('student.proposals.estimatedVolunteers'), `${formData.estimatedCapacity} ${t('common.people')}`],
                  [t('student.proposals.mentorTeacher'), formData.mentorTeacherOption[0]?.label || t('student.proposals.tbd')],
                  [t('student.proposals.suggestedHours'), `${formData.suggestedHours} ${t('common.hours')}`],
                ].map(([k, v]) => (
                  <div key={k} className="flex gap-3">
                    <span className="text-muted-foreground w-24 shrink-0">{k}</span>
                    <span className="text-foreground flex-1">{v}</span>
                  </div>
                ))}
              </div>

              {formData.goal && (
                <div className="border border-border rounded-xl p-3">
                  <div className="text-xs text-muted-foreground mb-1">{t('student.proposals.publicGoal')}</div>
                  <p className="text-sm text-foreground">{formData.goal}</p>
                </div>
              )}

              <div className="border border-border rounded-xl p-4 space-y-2 bg-muted/30">
                <p className="text-xs text-muted-foreground">
                  {t('student.proposals.declarationText')}
                </p>
                <label className="flex items-start gap-2 cursor-pointer">
                  <input type="checkbox" checked={declared} onChange={e => setDeclared(e.target.checked)}
                    className="mt-0.5 accent-primary" />
                  <span className="text-sm text-foreground">{t('student.proposals.declarationAgree')}</span>
                </label>
              </div>
            </div>
          )}
        </div>

        {/* Navigation */}
        <div className="flex gap-3">
          {step > 0 && (
            <button onClick={() => setStep(s => s - 1)}
              className="px-4 py-2.5 rounded-xl border border-border text-foreground text-sm hover:bg-accent transition-colors">
              {t('common.previous')}
            </button>
          )}
          <div className="flex-1" />
          <button onClick={handleSaveDraft}
            className="px-4 py-2.5 rounded-xl border border-border text-muted-foreground text-sm hover:bg-accent transition-colors">
            {t('student.proposals.saveDraft')}
          </button>
          {step < STEP_KEYS.length - 1 ? (
            <button onClick={() => setStep(s => s + 1)}
              className="px-5 py-2.5 rounded-xl bg-[oklch(0.488_0.243_264.376)] text-white text-sm hover:opacity-90 transition-opacity">
              {t('common.next')}
            </button>
          ) : (
            <button onClick={handleSubmit}
              className="px-5 py-2.5 rounded-xl bg-[oklch(0.488_0.243_264.376)] text-white text-sm hover:opacity-90 transition-opacity flex items-center gap-1.5">
              <Send className="w-4 h-4" /> {t('student.proposals.submitForReview')}
            </button>
          )}
        </div>
      </div>
    );
  }

  // ── Detail view ────────────────────────────────────────────────────────────
  if (view === 'detail' && detailProposal) {
    const p = detailProposal;
    const statusInfo = PROPOSAL_STATUS_LABELS[p.status];

    return (
      <div className="p-4 lg:p-6 max-w-4xl mx-auto">
        {/* Header */}
        <div className="flex items-start gap-3 mb-5">
          <button onClick={() => { setView('list'); setDetailId(null); }}
            className="p-2 rounded-xl hover:bg-accent text-muted-foreground shrink-0 mt-0.5">
            <ChevronRight className="w-4 h-4 rotate-180" />
          </button>
          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-2 mb-1 flex-wrap">
              <span className={`text-xs px-2 py-0.5 rounded-full ${colorMap[statusInfo.color]}`}>{t(statusInfo.label)}</span>
              <span className="text-xs text-muted-foreground">{p.categoryName}</span>
              {p.revisionCount > 0 && (
                <span className="text-xs bg-muted text-muted-foreground px-2 py-0.5 rounded-full">v{p.revisionCount + 1}</span>
              )}
            </div>
            <h2 className="text-foreground leading-snug">{p.title}</h2>
            <p className="text-xs text-muted-foreground mt-0.5">
              {p.myRole} · {t('student.proposals.lastUpdated')} {p.updatedAt.slice(0, 10)}
            </p>
          </div>
        </div>

        <div className="lg:grid lg:grid-cols-3 lg:gap-6">
          {/* Left: proposal content */}
          <div className="lg:col-span-2 space-y-4">

            {/* Revision feedback */}
            {p.status === 'changesRequested' && p.feedback && (
              <div className="bg-orange-50 dark:bg-orange-900/20 border border-orange-200 dark:border-orange-800 rounded-xl p-4 flex gap-3">
                <AlertTriangle className="w-5 h-5 text-orange-500 shrink-0 mt-0.5" />
                <div>
                  <div className="text-sm text-orange-800 dark:text-orange-200 font-medium mb-1">
                    {t('student.proposals.reviewerRequested', { name: p.reviewerName })}
                  </div>
                  <p className="text-xs text-orange-700 dark:text-orange-300">{p.feedback}</p>
                </div>
              </div>
            )}

            {/* Approved: linked project */}
            {p.status === 'approved' && p.linkedProjectTitle && (
              <div className="bg-green-50 dark:bg-green-900/20 border border-green-200 dark:border-green-800 rounded-xl p-4 flex gap-3">
                <CheckCircle className="w-5 h-5 text-green-600 shrink-0 mt-0.5" />
                <div>
                  <div className="text-sm text-green-800 dark:text-green-200 font-medium mb-0.5">{t('student.proposals.approvedMsg')}</div>
                  <p className="text-xs text-green-700 dark:text-green-300 mb-2">{p.feedback}</p>
                  <div className="text-xs text-green-600 dark:text-green-400">
                    {t('student.proposals.linkedProject')}: <span className="font-medium">{p.linkedProjectTitle}</span> ({t('student.proposals.pendingPublication', { name: p.reviewerName })})
                  </div>
                </div>
              </div>
            )}

            {/* Sections */}
            {[
              {
                title: t('student.proposals.step_overview'),
                rows: [
                  [t('student.proposals.publicGoal'), p.goal],
                  [t('student.proposals.eventDescription'), p.description],
                  [t('student.proposals.expectedOutcomes'), p.expectedOutcomes],
                ],
              },
              {
                title: t('student.proposals.step_implementation'),
                rows: [
                  [t('student.proposals.plannedDate'), p.plannedDate],
                  [t('student.proposals.location'), p.location],
                  [t('student.proposals.activityFormat'), p.activityFormat],
                  [t('student.proposals.estimatedDuration'), `${minutesToHours(p.estimatedDurationMinutes)} ${t('common.hours')}`],
                  [t('student.proposals.estimatedVolunteers'), `${p.estimatedCapacity} ${t('common.volunteers')}`],
                  [t('student.proposals.targetParticipants'), p.targetParticipants],
                  [t('student.proposals.volunteerTasks'), p.volunteerTasks],
                ],
              },
              {
                title: t('student.proposals.step_organization'),
                rows: [
                  [t('student.proposals.studentInitiator'), p.initiatorName],
                  [t('student.proposals.coOrganizers'), p.coOrganizers || t('common.none')],
                  [t('student.proposals.mentorTeacher'), p.mentorTeacher || t('student.proposals.tbd')],
                  [t('student.proposals.partnerOrg'), p.partnerOrg || t('common.none')],
                  [t('student.proposals.contactInfo'), p.contactInfo],
                ],
              },
              {
                title: t('student.proposals.step_safety'),
                rows: [
                  [t('student.proposals.riskMeasures'), p.riskMeasures],
                  [t('student.proposals.materialNeeds'), p.materialNeeds],
                  [t('student.proposals.budgetNeeds'), p.budgetNeeds],
                ],
              },
              {
                title: t('student.proposals.step_recruitment'),
                rows: [
                  [t('student.proposals.recruitmentMethod'), p.recruitmentMethod],
                  [t('student.proposals.eligibility'), p.eligibility],
                  [t('student.proposals.verificationMethod'), p.proofMethod],
                  [t('student.proposals.suggestedHours'), `${minutesToHours(p.suggestedMinutes)} ${t('common.hours')}`],
                ],
              },
            ].map(section => (
              <div key={section.title} className="bg-card border border-border rounded-2xl p-4">
                <h3 className="text-sm font-medium text-foreground mb-3">{section.title}</h3>
                <div className="space-y-2.5">
                  {section.rows.map(([k, v]) => v ? (
                    <div key={k} className="flex gap-3 text-sm">
                      <span className="text-muted-foreground shrink-0 w-28">{k}</span>
                      <span className="text-foreground flex-1 whitespace-pre-wrap">{v}</span>
                    </div>
                  ) : null)}
                </div>
              </div>
            ))}
          </div>

          {/* Right: status + timeline + actions */}
          <div className="space-y-4 mt-4 lg:mt-0">

            {/* Status card */}
            <div className="bg-card border border-border rounded-2xl p-4">
              <h3 className="text-sm font-medium text-foreground mb-3">{t('student.proposals.approvalStatus')}</h3>
              <div className="space-y-2 text-xs">
                <div className="flex justify-between">
                  <span className="text-muted-foreground">{t('student.proposals.currentNode')}</span>
                  <span className={`px-2 py-0.5 rounded-full ${colorMap[statusInfo.color]}`}>{p.currentApprovalNode}</span>
                </div>
                {p.reviewerName && (
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">{t('student.proposals.reviewer')}</span>
                    <span className="text-foreground">{p.reviewerName}</span>
                  </div>
                )}
                <div className="flex justify-between">
                  <span className="text-muted-foreground">{t('student.proposals.version')}</span>
                  <span className="text-foreground">v{p.revisionCount + 1}</span>
                </div>
                {p.submittedAt && (
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">{t('student.proposals.submitted')}</span>
                    <span className="text-foreground">{p.submittedAt.slice(0, 10)}</span>
                  </div>
                )}
              </div>
            </div>

            {/* Actions */}
            <div className="bg-card border border-border rounded-2xl p-4 space-y-2">
              <h3 className="text-sm font-medium text-foreground mb-3">{t('student.proposals.availableActions')}</h3>

              {(p.status === 'draft' || p.status === 'changesRequested') && (
                <button onClick={() => { openForm(p, 'edit'); }}
                  className="w-full flex items-center gap-2 px-3 py-2.5 rounded-xl bg-[oklch(0.488_0.243_264.376)] text-white text-sm hover:opacity-90 transition-opacity">
                  <Edit className="w-4 h-4" />
                  {p.status === 'changesRequested' ? t('student.proposals.reviseResubmit') : t('student.proposals.editDraft')}
                </button>
              )}

              {p.status === 'draft' && (
                <button onClick={async () => {
                  try {
                    const key = newIdempotencyKey();
                    await api.eventProposals.submit(p.id, key);
                    toast.success(t('student.proposals.submittedForReview'));
                    refetch();
                    setView('list');
                  } catch (err) {
                    handleMutationError(err, { refetch, fallbackMessage: t('common.unknownError') });
                  }
                }}
                  className="w-full flex items-center gap-2 px-3 py-2.5 rounded-xl border border-border text-foreground text-sm hover:bg-accent transition-colors">
                  <Send className="w-4 h-4" /> {t('student.proposals.submitForReview')}
                </button>
              )}

              {(p.status === 'submitted' || p.status === 'changesRequested') && (
                <button onClick={() => handleWithdraw(p)}
                  className="w-full flex items-center gap-2 px-3 py-2.5 rounded-xl border border-border text-muted-foreground text-sm hover:bg-accent transition-colors">
                  <RotateCcw className="w-4 h-4" />
                  {p.status === 'submitted' ? t('student.proposals.withdrawProposal') : t('student.proposals.requestWithdrawal')}
                </button>
              )}

              <button onClick={() => openForm(p)}
                className="w-full flex items-center gap-2 px-3 py-2.5 rounded-xl border border-border text-muted-foreground text-sm hover:bg-accent transition-colors">
                <Copy className="w-4 h-4" /> {t('student.proposals.copyProposal')}
              </button>
            </div>

            {/* Timeline */}
            <div className="bg-card border border-border rounded-2xl p-4">
              <h3 className="text-sm font-medium text-foreground mb-3">{t('student.proposals.timeline')}</h3>
              <div className="space-y-3">
                {p.timeline.map((evt, i) => (
                  <div key={i} className="flex gap-3">
                    <div className="flex flex-col items-center shrink-0">
                      <div className="w-2 h-2 rounded-full bg-primary mt-1" />
                      {i < p.timeline.length - 1 && <div className="w-px flex-1 bg-border mt-1" />}
                    </div>
                    <div className="pb-3 flex-1">
                      <div className="text-xs font-medium text-foreground">{evt.action}</div>
                      <div className="text-xs text-muted-foreground">{evt.actor} · {evt.time.slice(0, 10)}</div>
                      {evt.note && <p className="text-xs text-muted-foreground mt-1 italic">{evt.note}</p>}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </div>
      </div>
    );
  }

  // ── List view ──────────────────────────────────────────────────────────────
  if (loading && proposals.length === 0) return <div className="p-4 lg:p-6">{t('common.appName')}</div>;

  return (
    <div className="p-4 lg:p-6">
      <div className="flex items-center justify-between mb-5">
        <div>
          <h2 className="text-foreground">{t('student.proposals.title')}</h2>
          <p className="text-sm text-muted-foreground">{t('student.proposals.subtitle')}</p>
        </div>
        <button onClick={() => openForm()}
          className="flex items-center gap-1.5 px-3 py-2 bg-[oklch(0.488_0.243_264.376)] text-white rounded-xl text-sm hover:opacity-90">
          <Plus className="w-4 h-4" /> {t('student.proposals.newProposalBtn')}
        </button>
      </div>

      {/* Status filter tabs */}
      <div className="flex gap-2 overflow-x-auto pb-1 mb-4 -mx-4 px-4">
        {([
          ['all', t('student.proposals.tabAll')],
          ['draft', t('student.proposals.tabDraft')],
          ['submitted', t('student.proposals.tabSubmitted')],
          ['changesRequested', t('student.proposals.tabChangesRequested')],
          ['approved', t('student.proposals.tabApproved')],
          ['rejected', t('student.proposals.tabRejected')],
          ['withdrawn', t('student.proposals.tabWithdrawn')],
        ] as const).map(([val, label]) => (
          <button key={val} onClick={() => {
            setFilterStatus(val as ProposalStatus | 'all');
            setFilter('status', val === 'all' ? undefined : val);
          }}
            className={`px-3 py-1.5 rounded-full text-xs whitespace-nowrap transition-colors ${
              filterStatus === val ? 'bg-blue-100 text-blue-700 dark:bg-blue-500/20 dark:text-blue-300' : 'bg-muted text-muted-foreground hover:bg-accent'
            }`}>
            {label}
          </button>
        ))}
      </div>

      <ListStateBanner error={error} empty={!loading && proposals.length === 0} emptyLabel={t('student.proposals.noProposals')} />

      {/* Proposals list — desktop table / mobile cards */}
      {proposals.length === 0 ? null : (
        <>
          {/* Desktop table */}
          <div className="hidden lg:block bg-card border border-border rounded-2xl overflow-hidden">
            <table className="w-full text-sm">
              <thead className="bg-muted/50 border-b border-border">
                <tr>
                  <th className="text-left px-4 py-3 text-xs text-muted-foreground font-medium">{t('student.proposals.eventName')}</th>
                  <th className="text-left px-4 py-3 text-xs text-muted-foreground font-medium">{t('student.proposals.category')}</th>
                  <th className="text-left px-4 py-3 text-xs text-muted-foreground font-medium">{t('student.proposals.plannedDate')}</th>
                  <th className="text-left px-4 py-3 text-xs text-muted-foreground font-medium">{t('student.proposals.currentNode')}</th>
                  <th className="text-left px-4 py-3 text-xs text-muted-foreground font-medium">{t('student.proposals.status')}</th>
                  <th className="text-left px-4 py-3 text-xs text-muted-foreground font-medium">{t('student.proposals.lastUpdated')}</th>
                  <th className="text-right px-4 py-3 text-xs text-muted-foreground font-medium">{t('student.records.colActions')}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {proposals.map(p => {
                  const statusInfo = PROPOSAL_STATUS_LABELS[p.status];
                  return (
                    <tr key={p.id} className="hover:bg-muted/30 transition-colors cursor-pointer"
                      onClick={() => { setDetailId(p.id); setView('detail'); }}>
                      <td className="px-4 py-3">
                        <div className="text-sm text-foreground font-medium">{p.title}</div>
                        <div className="text-xs text-muted-foreground">{p.myRole}</div>
                      </td>
                      <td className="px-4 py-3 text-xs text-muted-foreground">{p.categoryName}</td>
                      <td className="px-4 py-3 text-xs text-muted-foreground">{p.plannedDate || '—'}</td>
                      <td className="px-4 py-3 text-xs text-muted-foreground">{p.currentApprovalNode}</td>
                      <td className="px-4 py-3">
                        <span className={`text-xs px-2 py-0.5 rounded-full ${colorMap[statusInfo.color]}`}>{t(statusInfo.label)}</span>
                      </td>
                      <td className="px-4 py-3 text-xs text-muted-foreground">{p.updatedAt.slice(0, 10)}</td>
                      <td className="px-4 py-3" onClick={e => e.stopPropagation()}>
                        <div className="flex gap-1 justify-end">
                          {(p.status === 'draft' || p.status === 'changesRequested') && (
                            <button onClick={() => openForm(p, 'edit')}
                              className="text-xs px-2 py-1 rounded-lg border border-border hover:bg-accent text-muted-foreground flex items-center gap-1">
                              <Edit className="w-3 h-3" /> {t('common.edit')}
                            </button>
                          )}
                          <button onClick={() => openForm(p)}
                            className="text-xs px-2 py-1 rounded-lg border border-border hover:bg-accent text-muted-foreground flex items-center gap-1">
                            <Copy className="w-3 h-3" /> {t('common.copy')}
                          </button>
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
            {proposals.map(p => {
              const statusInfo = PROPOSAL_STATUS_LABELS[p.status];
              return (
                <div key={p.id} className="bg-card border border-border rounded-2xl p-4"
                  onClick={() => { setDetailId(p.id); setView('detail'); }}>

                  {p.status === 'changesRequested' && (
                    <div className="flex items-center gap-1.5 text-xs text-orange-600 dark:text-orange-400 mb-2">
                      <AlertTriangle className="w-3.5 h-3.5" /> {t('student.proposals.revisionsRequired')}
                    </div>
                  )}
                  {p.status === 'approved' && (
                    <div className="flex items-center gap-1.5 text-xs text-green-600 dark:text-green-400 mb-2">
                      <CheckCircle className="w-3.5 h-3.5" /> {t('student.proposals.approvedMsg')}
                    </div>
                  )}

                  <div className="flex items-start justify-between gap-2 mb-2">
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2 mb-1 flex-wrap">
                        <span className={`text-xs px-2 py-0.5 rounded-full ${colorMap[statusInfo.color]}`}>{t(statusInfo.label)}</span>
                        <span className="text-xs text-muted-foreground">{p.categoryName}</span>
                      </div>
                      <div className="text-sm text-foreground font-medium leading-snug">{p.title}</div>
                    </div>
                    <ChevronRight className="w-4 h-4 text-muted-foreground shrink-0 mt-0.5" />
                  </div>

                  <div className="grid grid-cols-2 gap-y-1 text-xs text-muted-foreground">
                    <div className="flex items-center gap-1">
                      <Clock className="w-3 h-3" />
                      {p.plannedDate || t('student.proposals.dateTbd')}
                    </div>
                    <div>{t('student.proposals.nodeLabel')}: {p.currentApprovalNode}</div>
                    <div>{t('student.proposals.roleLabel')}: {p.myRole}</div>
                    <div>{t('student.proposals.updatedLabel')}: {p.updatedAt.slice(0, 10)}</div>
                  </div>

                  {p.status === 'approved' && p.linkedProjectTitle && (
                    <div className="mt-2 text-xs text-green-600 dark:text-green-400 truncate">
                      {t('student.proposals.linkedProject')}: {p.linkedProjectTitle}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        </>
      )}

      <PageControls page={page} onPageChange={setPage} />
    </div>
  );
}
