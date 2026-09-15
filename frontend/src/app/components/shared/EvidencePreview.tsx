import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { FileText, AlertTriangle } from 'lucide-react';
import { api } from '../../api/client';
import { type FileMetadata } from '../../api/types';

/**
 * Grid of evidence-file tiles with real inline preview for images (thumbnail,
 * clicking opens full-size) and browser-native inline preview for PDFs
 * (`Content-Disposition: inline` server-side already supports this — clicking
 * just opens the tab). Everything else falls back to a plain file-icon tile.
 *
 * Unlike `EvidenceLink` (fetch-on-click), this fetches every file's fresh
 * signed URL up front so a thumbnail has something to render — still never
 * stored beyond this component's own state (I3.3: never cache a download
 * URL). Each tile's `<a href>` is populated by the time of render, so a click
 * is a real, synchronous navigation — no async gap between the click and
 * opening the tab for a browser's popup blocker to catch (unlike a `window.open`
 * called after an `await`, which is what made `EvidenceLink`'s link
 * intermittently just not open at all).
 *
 * Tiles are fixed-size and wrap in a flex row rather than growing with
 * content, so a record with many evidence files stays a tidy grid instead of
 * stretching the detail view — this scales the same whether there's 1 file or 12.
 */
export function EvidencePreview({ files }: { files: { id: string; name: string }[] }) {
  return (
    <div className="flex flex-wrap gap-3">
      {files.map(f => <EvidenceTile key={f.id} fileId={f.id} name={f.name} />)}
    </div>
  );
}

function EvidenceTile({ fileId, name }: { fileId: string; name: string }) {
  const { t } = useTranslation();
  const [meta, setMeta] = useState<FileMetadata | null>(null);
  const [error, setError] = useState(false);

  useEffect(() => {
    let cancelled = false;
    api.files.get(fileId).then(
      m => { if (!cancelled) setMeta(m); },
      () => { if (!cancelled) setError(true); },
    );
    return () => { cancelled = true; };
  }, [fileId]);

  const tileClass = 'w-24 shrink-0 flex flex-col gap-1';
  const boxClass = 'w-24 h-24 rounded-lg border border-border bg-muted flex items-center justify-center overflow-hidden';
  const caption = <p className="text-xs text-muted-foreground truncate" title={name}>{name}</p>;

  if (error) {
    return (
      <div className={tileClass}>
        <div className={`${boxClass} text-destructive`}><AlertTriangle className="w-5 h-5" /></div>
        <p className="text-xs text-destructive truncate" title={t('common.evidenceLoadFailed')}>{t('common.evidenceLoadFailed')}</p>
      </div>
    );
  }

  if (!meta) {
    return (
      <div className={tileClass}>
        <div className={`${boxClass} animate-pulse`} />
        {caption}
      </div>
    );
  }

  if (meta.status === 'rejected') {
    return (
      <div className={tileClass}>
        <div className={`${boxClass} text-destructive`} title={t('common.evidenceRejectedReason', { reason: meta.rejectionReason || '' })}>
          <AlertTriangle className="w-5 h-5" />
        </div>
        {caption}
      </div>
    );
  }

  if (meta.status !== 'ready' || !meta.downloadUrl) {
    return (
      <div className={tileClass}>
        <div className={boxClass} title={t('common.evidenceProcessing')}><FileText className="w-5 h-5 text-muted-foreground" /></div>
        {caption}
      </div>
    );
  }

  const isImage = meta.mimeType.startsWith('image/');
  return (
    <a href={meta.downloadUrl} target="_blank" rel="noreferrer" className={tileClass}>
      <div className={`${boxClass} hover:opacity-80 transition-opacity`}>
        {isImage ? (
          <img src={meta.downloadUrl} alt={name} className="w-full h-full object-cover" />
        ) : (
          <FileText className="w-6 h-6 text-muted-foreground" />
        )}
      </div>
      {caption}
    </a>
  );
}
