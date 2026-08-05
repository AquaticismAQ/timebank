import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { FileText } from 'lucide-react';
import { toast } from 'sonner';
import { api } from '../api/client';
import { ApiError, mapApiError } from '../api/http';

/**
 * I3.3: evidence URLs are short-lived (5 minutes, HMAC-signed —
 * `DownloadTokenService`) and must never be cached in application state —
 * so this fetches `GET /files/{id}` fresh on every click rather than
 * storing a `downloadUrl` anywhere, and re-fetches transparently if a user
 * clicks again after the previous token expired. Handles the
 * `processing`/`rejected` cases (no `downloadUrl` exists yet) rather than
 * opening a broken link.
 */
export function EvidenceLink({ fileId, name }: { fileId: string; name: string }) {
  const { t } = useTranslation();
  const [loading, setLoading] = useState(false);

  const handleClick = async () => {
    setLoading(true);
    try {
      const meta = await api.files.get(fileId);
      if (meta.status === 'ready' && meta.downloadUrl) {
        window.open(meta.downloadUrl, '_blank', 'noopener,noreferrer');
      } else if (meta.status === 'rejected') {
        toast.error(t('common.evidenceRejectedReason', { reason: meta.rejectionReason || '' }));
      } else {
        toast.info(t('common.evidenceProcessing'));
      }
    } catch (err) {
      if (err instanceof ApiError) toast.error(mapApiError(err).message);
      else toast.error(t('common.unknownError'));
    } finally {
      setLoading(false);
    }
  };

  return (
    <button type="button" onClick={handleClick} disabled={loading}
      className="inline-flex items-center gap-1 text-primary underline hover:opacity-80 disabled:opacity-50">
      <FileText className="w-3 h-3" /> {name}
    </button>
  );
}
