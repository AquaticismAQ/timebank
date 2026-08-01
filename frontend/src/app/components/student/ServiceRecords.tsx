import { useState, useEffect, useMemo } from 'react';
import { Plus, Search, X, FileText, AlertTriangle, ChevronDown, ChevronUp, Upload, Send, Trash2 } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { RECORD_STATUS_LABELS, SERVICE_CATEGORIES, type ServiceRecord, type RecordStatus } from '../../mock/data';
import { toast } from 'sonner';
import { api } from '../../api/client';

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
  const { t } = useTranslation();
  const [records, setRecords] = useState<ServiceRecord[]>([]);
  const [view, setView] = useState<ViewMode>('list');
  const [search, setSearch] = useState('');
  const [filterStatus, setFilterStatus] = useState<RecordStatus | 'all'>('all');
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [step, setStep] = useState(0);

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

  useEffect(() => {
    api.serviceRecords.list().then(setRecords).catch(() => {});
  }, []);

  const filtered = useMemo(() => {
    return records.filter(r => {
      const matchSearch = !search || r.organizationName.includes(search) || r.description.includes(search) || (r.projectTitle && r.projectTitle.includes(search));
      const matchStatus = filterStatus === 'all' || r.status === filterStatus;
      return matchSearch && matchStatus;
    });
  }, [search, filterStatus]);

  const statusCounts = useMemo(() => {
    const counts: Record<string, number> = {};
    records.forEach(r => { counts[r.status] = (counts[r.status] || 0) + 1; });
    return counts;
  }, []);

  const handleSubmitRecord = (e: React.FormEvent) => {
    e.preventDefault();
    toast.success(t('student.records.submitSuccess'));
    setView('list');
    setStep(0);
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
                <input type="date" required value={formData.serviceDate} onChange={e => setFormData(f => ({ ...f, serviceDate: e.target.value }))}
                  className="w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground focus:outline-none focus:ring-2 focus:ring-primary/50 text-sm" />
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs text-muted-foreground mb-1.5">{t('student.records.startTime')} *</label>
                  <input type="time" required value={formData.startTime} onChange={e => setFormData(f => ({ ...f, startTime: e.target.value }))}
                    className="w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground focus:outline-none focus:ring-2 focus:ring-primary/50 text-sm" />
                </div>
                <div>
                  <label className="block text-xs text-muted-foreground mb-1.5">{t('student.records.endTime')} *</label>
                  <input type="time" required value={formData.endTime} onChange={e => setFormData(f => ({ ...f, endTime: e.target.value }))}
                    className="w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground focus:outline-none focus:ring-2 focus:ring-primary/50 text-sm" />
                </div>
              </div>
              <div>
                <label className="block text-xs text-muted-foreground mb-1.5">{t('student.records.serviceLocation')} *</label>
                <input type="text" required placeholder={t('student.records.locationPlaceholder')} value={formData.location} onChange={e => setFormData(f => ({ ...f, location: e.target.value }))}
                  className="w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50 text-sm" />
              </div>
              <div>
                <label className="block text-xs text-muted-foreground mb-1.5">{t('student.records.orgName')} *</label>
                <input type="text" required placeholder={t('student.records.locationPlaceholder')} value={formData.organizationName} onChange={e => setFormData(f => ({ ...f, organizationName: e.target.value }))}
                  className="w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50 text-sm" />
              </div>
              <div>
                <label className="block text-xs text-muted-foreground mb-1.5">{t('student.records.serviceCategory')}</label>
                <select value={formData.categoryId} onChange={e => setFormData(f => ({ ...f, categoryId: e.target.value }))}
                  className="w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground focus:outline-none focus:ring-2 focus:ring-primary/50 text-sm">
                  {SERVICE_CATEGORIES.map(cat => (
                    <option key={cat.id} value={cat.id}>{cat.name.startsWith('mock.') ? t(cat.name) : cat.name}</option>
                  ))}
                </select>
              </div>
              <div>
                <label className="block text-xs text-muted-foreground mb-1.5">{t('student.records.serviceDesc')} *</label>
                <textarea
                  required
                  rows={10}
                  placeholder={t('student.records.descPlaceholder')}
                  value={formData.description}
                  onChange={e => setFormData(f => ({ ...f, description: e.target.value }))}
                  className="w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50 text-sm font-mono resize-none overflow-y-auto whitespace-pre-wrap"
                />
              </div>
            </div>
          )}

          {/* Step 2: Evidence */}
          {step === 2 && (
            <div className="space-y-4">
              <div>
                <label className="block text-xs text-muted-foreground mb-1.5">{t('student.records.supervisorName')} *</label>
                <input type="text" required placeholder={t('student.records.supervisorPlaceholder')} value={formData.supervisorName} onChange={e => setFormData(f => ({ ...f, supervisorName: e.target.value }))}
                  className="w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50 text-sm" />
              </div>
              <div>
                <label className="block text-xs text-muted-foreground mb-1.5">{t('student.records.contactInfo')}</label>
                <input type="text" placeholder={t('student.records.contactPlaceholder')} value={formData.supervisorContact} onChange={e => setFormData(f => ({ ...f, supervisorContact: e.target.value }))}
                  className="w-full px-4 py-3 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50 text-sm" />
              </div>
              <div>
                <label className="block text-xs text-muted-foreground mb-2">{t('student.records.uploadEvidence')}</label>
                <div className="border-2 border-dashed border-border rounded-xl p-6 text-center hover:border-primary/50 transition-colors cursor-pointer">
                  <Upload className="w-8 h-8 text-muted-foreground mx-auto mb-2" />
                  <p className="text-sm text-foreground">{t('student.records.uploadClick')}</p>
                  <p className="text-xs text-muted-foreground mt-1">{t('student.records.uploadFormats')}</p>
                  <p className="text-xs text-muted-foreground">{t('student.records.uploadAccepted')}</p>
                </div>
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

          <button type="button" onClick={() => toast.success('Draft saved')}
            className="w-full py-2 text-sm text-muted-foreground hover:text-foreground transition-colors">
            {t('student.records.saveDraft')}
          </button>
        </form>
      </div>
    );
  }

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
            onClick={() => setFilterStatus(val as RecordStatus | 'all')}
            className={`px-3 py-1.5 rounded-full text-xs whitespace-nowrap transition-colors ${
              filterStatus === val
                ? 'bg-blue-100 text-blue-700 dark:bg-blue-500/20 dark:text-blue-300'
                : 'bg-muted text-muted-foreground hover:bg-accent'
            }`}
          >
            {label}
            {val !== 'all' && statusCounts[val] > 0 && (
              <span className="ml-1 opacity-60">({statusCounts[val]})</span>
            )}
          </button>
        ))}
      </div>

      {/* Search */}
      <div className="relative">
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground" />
        <input
          type="text"
          value={search}
          onChange={e => setSearch(e.target.value)}
          placeholder={t('student.records.searchPlaceholder')}
          className="w-full pl-9 pr-4 py-2.5 rounded-xl border border-border bg-input-background dark:bg-muted text-foreground placeholder:text-muted-foreground text-sm focus:outline-none focus:ring-2 focus:ring-primary/50"
        />
      </div>

      {/* Records — desktop table / mobile cards */}
      {filtered.length === 0 ? (
        <div className="text-center py-12 text-muted-foreground">
          <FileText className="w-10 h-10 mx-auto mb-2 opacity-30" />
          <p className="text-sm">{t('student.records.noRecords')}</p>
          <button onClick={() => setView('new')} className="mt-3 text-primary text-sm hover:underline">+ {t('student.records.submitFirst')}</button>
        </div>
      ) : (
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
                {filtered.map(rec => {
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
                        {rec.creditedUnits ? <span className="text-yellow-600 dark:text-yellow-400">+{rec.creditedUnits}</span> : '—'}
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
                              <button onClick={() => toast.success(t('student.records.draftDeleted'))}
                                className="text-xs px-2 py-1 border border-border text-muted-foreground rounded-lg hover:bg-accent">
                                {t('common.delete')}
                              </button>
                              <button onClick={() => toast.success(t('student.records.submittedForReview'))}
                                className="text-xs px-2 py-1 bg-primary text-primary-foreground rounded-lg hover:opacity-90">
                                {t('common.submit')}
                              </button>
                            </>
                          )}
                          {rec.status === 'rejected' && (
                            <button onClick={() => toast.info(t('student.records.appealComingSoon'))}
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
            {filtered.map(rec => {
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
                          {rec.creditedUnits && <span className="text-xs text-yellow-600 dark:text-yellow-400">+{rec.creditedUnits} {t('common.coins')}</span>}
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
                          <div>
                            <span className="font-medium text-foreground">{t('student.records.evidence')}:</span>
                            {rec.evidenceFiles.map(f => <span key={f.id} className="ml-1 text-primary underline cursor-pointer">{f.name}</span>)}
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
                            <button onClick={() => toast.success(t('student.records.draftDeleted'))}
                              className="text-xs px-3 py-1.5 border border-border text-muted-foreground rounded-lg hover:bg-accent flex items-center gap-1">
                              <Trash2 className="w-3 h-3" /> {t('common.delete')}
                            </button>
                            <button onClick={() => toast.success(t('student.records.submittedForReview'))}
                              className="text-xs px-3 py-1.5 bg-primary text-primary-foreground rounded-lg hover:opacity-90 flex items-center gap-1">
                              <Send className="w-3 h-3" /> {t('common.submit')}
                            </button>
                          </>
                        )}
                        {rec.status === 'rejected' && (
                          <button onClick={() => toast.info(t('student.records.appealComingSoon'))}
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
    </div>
  );
}
