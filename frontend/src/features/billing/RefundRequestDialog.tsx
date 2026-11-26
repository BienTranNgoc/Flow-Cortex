import { useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useModalDialog } from '../../lib/useModalDialog';
import { REASON_MAX, REASON_MIN } from './useRefund';

interface RefundRequestDialogProps {
  open: boolean;
  sending: boolean;
  /** The last attempt failed for a reason other than a refusal; the dialog stays open and keeps the text. */
  failed: boolean;
  /** The server's message about the reason field, shown under it. */
  fieldError: string | undefined;
  onClose: () => void;
  onSend: (reason: string) => void;
}

/**
 * Asks why the member wants the refund. The reason field starts focused, Send stays disabled until the
 * trimmed text holds {@link REASON_MIN} characters, Escape closes it unless a request is in flight.
 */
export function RefundRequestDialog({
  open,
  sending,
  failed,
  fieldError,
  onClose,
  onSend,
}: Readonly<RefundRequestDialogProps>) {
  const { t } = useTranslation();
  const titleId = useId();
  const descriptionId = useId();
  const reasonId = useId();
  const hintId = useId();
  const reasonRef = useRef<HTMLTextAreaElement>(null);
  const dialogRef = useModalDialog(open, reasonRef);
  const [reason, setReason] = useState('');
  const tooShort = reason.trim().length < REASON_MIN;

  return (
    <dialog
      ref={dialogRef}
      className="refund-dialog"
      aria-labelledby={titleId}
      aria-describedby={descriptionId}
      onCancel={(event) => {
        event.preventDefault();
        if (!sending) onClose();
      }}
      onClose={() => {
        if (open && !sending) onClose();
      }}
    >
      <form
        onSubmit={(event) => {
          event.preventDefault();
          if (!sending && !tooShort) onSend(reason.trim());
        }}
      >
        <h2 id={titleId} className="refund-dialog__title">
          {t('billing.refund.dialog.title')}
        </h2>
        <p id={descriptionId} className="refund-dialog__text">
          {t('billing.refund.dialog.description')}
        </p>
        <label htmlFor={reasonId} className="refund-dialog__label">
          {t('billing.refund.dialog.reasonLabel')}
        </label>
        <textarea
          ref={reasonRef}
          id={reasonId}
          className="refund-dialog__input"
          rows={5}
          maxLength={REASON_MAX}
          value={reason}
          aria-describedby={hintId}
          aria-invalid={fieldError ? true : undefined}
          onChange={(event) => setReason(event.target.value)}
        />
        <p id={hintId} className="refund-dialog__hint">
          {t('billing.refund.dialog.reasonHint', { min: REASON_MIN })}
        </p>
        {fieldError && (
          <p className="refund-dialog__error" role="alert">
            {fieldError}
          </p>
        )}
        {failed && (
          <p className="refund-dialog__error" role="alert">
            {t('billing.refund.dialog.failure')}
          </p>
        )}
        <div className="refund-dialog__actions">
          <button
            type="button"
            aria-disabled={sending}
            onClick={() => {
              if (!sending) onClose();
            }}
          >
            {t('billing.refund.dialog.cancel')}
          </button>
          <button
            type="submit"
            className="refund-primary"
            disabled={tooShort}
            aria-disabled={sending}
            aria-busy={sending}
          >
            {sending ? t('billing.refund.dialog.sending') : t('billing.refund.dialog.send')}
          </button>
        </div>
      </form>
    </dialog>
  );
}
