import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { FileText, AlertTriangle } from 'lucide-react';
import { api } from '../../api/client';
import { type FileMetadata } from '../../api/types';
import { EvidenceViewerDialog } from './EvidenceViewerDialog';

/**
 * Grid of evidence-file tiles with real inline preview for images (thumbnail)
 * and a file-icon tile for everything else. Clicking a ready tile opens
 * `EvidenceViewerDialog` — a large in-app viewer with download/close (issue
 * 072). Tiles used to be `<a target="_blank">`, which sent a reviewer out to a
 * browser tab and lost their place in the queue.
 *
 * Unlike `EvidenceLink` (fetch-on-click), this fetches every file's fresh
 * signed URL up front so a thumbnail has something to render — still never
 * stored beyond this component's own state (I3.3: never cache a download
 * URL). Because the metadata is already resolved by the time a tile is
 * clickable, opening the viewer costs no extra request and there is no async
 * gap between the click and the content appearing.
 *
 * Tiles are fixed-size and wrap in a flex row rather than growing with
 * content, so a record with many evidence files stays a tidy grid instead of
 * stretching the detail view — this scales the same whether there's 1 file or 12.
 */
export function EvidencePreview({ files }: { files: { id: string; name: string }[] }) {
  return (
    <div className="flex flex-wrap items-start gap-3">
      {files.map(f => <EvidenceTile key={f.id} fileId={f.id} name={f.name} />)}
    </div>
  );
}

function EvidenceTile({ fileId, name }: { fileId: string; name: string }) {
  const { t } = useTranslation();
  const [meta, setMeta] = useState<FileMetadata | null>(null);
  const [error, setError] = useState(false);
  const [viewing, setViewing] = useState(false);
  // Bumped to re-mint a thumbnail's expired URL exactly once (issue 074): the
  // signed link lives 300s, so a queue left open past that boundary would
  // otherwise fill with broken images.
  const [refreshes, setRefreshes] = useState(0);

  useEffect(() => {
    let cancelled = false;
    api.files.get(fileId).then(
      m => { if (!cancelled) setMeta(m); },
      () => { if (!cancelled) setError(true); },
    );
    return () => { cancelled = true; };
  }, [fileId, refreshes]);

  const tileClass = 'w-24 shrink-0 flex flex-col gap-1';
  const boxClass = 'w-24 h-24 rounded-lg border border-border bg-muted flex items-center justify-center overflow-hidden';
  // Full name, wrapped — not truncated. `break-words` breaks at the natural
  // points in a filename (hyphens, dots) and only splits mid-word when a run
  // genuinely can't fit, so the caption can never exceed the tile's own w-24.
  const caption = <p className="text-xs text-muted-foreground break-words" title={name}>{name}</p>;

  if (error) {
    return (
      <div className={tileClass}>
        <div className={`${boxClass} text-destructive`}><AlertTriangle className="w-5 h-5" /></div>
        <p className="text-xs text-destructive break-words" title={t('common.evidenceLoadFailed')}>{t('common.evidenceLoadFailed')}</p>
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
    <>
      <button type="button" onClick={() => setViewing(true)} className={`${tileClass} text-left`} title={name}>
        <div className={`${boxClass} hover:opacity-80 transition-opacity cursor-zoom-in`}>
          {isImage ? (
            // The cache-buster is load-bearing, not decoration: `DownloadTokenService`
            // signs `fileId:(now + 300)`, so two mints within the same second produce a
            // byte-identical URL. Without it the retry re-fetched metadata, got the same
            // `src` back, and the browser never re-requested — the thumbnail stayed
            // broken (proven by forcing a 400 on the first content request: 1 request,
            // naturalWidth 0). The endpoint reads only `token`, so the extra param is
            // ignored server-side.
            <img src={refreshes > 0 ? `${meta.downloadUrl}&_retry=${refreshes}` : meta.downloadUrl}
              alt={name} className="w-full h-full object-cover"
              onError={() => { if (refreshes === 0) setRefreshes(1); }} />
          ) : (
            <FileText className="w-6 h-6 text-muted-foreground" />
          )}
        </div>
        {caption}
      </button>
      {viewing && <EvidenceViewerDialog fileId={fileId} meta={meta} name={name} onClose={() => setViewing(false)} />}
    </>
  );
}
