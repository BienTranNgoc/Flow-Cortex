import { useEffect, useId, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import type { Lowering } from './settingConfirmation';

interface LowerValueDialogProps {
  readonly lowering: Lowering;
  readonly pending: boolean;
  readonly onConfirm: () => void;
  /** Escape or Cancel: nothing was sent. */
  readonly onCancel: () => void;
}

/** Modal that names a lowered limit before it is sent; only the confirm button lets the change go out. */
export function LowerValueDialog({
  lowering,
  pending,
  onConfirm,
  onCancel,
}: Readonly<LowerValueDialogProps>) {
  const { t } = useTranslation();
  const titleId = useId();
  const messageId = useId();
  const dialogRef = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (dialog && !dialog.open) dialog.showModal();
  }, []);

  return (
    <dialog
      ref={dialogRef}
      className="cortex-admin-account-dialog settings-dialog"
      aria-labelledby={titleId}
      aria-describedby={messageId}
      onClose={onCancel}
    >
      <div className="settings-dialog__body">
        <h2 id={titleId}>{t('settings.lowerValue.title')}</h2>
        <p id={messageId}>
          {t('settings.lowerValue.message', { from: lowering.from, to: lowering.to })}
        </p>
      </div>
      <div className="settings-dialog__actions">
        <button
          type="button"
          className="cortex-admin-btn"
          onClick={() => dialogRef.current?.close()}
        >
          {t('settings.lowerValue.cancel')}
        </button>
        <button
          type="button"
          className="cortex-admin-btn cortex-admin-btn--primary"
          disabled={pending}
          onClick={onConfirm}
        >
          {t('settings.lowerValue.confirm')}
        </button>
      </div>
    </dialog>
  );
}
