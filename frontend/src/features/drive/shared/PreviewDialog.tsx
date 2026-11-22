import { X } from '@phosphor-icons/react';
import { useId, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import type { ContentType } from '../model/driveRoles';
import { SignedMedia } from './SignedMedia';
import { useModalDialog } from './useModalDialog';
import './DriveShared.css';

interface PreviewDialogProps {
  open: boolean;
  title: string;
  kind: ContentType;
  /** The text of a text item, shown as plain text, never as markup. */
  text?: string | undefined;
  url?: string | undefined;
  urlExpiresAt?: string | undefined;
  onRenew: () => void;
  onClose: () => void;
}

/** A native modal that shows one item in full: an image, a player or its plain text. */
export function PreviewDialog({
  open,
  title,
  kind,
  text,
  url,
  urlExpiresAt,
  onRenew,
  onClose,
}: Readonly<PreviewDialogProps>) {
  const { t } = useTranslation();
  const titleId = useId();
  const closeRef = useRef<HTMLButtonElement>(null);
  const dialogRef = useModalDialog(open, closeRef);

  let body;
  if (kind === 'text') {
    body = text ? (
      <p className="drive-preview__text">{text}</p>
    ) : (
      <p className="drive-preview__empty">{t('drive.preview.empty')}</p>
    );
  } else {
    body = (
      <SignedMedia
        kind={kind}
        url={url}
        expiresAt={urlExpiresAt}
        label={title}
        onRenew={onRenew}
        withStatus
        className="drive-preview__media"
      />
    );
  }

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
      {open && body}
    </dialog>
  );
}
