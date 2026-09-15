import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Download, X, FileText } from 'lucide-react';
import { toast } from 'sonner';
import { api } from '../../api/client';
import { type FileMetadata } from '../../api/types';

/**
 * Large single-file viewer for an evidence file (issue 072) — opened by
 * clicking a tile in `EvidencePreview`, so both the Audit Center and the
 * student's own record detail get it from one place.
 *
 * Replaces what used to be `<a target="_blank">` on each tile: a reviewer
 * working through the queue no longer loses their place to a browser tab.
 *
 * **Every URL used here is minted on demand, never inherited.** The signed
 * content URL lives for 300s (`DownloadTokenService`, contract §4.6's
 * short-lived link), and the tile's copy is minted when the *panel* mounts —
 * so anything more than five minutes later failed with
 * `400 "Download link has expired."`, which Chrome reports as the thoroughly
 * misleading "site wasn't available" (issue 074). Hence: refresh on open, and
 * re-fetch again on the download click, since the dialog itself can sit open
 * past the boundary. The TTL is deliberately short and is not lengthened to
 * paper over this.
 *
 * PDFs are framed from a `blob:` URL, not the content URL directly: Spring
 * Security sends `X-Frame-Options: DENY` on every API response, which blocks
 * framing it even same-origin (issue 072).
 */
export function EvidenceViewerDialog({ fileId, meta, name, onClose }: {
  fileId: string;
  /** The tile's already-resolved metadata — used for mime type immediately; its URL is refreshed before use. */
  meta: FileMetadata;
  name: string;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const [url, setUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const [downloading, setDownloading] = useState(false);

  const isImage = meta.mimeType.startsWith('image/');
  const isPdf = meta.mimeType === 'application/pdf';

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  // Fresh URL on open, rather than trusting the one the tile minted.
  useEffect(() => {
    let cancelled = false;
    api.files.get(fileId).then(
      m => { if (!cancelled) (m.downloadUrl ? setUrl(m.downloadUrl) : setFailed(true)); },
      () => { if (!cancelled) setFailed(true); },
    );
    return () => { cancelled = true; };
  }, [fileId]);

  // PDFs need the bytes (see the X-Frame-Options note above), so this waits on
  // the refreshed URL rather than racing it.
  const [pdfUrl, setPdfUrl] = useState<string | null>(null);
  useEffect(() => {
    if (!isPdf || !url) return;
    let objectUrl: string | null = null;
    let cancelled = false;
    fetch(url)
      .then(r => (r.ok ? r.blob() : Promise.reject(new Error(String(r.status)))))
      .then(blob => {
        if (cancelled) return;
        objectUrl = URL.createObjectURL(blob);
        setPdfUrl(objectUrl);
      })
      .catch(() => { if (!cancelled) setFailed(true); });
    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [isPdf, url]);

  /**
   * Re-mint the URL on click. A download started from a user gesture is not
   * popup-blocked even after an `await` (unlike `window.open` — issue 060), so
   * the fetch-then-save gap is safe here.
   */
  const handleDownload = async () => {
    setDownloading(true);
    try {
      const fresh = await api.files.get(fileId);
      if (!fresh.downloadUrl) throw new Error('no url');
      const a = document.createElement('a');
      a.href = fresh.downloadUrl;
      a.download = name;
      document.body.appendChild(a);
      a.click();
      a.remove();
    } catch {
      toast.error(t('common.evidenceDownloadFailed'));
    } finally {
      setDownloading(false);
    }
  };

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-black/70 backdrop-blur-sm" onClick={onClose} />
      <div className="relative bg-card w-full max-w-4xl h-[85vh] rounded-2xl shadow-xl flex flex-col overflow-hidden">
        <div className="flex items-center gap-3 px-5 py-3 border-b border-border shrink-0">
          <h3 className="text-sm font-medium text-foreground truncate flex-1" title={name}>{name}</h3>
          <button type="button" onClick={handleDownload} disabled={downloading}
            className="text-xs px-3 py-1.5 rounded-lg border border-border text-foreground hover:bg-accent transition-colors flex items-center gap-1.5 shrink-0 disabled:opacity-50">
            <Download className="w-3.5 h-3.5" /> {t('common.download')}
          </button>
          <button type="button" onClick={onClose} title={t('common.close')}
            className="p-1.5 rounded-lg text-muted-foreground hover:text-foreground hover:bg-accent transition-colors shrink-0">
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className="flex-1 min-h-0 bg-muted/40 flex items-center justify-center overflow-auto">
          {failed && (
            <div className="flex flex-col items-center gap-3 text-muted-foreground p-8 text-center">
              <FileText className="w-10 h-10" />
              <p className="text-sm">{t('common.evidenceNoPreview')}</p>
            </div>
          )}
          {!failed && !url && <p className="text-sm text-muted-foreground">{t('common.loading')}</p>}
          {!failed && url && isImage && <img src={url} alt={name} className="max-w-full max-h-full object-contain" />}
          {!failed && url && isPdf && (
            pdfUrl
              ? <iframe src={pdfUrl} title={name} className="w-full h-full border-0" />
              : <p className="text-sm text-muted-foreground">{t('common.loading')}</p>
          )}
          {!failed && url && !isImage && !isPdf && (
            <div className="flex flex-col items-center gap-3 text-muted-foreground p-8 text-center">
              <FileText className="w-10 h-10" />
              <p className="text-sm">{t('common.evidenceNoPreview')}</p>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
