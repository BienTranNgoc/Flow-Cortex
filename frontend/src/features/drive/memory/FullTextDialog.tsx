import { ApiError } from '@flow-cortex/api-client';
import type { MemoryEntryResponse } from '@flow-cortex/api-client';
import { X } from '@phosphor-icons/react';
import { useId, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { DriveLoading } from '../shared/DriveStates';
import { useModalDialog } from '../shared/useModalDialog';
import { useMemoryText } from './useDriveMemory';
import '../shared/DriveShared.css';
import './DriveMemory.css';

interface FullTextDialogProps {
  /** The text result to open; `null` keeps the dialog closed. */
  entry: MemoryEntryResponse | null;
  onClose: () => void;
}

/**
 * A text result in full, as plain text. The preview stays on screen while the full text loads and
 * when it cannot: a result whose text is gone says so, any other failure offers Retry.
 */
export function FullTextDialog({ entry, onClose }: Readonly<FullTextDialogProps>) {
  const { t } = useTranslation();
  const titleId = useId();
  const closeRef = useRef<HTMLButtonElement>(null);
  const open = entry !== null;
  const dialogRef = useModalDialog(open, closeRef);
  const full = useMemoryText(entry?.id);

  const gone = full.error instanceof ApiError && full.error.status === 404;
  const text = full.data?.text ?? entry?.previewText;

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
          {entry?.title}
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
      {open && (
        <>
          {text ? <p className="drive-preview__text">{text}</p> : null}
          {full.isPending && <DriveLoading label={t('driveMemory.fullText.loading')} />}
          {gone && <p className="drive-memory__note">{t('driveMemory.fullText.gone')}</p>}
          {full.isError && !gone && (
            <div className="drive-memory__fullText-failed">
              <p role="alert">{t('driveMemory.fullText.failed')}</p>
              <button
                type="button"
                className="drive-button"
                aria-disabled={full.isFetching || undefined}
                onClick={() => {
                  if (!full.isFetching) void full.refetch();
                }}
              >
                {t('drive.status.retry')}
              </button>
            </div>
          )}
        </>
      )}
    </dialog>
  );
}
