import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';

export const REASON_MAX = 2000;

interface ReasonDialogProps {
  readonly description: string;
  readonly pending: boolean;
  readonly onConfirm: (reason: string) => void;
  /** Esc or Cancel: the change is abandoned. */
  readonly onCancel: () => void;
}

/** Modal that collects the reason a dangerous setting needs; Save stays disabled while it is blank. */
export function ReasonDialog({
  description,
  pending,
  onConfirm,
  onCancel,
}: Readonly<ReasonDialogProps>) {
  const { t } = useTranslation();
  const titleId = useId();
  const descriptionId = useId();
  const reasonId = useId();
  const counterId = useId();
  const dialogRef = useRef<HTMLDialogElement>(null);
  const reasonRef = useRef<HTMLTextAreaElement>(null);
  const [reason, setReason] = useState('');
  const blank = reason.trim() === '';

  useEffect(() => {
    const dialog = dialogRef.current;
    if (dialog && !dialog.open) dialog.showModal();
    reasonRef.current?.focus();
  }, []);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!blank && !pending) onConfirm(reason.trim());
  };

  return (
    <dialog
      ref={dialogRef}
      className="cortex-admin-account-dialog payment-dialog"
      aria-labelledby={titleId}
      aria-describedby={descriptionId}
      onClose={onCancel}
    >
      <form className="payment-dialog__form" onSubmit={submit} noValidate>
        <div className="payment-dialog__body">
          <h2 id={titleId}>{t('paymentSettings.dialog.title')}</h2>
          <p id={descriptionId}>{description}</p>
          <div className="cortex-admin-field payment-dialog__field">
            <label htmlFor={reasonId}>{t('paymentSettings.dialog.reasonLabel')}</label>
            <textarea
              id={reasonId}
              ref={reasonRef}
              rows={4}
              maxLength={REASON_MAX}
              value={reason}
              aria-describedby={counterId}
              onChange={(event) => setReason(event.target.value)}
            />
            <span id={counterId} className="payment-muted">
              {t('paymentSettings.dialog.counter', { count: reason.length, max: REASON_MAX })}
            </span>
          </div>
        </div>
        <div className="payment-dialog__actions">
          <button
            type="button"
            className="cortex-admin-btn"
            onClick={() => dialogRef.current?.close()}
          >
            {t('paymentSettings.dialog.cancel')}
          </button>
          <button
            type="submit"
            className="cortex-admin-btn cortex-admin-btn--primary"
            disabled={blank || pending}
          >
            {t(pending ? 'paymentSettings.dialog.saving' : 'paymentSettings.dialog.save')}
          </button>
        </div>
      </form>
    </dialog>
  );
}
