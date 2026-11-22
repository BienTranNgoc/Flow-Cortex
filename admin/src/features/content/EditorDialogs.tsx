import { useTranslation } from 'react-i18next';
import { ContentDialog } from './ContentDialog';

interface ConfirmDialogProps {
  readonly title: string;
  readonly message: string;
  readonly confirmLabel: string;
  readonly pending?: boolean;
  /** Shown in the dialog so a refusal is read where the decision was made. */
  readonly error?: string | null | undefined;
  readonly onConfirm: () => void;
  readonly onClose: () => void;
}

export function ConfirmDialog({
  title,
  message,
  confirmLabel,
  pending = false,
  error,
  onConfirm,
  onClose,
}: ConfirmDialogProps) {
  const { t } = useTranslation();
  return (
    <ContentDialog title={title} onClose={onClose}>
      <p>{message}</p>
      {error && (
        <p className="cortex-admin-form-error" role="alert">
          {error}
        </p>
      )}
      <div className="content-dialog__actions">
        <button type="button" className="cortex-admin-btn" onClick={onClose}>
          {t('content.dialog.cancel')}
        </button>
        <button
          type="button"
          className="cortex-admin-btn cortex-admin-btn--primary"
          aria-disabled={pending}
          onClick={() => !pending && onConfirm()}
        >
          {pending ? t('content.dialog.working') : confirmLabel}
        </button>
      </div>
    </ContentDialog>
  );
}

interface ConflictDialogProps {
  readonly currentVersion: number | undefined;
  /** A refused publish has nothing of the editor's to resend, so only "Load latest" is offered. */
  readonly allowOverwrite: boolean;
  readonly pending: boolean;
  readonly error: string | null;
  readonly onLoadLatest: () => void;
  readonly onOverwrite: () => void;
  readonly onClose: () => void;
}

/** Another save landed first. The editor's content stays untouched until one of the two choices is made. */
export function ConflictDialog({
  currentVersion,
  allowOverwrite,
  pending,
  error,
  onLoadLatest,
  onOverwrite,
  onClose,
}: ConflictDialogProps) {
  const { t } = useTranslation();
  return (
    <ContentDialog title={t('content.conflict.title')} onClose={onClose}>
      <p>
        {currentVersion === undefined
          ? t('content.conflict.messageUnknown')
          : t('content.conflict.message', { version: currentVersion })}
      </p>
      {error && (
        <p className="cortex-admin-form-error" role="alert">
          {error}
        </p>
      )}
      <div className="content-dialog__actions">
        <button type="button" className="cortex-admin-btn" onClick={onClose}>
          {t('content.dialog.cancel')}
        </button>
        <button
          type="button"
          className="cortex-admin-btn"
          aria-disabled={pending}
          onClick={() => !pending && onLoadLatest()}
        >
          {t('content.conflict.loadLatest')}
        </button>
        {allowOverwrite && (
          <button
            type="button"
            className="cortex-admin-btn cortex-admin-btn--primary"
            aria-disabled={pending}
            onClick={() => !pending && onOverwrite()}
          >
            {t('content.conflict.overwrite')}
          </button>
        )}
      </div>
    </ContentDialog>
  );
}
