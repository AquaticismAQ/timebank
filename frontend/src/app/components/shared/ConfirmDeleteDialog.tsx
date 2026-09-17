import { Trash2 } from 'lucide-react';
import { useTranslation } from 'react-i18next';

/** A standalone delete-confirmation dialog — deliberately outside any create/edit form, since delete is a list-level action. */
export function ConfirmDeleteDialog({ title, message, onConfirm, onCancel }: {
  title: string;
  message: string;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const { t } = useTranslation();
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" />
      <div className="relative bg-card w-full max-w-sm rounded-2xl shadow-xl p-5 space-y-4">
        <div className="flex items-center gap-2">
          <Trash2 className="w-4 h-4 shrink-0 text-destructive" />
          <h3 className="text-sm font-medium text-foreground">{title}</h3>
        </div>
        <p className="text-xs text-muted-foreground">{message}</p>
        <div className="flex gap-3">
          <button type="button" onClick={onCancel}
            className="flex-1 py-2.5 rounded-xl border border-border text-sm text-foreground hover:bg-accent">
            {t('common.cancel')}
          </button>
          <button type="button" onClick={onConfirm}
            className="flex-1 py-2.5 rounded-xl bg-destructive text-destructive-foreground text-sm hover:opacity-90">
            {t('common.confirmDelete')}
          </button>
        </div>
      </div>
    </div>
  );
}
