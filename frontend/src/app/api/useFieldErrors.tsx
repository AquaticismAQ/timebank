import { useState, useCallback } from 'react';
import { toast } from 'sonner';
import { ApiError, mapApiError } from './http';

export function useFieldErrors() {
  const [errors, setErrors] = useState<Record<string, string>>({});

  const setFromApiError = useCallback((err: ApiError) => {
    const mapped = mapApiError(err);
    if (mapped.kind === 'field' && mapped.fieldErrors?.length) {
      const next: Record<string, string> = {};
      for (const fe of mapped.fieldErrors) {
        next[fe.field] = fe.message;
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

  /** Look up an error by API field name or frontend alias(es). */
  const getError = useCallback(
    (...fields: string[]) => {
      for (const f of fields) {
        if (errors[f]) return errors[f];
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
    if (mapped.kind === 'conflict' && opts.refetch) {
      toast.error(mapped.message, {
        action: { label: 'Refresh', onClick: () => opts.refetch!() },
      });
    } else {
      toast.error(mapped.message);
    }
    return;
  }
  toast.error(opts.fallbackMessage ?? 'Request failed.');
}
