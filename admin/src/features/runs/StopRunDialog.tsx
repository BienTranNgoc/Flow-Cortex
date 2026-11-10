import { useEffect, useId, useRef, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';

interface StopRunDialogProps {
  readonly pending: boolean;
  /** The localized problem text of the last failed attempt, shown inside the dialog. */
  readonly error: string | undefined;
  readonly onConfirm: () => void;
  readonly onClose: () => void;
}

/** Modal that confirms stopping a run; it stays open on a failure so the reason is read in place. */
export function StopRunDialog({ pending, error, onConfirm, onClose }: StopRunDialogProps) {
  const { t } = useTranslation();
  const titleId = useId();
  const dialogRef = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (dialog && !dialog.open) dialog.showModal();
  }, []);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!pending) onConfirm();
  };

  return (
    <dialog
      ref={dialogRef}
      className="cortex-admin-account-dialog"
      aria-labelledby={titleId}
      onClose={onClose}
      onClick={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <form className="cortex-admin-stop-dialog__body" onSubmit={submit} noValidate>
        <h2 id={titleId}>{t('runs.stop.title')}</h2>
        <p>{t('runs.stop.description')}</p>
        {error && (
          <p className="cortex-admin-form-error" role="alert">
            {error}
          </p>
        )}
        <div className="cortex-admin-stop-dialog__actions">
          <button type="button" className="cortex-admin-btn" onClick={() => dialogRef.current?.close()}>
            {t('runs.stop.keep')}
          </button>
          <button type="submit" className="cortex-admin-btn cortex-admin-btn--danger" disabled={pending}>
            {pending ? t('runs.stop.working') : t('runs.stop.confirm')}
          </button>
        </div>
      </form>
    </dialog>
  );
}
