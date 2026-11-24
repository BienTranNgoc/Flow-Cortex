import { X } from '@phosphor-icons/react';
import { useId, useRef, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { useModalDialog } from '../../../lib/useModalDialog';
import './DriveShared.css';

interface ViewDialogProps {
  open: boolean;
  title: string | undefined;
  onClose: () => void;
  /** Mounted only while open. */
  children: ReactNode;
}

/** A native modal for reading one thing: a title, a close button that takes focus, then the body. */
export function ViewDialog({ open, title, onClose, children }: Readonly<ViewDialogProps>) {
  const { t } = useTranslation();
  const titleId = useId();
  const closeRef = useRef<HTMLButtonElement>(null);
  const dialogRef = useModalDialog(open, closeRef);

  return (
    <dialog
      ref={dialogRef}
      className="drive-dialog drive-dialog--preview"
      aria-labelledby={titleId}
      onCancel={(event) => {
        event.preventDefault();
        onClose();
      }}
      onClose={() => {
        if (open) onClose();
      }}
    >
      <div className="drive-dialog__head">
        <h2 id={titleId} className="drive-dialog__title">
          {title}
        </h2>
        <button
          ref={closeRef}
          type="button"
          className="drive-button"
          aria-label={t('drive.preview.close')}
          onClick={onClose}
        >
          <X aria-hidden="true" />
        </button>
      </div>
      {open && children}
    </dialog>
  );
}
