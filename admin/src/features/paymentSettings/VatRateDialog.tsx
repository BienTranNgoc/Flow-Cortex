import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { percentToBps, vatToday } from './vatRates';

export const SOURCE_NOTE_MAX = 1000;

interface VatRateDialogProps {
  readonly pending: boolean;
  /** The server's refusal of the percentage itself (400), shown on the field. */
  readonly fieldError?: string | undefined;
  /** The server's refusal of the change (409) or a failure, announced in the dialog. */
  readonly formError?: string | undefined;
  readonly onConfirm: (rateBps: number, sourceNote: string | undefined) => void;
  /** Esc or Cancel before the rate is sent: it is abandoned. Neither works while it is pending. */
  readonly onCancel: () => void;
}

/** Modal that sets the rate from today: a percentage and an optional note for the record. */
export function VatRateDialog({
  pending,
  fieldError,
  formError,
  onConfirm,
  onCancel,
}: Readonly<VatRateDialogProps>) {
  const { t } = useTranslation();
  const titleId = useId();
  const descriptionId = useId();
  const percentId = useId();
  const percentErrorId = useId();
  const noteId = useId();
  const counterId = useId();
  const dialogRef = useRef<HTMLDialogElement>(null);
  const percentRef = useRef<HTMLInputElement>(null);
  const [percent, setPercent] = useState('');
  const [note, setNote] = useState('');
  const [invalid, setInvalid] = useState(false);
  const shownError = invalid ? t('paymentSettings.vat.dialog.invalidPercent') : fieldError;

  useEffect(() => {
    const dialog = dialogRef.current;
    if (dialog && !dialog.open) dialog.showModal();
    percentRef.current?.focus();
  }, []);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (pending) return;
    const bps = percentToBps(percent);
    if (bps === null) {
      setInvalid(true);
      percentRef.current?.focus();
      return;
    }
    const trimmed = note.trim();
    onConfirm(bps, trimmed === '' ? undefined : trimmed);
  };

  return (
    <dialog
      ref={dialogRef}
      className="cortex-admin-account-dialog payment-dialog"
      aria-labelledby={titleId}
      aria-describedby={descriptionId}
      onClose={onCancel}
      onCancel={(event) => {
        // A sent rate cannot be taken back, so Esc must not look like it abandons one.
        if (pending) event.preventDefault();
      }}
    >
      <form className="payment-dialog__form" onSubmit={submit} noValidate>
        <div className="payment-dialog__body">
          <h2 id={titleId}>{t('paymentSettings.vat.dialog.title')}</h2>
          <p id={descriptionId}>
            {t('paymentSettings.vat.dialog.description', { date: vatToday() })}
          </p>
          <div className="cortex-admin-field payment-dialog__field">
            <label htmlFor={percentId}>{t('paymentSettings.vat.dialog.percentLabel')}</label>
            <input
              id={percentId}
              ref={percentRef}
              type="text"
              inputMode="decimal"
              autoComplete="off"
              value={percent}
              aria-invalid={shownError ? true : undefined}
              aria-describedby={shownError ? percentErrorId : undefined}
              onChange={(event) => {
                setPercent(event.target.value);
                setInvalid(false);
              }}
            />
            {shownError && (
              <p id={percentErrorId} role="alert" className="cortex-admin-form-error">
                {shownError}
              </p>
            )}
          </div>
          <div className="cortex-admin-field payment-dialog__field">
            <label htmlFor={noteId}>{t('paymentSettings.vat.dialog.noteLabel')}</label>
            <textarea
              id={noteId}
              rows={3}
              maxLength={SOURCE_NOTE_MAX}
              value={note}
              aria-describedby={counterId}
              onChange={(event) => setNote(event.target.value)}
            />
            <span id={counterId} className="payment-muted">
              {t('paymentSettings.dialog.counter', { count: note.length, max: SOURCE_NOTE_MAX })}
            </span>
          </div>
          {formError && (
            <p role="alert" className="cortex-admin-form-error">
              {formError}
            </p>
          )}
        </div>
        <div className="payment-dialog__actions">
          <button
            type="button"
            className="cortex-admin-btn"
            disabled={pending}
            onClick={() => dialogRef.current?.close()}
          >
            {t('paymentSettings.dialog.cancel')}
          </button>
          <button
            type="submit"
            className="cortex-admin-btn cortex-admin-btn--primary"
            disabled={pending}
          >
            {t(pending ? 'paymentSettings.dialog.saving' : 'paymentSettings.vat.dialog.save')}
          </button>
        </div>
      </form>
    </dialog>
  );
}
