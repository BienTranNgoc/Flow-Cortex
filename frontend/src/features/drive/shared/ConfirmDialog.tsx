import { useId, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { useModalDialog } from './useModalDialog';
import './DriveShared.css';

interface ConfirmDialogProps {
  open: boolean;
  title: string;
  message: string;
  confirmLabel: string;
  /** Shown on the confirm button while `busy`. */
  busyLabel: string;
  /** A refusal or failure of the confirmed action, announced inside the dialog. */
  error?: string | null;
  /** The confirmed action is running: both choices hold until it answers. */
  busy: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}

/**
 * A confirmation as a native modal dialog: focus starts on the safe choice, Escape means cancel,
 * and focus goes back to whatever opened it.
 */
export function ConfirmDialog({
  open,
  title,
  message,
  confirmLabel,
  busyLabel,
  error,
  busy,
  onCancel,
  onConfirm,
}: Readonly<ConfirmDialogProps>) {
  const { t } = useTranslation();
  const titleId = useId();
  const textId = useId();
  const cancelRef = useRef<HTMLButtonElement>(null);
  const dialogRef = useModalDialog(open, cancelRef);

  return (
    <dialog
      ref={dialogRef}
      className="drive-dialog drive-dialog--confirm"
      aria-labelledby={titleId}
      aria-describedby={textId}
      onCancel={(event) => {
        event.preventDefault();
        if (!busy) onCancel();
      }}
      // A browser may close the dialog on a repeated Escape despite `preventDefault`.
      onClose={() => {
        if (!open) return;
        if (busy) dialogRef.current?.showModal();
        else onCancel();
      }}
    >
      <h2 id={titleId} className="drive-dialog__title">
        {title}
      </h2>
      <p id={textId} className="drive-dialog__text">
        {message}
      </p>
      {error && (
        <p role="alert" className="drive-dialog__error">
          {error}
        </p>
      )}
      <div className="drive-dialog__actions">
        <button
          ref={cancelRef}
          type="button"
          className="drive-button"
          aria-disabled={busy || undefined}
          onClick={() => {
            if (!busy) onCancel();
          }}
        >
          {t('drive.confirm.cancel')}
        </button>
        <button
          type="button"
          className="drive-button drive-button--danger"
          aria-disabled={busy || undefined}
          aria-busy={busy || undefined}
          onClick={() => {
            if (!busy) onConfirm();
          }}
        >
          {busy ? busyLabel : confirmLabel}
        </button>
      </div>
    </dialog>
  );
}
