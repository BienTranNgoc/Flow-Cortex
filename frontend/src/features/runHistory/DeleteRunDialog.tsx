import { useId, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { useModalDialog } from './useModalDialog';

interface DeleteRunDialogProps {
  open: boolean;
  runName: string;
  deleting: boolean;
  /** Why the last attempt failed, shown inside the dialog; the dialog stays open. */
  error: string | null;
  onKeep: () => void;
  onDelete: () => void;
}

/** The confirmation before a finished run leaves the history; focus starts on "Keep run", Escape keeps it. */
export function DeleteRunDialog({
  open,
  runName,
  deleting,
  error,
  onKeep,
  onDelete,
}: Readonly<DeleteRunDialogProps>) {
  const { t } = useTranslation();
  const titleId = useId();
  const textId = useId();
  const keepRef = useRef<HTMLButtonElement>(null);
  const dialogRef = useModalDialog(open, keepRef);

  return (
    <dialog
      ref={dialogRef}
      className="run-dialog"
      aria-labelledby={titleId}
      aria-describedby={textId}
      onCancel={(event) => {
        event.preventDefault();
        if (!deleting) onKeep();
      }}
      onClose={() => {
        if (open && !deleting) onKeep();
      }}
    >
      <h2 id={titleId}>{t('runHistory.delete.title')}</h2>
      <p id={textId}>{t('runHistory.delete.text', { name: runName })}</p>
      {error && (
        <p className="run-dialog__error" role="alert">
          {error}
        </p>
      )}
      <div className="run-dialog__actions">
        <button
          ref={keepRef}
          type="button"
          aria-disabled={deleting}
          onClick={() => {
            if (!deleting) onKeep();
          }}
        >
          {t('runHistory.delete.keep')}
        </button>
        <button
          type="button"
          className="run-dialog__danger"
          aria-disabled={deleting}
          aria-busy={deleting}
          onClick={() => {
            if (!deleting) onDelete();
          }}
        >
          {deleting ? t('runHistory.delete.deleting') : t('runHistory.delete.confirm')}
        </button>
      </div>
    </dialog>
  );
}
