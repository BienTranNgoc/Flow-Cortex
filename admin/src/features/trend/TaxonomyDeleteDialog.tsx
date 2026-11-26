import { useEffect, useId, useRef } from 'react';
import { useTranslation } from 'react-i18next';

interface TaxonomyDeleteDialogProps {
  readonly title: string;
  readonly body: string;
  readonly pending: boolean;
  readonly onConfirm: () => void;
  readonly onClose: () => void;
}

/** Modal that asks before a record is deleted; nothing is sent until Delete is pressed. */
export function TaxonomyDeleteDialog({
  title,
  body,
  pending,
  onConfirm,
  onClose,
}: TaxonomyDeleteDialogProps) {
  const { t } = useTranslation();
  const titleId = useId();
  const bodyId = useId();
  const dialogRef = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (dialog && !dialog.open) dialog.showModal();
  }, []);

  return (
    <dialog
      ref={dialogRef}
      className="trend-dialog"
      aria-labelledby={titleId}
      aria-describedby={bodyId}
      onClose={onClose}
    >
      <div className="trend-dialog__body">
        <h2 id={titleId}>{title}</h2>
        <p id={bodyId}>{body}</p>
        <div className="trend-dialog__actions">
          <button type="button" className="cortex-admin-btn" onClick={() => dialogRef.current?.close()}>
            {t('trend.delete.cancel')}
          </button>
          <button
            type="button"
            className="cortex-admin-btn cortex-admin-btn--danger"
            disabled={pending}
            onClick={onConfirm}
          >
            {pending ? t('trend.delete.working') : t('trend.delete.confirm')}
          </button>
        </div>
      </div>
    </dialog>
  );
}
