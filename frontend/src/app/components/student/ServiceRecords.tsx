import { useState, useRef } from 'react';
import { Plus, Search, X, FileText, AlertTriangle, ChevronDown, ChevronUp, Upload, Send, Trash2 } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { RECORD_STATUS_LABELS, SERVICE_CATEGORIES, resolveCategoryName } from '../../mock/data';
import { type RecordStatus, type ServiceRecord, type EvidenceFile } from '../../api/types';
import { localDateTimeToISO } from '../../lib/datetime';
import { formatCoins } from '../../lib/format';
import { toast } from 'sonner';
import { api } from '../../api/client';
import { ApiError, mapApiError, newIdempotencyKey } from '../../api/http';
import { useFieldErrors, FieldError, handleMutationError } from '../../api/useFieldErrors';
import { usePagedApi } from '../../api/useApi';
import { PageControls } from '../PageControls';
import { ListStateBanner } from '../ListStateBanner';
import { EvidenceLink } from '../EvidenceLink';

const colorMap: Record<string, string> = {
  gray: 'bg-muted text-muted-foreground',
  blue: 'bg-blue-100 dark:bg-blue-900/30 text-blue-700 dark:text-blue-400',
  yellow: 'bg-yellow-100 dark:bg-yellow-900/30 text-yellow-700 dark:text-yellow-400',
  green: 'bg-green-100 dark:bg-green-900/30 text-green-700 dark:text-green-400',
  orange: 'bg-orange-100 dark:bg-orange-900/30 text-orange-700 dark:text-orange-400',
  red: 'bg-red-100 dark:bg-red-900/30 text-red-700 dark:text-red-400',
  purple: 'bg-purple-100 dark:bg-purple-900/30 text-purple-700 dark:text-purple-400',
};

type ViewMode = 'list' | 'new';

const STEP_KEYS = ['source', 'serviceInfo', 'evidence', 'confirmSubmit'];

export function ServiceRecords() {
  const { t, i18n } = useTranslation();
  const { items: records, page, loading, error, setFilter, setSearch: setPagedSearch, setPage, refetch } = usePagedApi(api.serviceRecords.list, {});
  const [view, setView] = useState<ViewMode>('list');
  const [search, setSearch] = useState('');
  const [filterStatus, setFilterStatus] = useState<RecordStatus | 'all'>('all');
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [step, setStep] = useState(0);
  const [saving, setSaving] = useState(false);
  const submitKeyRef = useRef(newIdempotencyKey());
  const fieldErrors = useFieldErrors();

  // Appeal-a-rejected-record modal state (I2-d8, cases domain)
  const [appealRecord, setAppealRecord] = useState<ServiceRecord | null>(null);
  const [appealReason, setAppealReason] = useState('');
  const [appealSaving, setAppealSaving] = useState(false);
  const appealKeyRef = useRef(newIdempotencyKey());

  const handleSubmitAppeal = async () => {
    if (!appealRecord || !appealReason.trim()) { toast.error(t('student.records.appealReasonRequired')); return; }
    setAppealSaving(true);
    try {
      const created = await api.cases.create({
        type: 'appeal',
        subjectType: 'serviceRecord',
        subjectId: appealRecord.id,
        title: `${t('student.records.appeal')}: ${appealRecord.projectTitle || appealRecord.organizationName}`,
        description: appealReason,
        reasonCode: 'STUDENT_APPEAL',
      });
      await api.cases.submit(created.id, appealKeyRef.current);
      appealKeyRef.current = newIdempotencyKey();
      toast.success(t('student.records.appealSubmitted'));
      setAppealRecord(null);
      setAppealReason('');
    } catch (err) {
      if (err instanceof ApiError) toast.error(mapApiError(err).message);
      else toast.error(t('common.unknownError'));
    } finally {
      setAppealSaving(false);
    }
  };

  // Evidence upload — the "evidence" step existed as a purely decorative
  // dropzone (no <input>, no handler) even though `api.files` was fully
  // wired in client.ts; submit requires at least one evidence file
  // (`ServiceRecordServiceImpl`'s EVIDENCE_REQUIRED check), so no record
  // could ever actually be submitted through the real UI. Caught only by
  // driving the browser through the flow, not by source review.
  const [evidenceFiles, setEvidenceFiles] = useState<EvidenceFile[]>([]);
  const [uploading, setUploading] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

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

  // New record form state
  const [formData, setFormData] = useState({
    sourceType: 'external',
    serviceDate: '',
    startTime: '',
    endTime: '',
    location: '',
    organizationName: '',
    categoryId: 'cat_community',
    description: '',
    supervisorName: '',
    supervisorContact: '',
  });

  const handleSubmitRecord = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    try {
      const startTime = formData.startTime ? localDateTimeToISO(formData.serviceDate, formData.startTime) : undefined;
      const endTime = formData.endTime ? localDateTimeToISO(formData.serviceDate, formData.endTime) : undefined;
      const record = await api.serviceRecords.create({
        sourceType: formData.sourceType as 'project' | 'external' | 'self',
        serviceDate: formData.serviceDate,
        startAt: startTime,
        endAt: endTime,
        location: formData.location,
        organizationName: formData.organizationName,
        serviceCategoryId: formData.categoryId,
        description: formData.description,
        supervisorName: formData.supervisorName,
        supervisorContact: formData.supervisorContact,
        evidenceFiles,
      });
      await api.serviceRecords.submit(record.id, submitKeyRef.current);
      submitKeyRef.current = newIdempotencyKey();
      fieldErrors.clearAll();
      toast.success(t('student.records.submitSuccess'));
      setView('list');
      setStep(0);
      setEvidenceFiles([]);
      refetch();
    } catch (err) {
      handleMutationError(err, { fieldErrors, fallbackMessage: t('common.unknownError') });
    } finally {
      setSaving(false);
    }
  };

  const handleSaveDraft = async () => {
    setSaving(true);
    try {
      const startTime = formData.startTime ? localDateTimeToISO(formData.serviceDate, formData.startTime) : undefined;
      const endTime = formData.endTime ? localDateTimeToISO(formData.serviceDate, formData.endTime) : undefined;
      await api.serviceRecords.create({
        sourceType: formData.sourceType as 'project' | 'external' | 'self',
        serviceDate: formData.serviceDate,
        startAt: startTime,
        endAt: endTime,
        location: formData.location,
        organizationName: formData.organizationName,
        serviceCategoryId: formData.categoryId,
        description: formData.description,
        supervisorName: formData.supervisorName,
        supervisorContact: formData.supervisorContact,
        evidenceFiles,
      });
      fieldErrors.clearAll();
      toast.success(t('student.records.draftSaved'));
      setEvidenceFiles([]);
      refetch();
    } catch (err) {
      handleMutationError(err, { fieldErrors, fallbackMessage: t('common.unknownError') });
    } finally {
      setSaving(false);
    }
  };

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
      if (err instanceof ApiError) toast.error(mapApiError(err).message);
      else toast.error(t('common.unknownError'));
    }
  };

  if (view === 'new') {
    return (
      <div className="px-4 py-4">
        {/* Header */}
        <div className="flex items-center gap-3 mb-6">
          <button onClick={() => { setView('list'); setStep(0); }} className="text-muted-foreground hover:text-foreground p-1">
            <X className="w-5 h-5" />
          </button>
          <h2 className="text-foreground flex-1">{t('student.records.submitTitle')}</h2>
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

        <form onSubmit={handleSubmitRecord} className="space-y-4">
          {/* Step 0: Source */}
          {step === 0 && (
            <div className="space-y-4">
              <div>
                <label className="block text-sm text-foreground mb-2">{t('student.records.serviceSource')}</label>
                <div className="space-y-2">
                  {[
                    { value: 'project', label: t('student.records.sourceProject'), desc: t('student.records.sourceProjectDesc') },
                    { value: 'external', label: t('student.records.sourceExternal'), desc: t('student.records.sourceExternalDesc') },
                    { value: 'self', label: t('student.records.sourceSelf'), desc: t('student.records.sourceSelfDesc') },
                  ].map(opt => (
                    <button
                      type="button"
                      key={opt.value}
                      onClick={() => setFormData(f => ({ ...f, sourceType: opt.value }))}
                      className={`w-full text-left p-4 rounded-xl border-2 transition-colors ${
                        formData.sourceType === opt.value ? 'border-[oklch(0.488_0.243_264.376)] bg-blue-50 dark:bg-blue-900/20' : 'border-border hover:border-border/80 hover:bg-accent/50'
                      }`}
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
              <div>
                <label className="block text-xs text-muted-foreground mb-1.5">{t('student.records.serviceDate')} *</label>
                <input type="date" required value={formData.serviceDate} onChange={e => { fieldErrors.clearField('serviceDate'); setFormData(f => ({ ...f, serviceDate: e.target.value })); }}
                  className="w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground focus:outline-none focus:ring-2 focus:ring-primary/50 text-sm" />
                <FieldError message={fieldErrors.getError('serviceDate')} />
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs text-muted-foreground mb-1.5">{t('student.records.startTime')} *</label>
                  <input type="time" required value={formData.startTime} onChange={e => { fieldErrors.clearField('startAt'); setFormData(f => ({ ...f, startTime: e.target.value })); }}
                    className="w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground focus:outline-none focus:ring-2 focus:ring-primary/50 text-sm" />
                  <FieldError message={fieldErrors.getError('startAt', 'startTime')} />
                </div>
                <div>
                  <label className="block text-xs text-muted-foreground mb-1.5">{t('student.records.endTime')} *</label>
                  <input type="time" required value={formData.endTime} onChange={e => { fieldErrors.clearField('endAt'); setFormData(f => ({ ...f, endTime: e.target.value })); }}
                    className="w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground focus:outline-none focus:ring-2 focus:ring-primary/50 text-sm" />
                  <FieldError message={fieldErrors.getError('endAt', 'endTime')} />
                </div>
              </div>
              <div>
                <label className="block text-xs text-muted-foreground mb-1.5">{t('student.records.serviceLocation')} *</label>
                <input type="text" required placeholder={t('student.records.locationPlaceholder')} value={formData.location} onChange={e => { fieldErrors.clearField('location'); setFormData(f => ({ ...f, location: e.target.value })); }}
                  className="w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50 text-sm" />
                <FieldError message={fieldErrors.getError('location')} />
              </div>
              <div>
                <label className="block text-xs text-muted-foreground mb-1.5">{t('student.records.orgName')} *</label>
                <input type="text" required placeholder={t('student.records.locationPlaceholder')} value={formData.organizationName} onChange={e => { fieldErrors.clearField('organizationName'); setFormData(f => ({ ...f, organizationName: e.target.value })); }}
                  className="w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50 text-sm" />
                <FieldError message={fieldErrors.getError('organizationName')} />
              </div>
              <div>
                <label className="block text-xs text-muted-foreground mb-1.5">{t('student.records.serviceCategory')}</label>
                <select value={formData.categoryId} onChange={e => { fieldErrors.clearField('serviceCategoryId'); setFormData(f => ({ ...f, categoryId: e.target.value })); }}
                  className="w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground focus:outline-none focus:ring-2 focus:ring-primary/50 text-sm">
                  {SERVICE_CATEGORIES.map(cat => (
                    <option key={cat.id} value={cat.id}>{resolveCategoryName(cat.id, i18n.language)}</option>
                  ))}
                </select>
                <FieldError message={fieldErrors.getError('serviceCategoryId', 'categoryId')} />
              </div>
              <div>
                <label className="block text-xs text-muted-foreground mb-1.5">{t('student.records.serviceDesc')} *</label>
                <textarea
                  required
                  rows={10}
                  placeholder={t('student.records.descPlaceholder')}
                  value={formData.description}
                  onChange={e => { fieldErrors.clearField('description'); setFormData(f => ({ ...f, description: e.target.value })); }}
                  className="w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50 text-sm font-mono resize-none overflow-y-auto whitespace-pre-wrap"
                />
                <FieldError message={fieldErrors.getError('description')} />
              </div>
            </div>
          )}

          {/* Step 2: Evidence */}
          {step === 2 && (
            <div className="space-y-4">
              <div>
                <label className="block text-xs text-muted-foreground mb-1.5">{t('student.records.supervisorName')} *</label>
                <input type="text" required placeholder={t('student.records.supervisorPlaceholder')} value={formData.supervisorName} onChange={e => { fieldErrors.clearField('supervisorName'); setFormData(f => ({ ...f, supervisorName: e.target.value })); }}
                  className="w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50 text-sm" />
                <FieldError message={fieldErrors.getError('supervisorName')} />
              </div>
              <div>
                <label className="block text-xs text-muted-foreground mb-1.5">{t('student.records.contactInfo')}</label>
                <input type="text" placeholder={t('student.records.contactPlaceholder')} value={formData.supervisorContact} onChange={e => { fieldErrors.clearField('supervisorContact'); setFormData(f => ({ ...f, supervisorContact: e.target.value })); }}
                  className="w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50 text-sm" />
                <FieldError message={fieldErrors.getError('supervisorContact')} />
              </div>
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
                  <span className="text-foreground">{formData.startTime} - {formData.endTime || '—'}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-muted-foreground">{t('student.records.serviceLocation')}</span>
                  <span className="text-foreground text-right max-w-[60%]">{formData.location || '—'}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-muted-foreground">{t('student.records.orgName')}</span>
                  <span className="text-foreground">{formData.organizationName || '—'}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-muted-foreground">{t('student.records.supervisorName')}</span>
                  <span className="text-foreground">{formData.supervisorName || '—'}</span>
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
            {step < STEP_KEYS.length - 1 ? (
              <button type="button" onClick={() => setStep(s => s + 1)}
                className="flex-1 py-3 rounded-xl bg-[oklch(0.488_0.243_264.376)] text-white hover:opacity-90 transition-opacity text-sm">
                {t('common.next')}
              </button>
            ) : (
              <button type="submit"
                className="flex-1 py-3 rounded-xl bg-[oklch(0.488_0.243_264.376)] text-white hover:opacity-90 transition-opacity text-sm flex items-center justify-center gap-2">
                <Send className="w-4 h-4" />
                {t('student.records.confirmSubmit')}
              </button>
            )}
          </div>

          <button type="button" onClick={handleSaveDraft} disabled={saving}
            className="w-full py-2 text-sm text-muted-foreground hover:text-foreground transition-colors disabled:opacity-50">
            {t('student.records.saveDraft')}
          </button>
        </form>
      </div>
    );
  }

  if (loading && records.length === 0) return <div className="p-4 lg:p-6">{t('common.appName')}</div>;

  return (
    <div className="p-4 lg:p-6 space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-foreground">{t('student.records.title')}</h2>
        <button
          onClick={() => setView('new')}
          className="flex items-center gap-1.5 px-3 py-2 bg-[oklch(0.488_0.243_264.376)] text-white rounded-xl text-sm hover:opacity-90 transition-opacity"
        >
          <Plus className="w-4 h-4" />
          {t('student.records.newRecord')}
        </button>
      </div>

      {/* Status tabs */}
      <div className="flex gap-2 overflow-x-auto pb-1 -mx-4 px-4">
        {([
          ['all', t('student.records.tabAll')],
          ['draft', t('student.records.tabDraft')],
          ['submitted', t('student.records.tabSubmitted')],
          ['underReview', t('student.records.tabUnderReview')],
          ['returned', t('student.records.tabReturned')],
          ['approved', t('student.records.tabApproved')],
          ['rejected', t('student.records.tabRejected')],
        ] as const).map(([val, label]) => (
          <button
            key={val}
            onClick={() => {
              setFilterStatus(val as RecordStatus | 'all');
              setFilter('status', val === 'all' ? undefined : val);
            }}
            className={`px-3 py-1.5 rounded-full text-xs whitespace-nowrap transition-colors ${
              filterStatus === val
                ? 'bg-blue-100 text-blue-700 dark:bg-blue-500/20 dark:text-blue-300'
                : 'bg-muted text-muted-foreground hover:bg-accent'
            }`}
          >
            {label}
          </button>
        ))}
      </div>

      {/* Search */}
      <div className="relative">
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground" />
        <input
          type="text"
          value={search}
          onChange={e => { setSearch(e.target.value); setPagedSearch(e.target.value); }}
          placeholder={t('student.records.searchPlaceholder')}
          className="w-full pl-9 pr-4 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground placeholder:text-muted-foreground text-sm focus:outline-none focus:ring-2 focus:ring-primary/50"
        />
      </div>

      {/* Records — desktop table / mobile cards */}
      <ListStateBanner error={error} empty={!loading && records.length === 0} emptyLabel={t('student.records.noRecords')} />
      {!loading && !error && records.length === 0 && (
        <div className="text-center -mt-8">
          <button onClick={() => setView('new')} className="text-primary text-sm hover:underline">+ {t('student.records.submitFirst')}</button>
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
                        {rec.status === 'returned' && rec.returnReason && (
                          <div className="text-xs text-orange-600 dark:text-orange-400 flex items-center gap-1 mt-0.5">
                            <AlertTriangle className="w-3 h-3 shrink-0" />
                            <span className="truncate max-w-48">{rec.returnReason}</span>
                          </div>
                        )}
                      </td>
                      <td className="px-4 py-3 text-xs text-muted-foreground">{rec.serviceCategoryName.startsWith('mock.') ? t(rec.serviceCategoryName) : rec.serviceCategoryName}</td>
                      <td className="px-4 py-3 text-xs text-muted-foreground">{rec.serviceDate}</td>
                      <td className="px-4 py-3 text-xs text-muted-foreground">{rec.durationMinutes / 60}h</td>
                      <td className="px-4 py-3">
                        <span className={`text-xs px-2 py-0.5 rounded-full ${colorMap[statusInfo.color]}`}>{t(statusInfo.label)}</span>
                      </td>
                      <td className="px-4 py-3 text-xs text-muted-foreground">
                        {rec.creditedUnits ? <span className="text-yellow-600 dark:text-yellow-400">+{formatCoins(rec.creditedUnits)}</span> : '—'}
                      </td>
                      <td className="px-4 py-3">
                        <div className="flex gap-1 justify-end">
                          {rec.status === 'returned' && (
                            <button onClick={() => { setView('new'); toast.info(t('student.records.loadedReturned')); }}
                              className="text-xs px-2 py-1 bg-primary text-primary-foreground rounded-lg hover:opacity-90">
                              {t('student.records.resubmit')}
                            </button>
                          )}
                          {rec.status === 'draft' && (
                            <>
                              <button onClick={() => handleDeleteDraft(rec.id)}
                                className="text-xs px-2 py-1 border border-border text-muted-foreground rounded-lg hover:bg-accent">
                                {t('common.delete')}
                              </button>
                              <button onClick={() => handleSubmitDraft(rec.id)}
                                className="text-xs px-2 py-1 bg-primary text-primary-foreground rounded-lg hover:opacity-90">
                                {t('common.submit')}
                              </button>
                            </>
                          )}
                          {rec.status === 'rejected' && (
                            <button onClick={() => setAppealRecord(rec)}
                              className="text-xs px-2 py-1 border border-border text-foreground rounded-lg hover:bg-accent">
                              {t('student.records.appeal')}
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
                          <span className={`text-xs px-2 py-0.5 rounded-full ${colorMap[statusInfo.color]}`}>{t(statusInfo.label)}</span>
                          {rec.creditedUnits && <span className="text-xs text-yellow-600 dark:text-yellow-400">+{formatCoins(rec.creditedUnits)} {t('common.coins')}</span>}
                        </div>
                        <div className="text-sm text-foreground font-medium truncate">{rec.projectTitle || rec.organizationName}</div>
                        <div className="text-xs text-muted-foreground">{rec.serviceDate} · {rec.durationMinutes / 60} {t('common.hours')} · {rec.serviceCategoryName.startsWith('mock.') ? t(rec.serviceCategoryName) : rec.serviceCategoryName}</div>
                      </div>
                    </div>
                    {rec.status === 'returned' && rec.returnReason && (
                      <div className="bg-orange-50 dark:bg-orange-900/20 border border-orange-200 dark:border-orange-800 rounded-lg px-3 py-2 mb-3">
                        <div className="text-xs text-orange-700 dark:text-orange-300 flex items-start gap-1.5">
                          <AlertTriangle className="w-3.5 h-3.5 shrink-0 mt-0.5" />
                          {rec.returnReason}
                        </div>
                      </div>
                    )}
                    {isExpanded && (
                      <div className="border-t border-border pt-3 mb-3 space-y-2 text-xs text-muted-foreground">
                        <p><span className="font-medium text-foreground">{t('student.records.description')}:</span>{rec.description}</p>
                        <p><span className="font-medium text-foreground">{t('student.records.serviceLocation')}:</span>{rec.location}</p>
                        <p><span className="font-medium text-foreground">{t('student.records.orgName')}:</span>{rec.organizationName}</p>
                        <p><span className="font-medium text-foreground">{t('student.records.supervisorName')}:</span>{rec.supervisorName} {rec.supervisorContact}</p>
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
                        {rec.status === 'returned' && (
                          <button onClick={() => { setView('new'); toast.info(t('student.records.loadedReturned')); }}
                            className="text-xs px-3 py-1.5 bg-primary text-primary-foreground rounded-lg hover:opacity-90">{t('student.records.resubmit')}</button>
                        )}
                        {rec.status === 'draft' && (
                          <>
                            <button onClick={() => handleDeleteDraft(rec.id)}
                              className="text-xs px-3 py-1.5 border border-border text-muted-foreground rounded-lg hover:bg-accent flex items-center gap-1">
                              <Trash2 className="w-3 h-3" /> {t('common.delete')}
                            </button>
                            <button onClick={() => handleSubmitDraft(rec.id)}
                              className="text-xs px-3 py-1.5 bg-primary text-primary-foreground rounded-lg hover:opacity-90 flex items-center gap-1">
                              <Send className="w-3 h-3" /> {t('common.submit')}
                            </button>
                          </>
                        )}
                        {rec.status === 'rejected' && (
                          <button onClick={() => setAppealRecord(rec)}
                            className="text-xs px-3 py-1.5 border border-border text-foreground rounded-lg hover:bg-accent">{t('student.records.appeal')}</button>
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
      <PageControls page={page} onPageChange={setPage} />

      {appealRecord && (
        <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50 p-4" onClick={() => setAppealRecord(null)}>
          <div className="bg-card border border-border rounded-2xl p-5 w-full max-w-md" onClick={e => e.stopPropagation()}>
            <div className="flex items-center justify-between mb-3">
              <h3 className="text-foreground">{t('student.records.appeal')}</h3>
              <button onClick={() => setAppealRecord(null)} className="text-muted-foreground hover:text-foreground"><X className="w-4 h-4" /></button>
            </div>
            <p className="text-xs text-muted-foreground mb-3">{appealRecord.projectTitle || appealRecord.organizationName} · {appealRecord.serviceDate}</p>
            <textarea rows={4} value={appealReason} onChange={e => setAppealReason(e.target.value)}
              placeholder={t('student.records.appealReasonPlaceholder')}
              className="w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-primary/50 resize-none" />
            <div className="flex gap-3 mt-4">
              <button onClick={() => setAppealRecord(null)} disabled={appealSaving}
                className="flex-1 py-2.5 rounded-xl border border-border text-foreground hover:bg-accent text-sm">{t('common.cancel')}</button>
              <button onClick={handleSubmitAppeal} disabled={appealSaving}
                className="flex-1 py-2.5 rounded-xl bg-primary text-primary-foreground hover:opacity-90 text-sm disabled:opacity-50">
                {appealSaving ? t('common.saving', { defaultValue: 'Saving...' }) : t('common.submit')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
