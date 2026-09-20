import { useState, useCallback } from 'react';
import { toast } from 'sonner';
import { ApiError, mapApiError } from './http';
import { apiErrorMessage, fieldErrorMessage } from './errorMessages';
import i18n from '../../i18n';

export function useFieldErrors() {
  // The code is kept alongside the message, because the code is what carries
  // the meaning: the message is the server's English sentence and is only the
  // fallback for a code we have no wording for yet (issue 121).
  const [errors, setErrors] = useState<Record<string, { code: string; message: string }>>({});

  const setFromApiError = useCallback((err: ApiError) => {
    const mapped = mapApiError(err);
    if (mapped.kind === 'field' && mapped.fieldErrors?.length) {
      const next: Record<string, { code: string; message: string }> = {};
      for (const fe of mapped.fieldErrors) {
        next[fe.field] = { code: fe.code, message: fe.message };
      }
      setErrors(next);
    }
  }, []);

  const clearField = useCallback((field: string) => {
    setErrors(prev => {
      if (!(field in prev)) return prev;
      const { [field]: _, ...rest } = prev;
      return rest;
    });
  }, []);

  const clearAll = useCallback(() => setErrors({}), []);

  /**
   * Look up an error by API field name or frontend alias(es), localized.
   *
   * Resolved at call time rather than when it was stored, so switching language
   * with a form open re-words the errors already on screen.
   */
  const getError = useCallback(
    (...fields: string[]) => {
      for (const f of fields) {
        const e = errors[f];
        if (e) return fieldErrorMessage(e.code, e.message);
      }
      return undefined;
    },
    [errors],
  );

  return { errors, setFromApiError, clearField, clearAll, getError };
}

export function FieldError({ message }: { message?: string }) {
  if (!message) return null;
  return <p className="text-xs text-destructive mt-1">{message}</p>;
}

/** Shared catch-block helper: field errors → form, conflict → refresh toast, always toasts. */
export function handleMutationError(
  err: unknown,
  opts: {
    fieldErrors?: ReturnType<typeof useFieldErrors>;
    refetch?: () => void;
    fallbackMessage?: string;
  },
) {
  if (err instanceof ApiError) {
    const mapped = mapApiError(err);
    if (mapped.kind === 'field') {
      opts.fieldErrors?.setFromApiError(err);
    }
    const message = apiErrorMessage(err);
    if (mapped.kind === 'conflict' && opts.refetch) {
      toast.error(message, {
        action: { label: i18n.t('common.refresh'), onClick: () => opts.refetch!() },
      });
    } else {
      toast.error(message);
    }
    return;
  }
  toast.error(opts.fallbackMessage ?? i18n.t('common.unknownError'));
}
