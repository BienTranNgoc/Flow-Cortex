import { useEffect, useId, useRef } from 'react';
import { useTranslation } from 'react-i18next';

interface DeleteDialogProps {
  /** The conversation awaiting confirmation, or `null` while the dialog is closed. */
  title: string | null;
  deleting: boolean;
  failed: boolean;
  onKeep: () => void;
  onDelete: () => void;
}

/** Native modal: focus starts on "Keep", Escape keeps, and focus returns to what opened it. */
export function DeleteDialog({ title, deleting, failed, onKeep, onDelete }: Readonly<DeleteDialogProps>) {
  const { t } = useTranslation();
  const titleId = useId();
  const textId = useId();
  const dialogRef = useRef<HTMLDialogElement>(null);
  const keepRef = useRef<HTMLButtonElement>(null);
  const openerRef = useRef<HTMLElement | null>(null);
  const open = title !== null;

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (open) {
      if (!dialog.open) {
        openerRef.current =
          document.activeElement instanceof HTMLElement ? document.activeElement : null;
        dialog.showModal();
        keepRef.current?.focus();
      }
      return;
    }
    if (dialog.open) dialog.close();
    const opener = openerRef.current;
    openerRef.current = null;
    if (opener?.isConnected) opener.focus();
  }, [open]);

  return (
    <dialog
      ref={dialogRef}
      className="chat-dialog"
      aria-labelledby={titleId}
      aria-describedby={textId}
      onCancel={(event) => {
        event.preventDefault();
        if (!deleting) onKeep();
      }}
    >
      <h2 id={titleId}>{t('chat.delete.title')}</h2>
      <p id={textId}>{t('chat.delete.text', { title: title ?? '' })}</p>
      {failed && (
        <p role="alert" className="form-error">
          {t('chat.delete.failed')}
        </p>
      )}
      <div className="chat-dialog__actions">
        <button
          ref={keepRef}
          type="button"
          className="chat-button"
          aria-disabled={deleting || undefined}
          onClick={() => {
            if (!deleting) onKeep();
          }}
        >
          {t('chat.delete.keep')}
        </button>
        <button
          type="button"
          className="chat-button chat-button--danger"
          aria-disabled={deleting || undefined}
          aria-busy={deleting || undefined}
          onClick={() => {
            if (!deleting) onDelete();
          }}
        >
          {deleting ? t('chat.delete.deleting') : t('chat.delete.confirm')}
        </button>
      </div>
    </dialog>
  );
}
