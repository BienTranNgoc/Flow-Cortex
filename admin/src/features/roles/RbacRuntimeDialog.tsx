import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';

/** Longest reason the server accepts. */
export const REASON_MAX = 2000;

interface RbacRuntimeDialogProps {
  /** The state the confirm button sets: false turns role edits off. */
  readonly turnOn: boolean;
  readonly pending: boolean;
  /** The localized problem text of the last failed attempt, shown inside the dialog. */
  readonly error: string | undefined;
  /** Resolves once the change is saved; a rejection leaves the dialog open with `error` set. */
  readonly onConfirm: (reason: string) => Promise<unknown>;
  readonly onClose: () => void;
}

/** Modal that asks for an optional reason before the switch flips; a refusal stays inside it so it is read in place. */
export function RbacRuntimeDialog({
  turnOn,
  pending,
  error,
  onConfirm,
  onClose,
}: Readonly<RbacRuntimeDialogProps>) {
  const { t } = useTranslation();
  const titleId = useId();
  const reasonId = useId();
  const dialogRef = useRef<HTMLDialogElement>(null);
  const [reason, setReason] = useState('');
  // Fixed at open: the saved state arrives before the dialog closes and must not flip its wording.
  const [action] = useState(turnOn ? 'turnOn' : 'turnOff');

  useEffect(() => {
    const dialog = dialogRef.current;
    if (dialog && !dialog.open) dialog.showModal();
  }, []);

  // Closing through the dialog itself, not by unmounting it, lets the browser hand focus back to
  // the toggle: while a modal is open the rest of the page is inert and focus() on it is ignored.
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (pending) return;
    try {
      await onConfirm(reason.trim());
    } catch {
      return; // the refusal is shown inside the dialog through `error`
    }
    dialogRef.current?.close();
  };

  return (
    <dialog
      ref={dialogRef}
      className="roles-dialog"
      aria-labelledby={titleId}
      onClose={onClose}
    >
      <form className="roles-dialog__body" onSubmit={submit} noValidate>
        <h2 id={titleId}>{t(`roles.runtime.dialog.${action}.title`)}</h2>
        <p>{t(`roles.runtime.dialog.${action}.description`)}</p>
        <div className="cortex-admin-field">
          <label htmlFor={reasonId}>{t('roles.runtime.dialog.reason')}</label>
          <textarea
            id={reasonId}
            rows={3}
            value={reason}
            maxLength={REASON_MAX}
            disabled={pending}
            onChange={(event) => setReason(event.target.value)}
          />
        </div>
        {error && (
          <p className="cortex-admin-form-error" role="alert">
            {error}
          </p>
        )}
        <div className="roles-dialog__actions">
          <button
            type="button"
            className="cortex-admin-btn"
            onClick={() => dialogRef.current?.close()}
          >
            {t('roles.runtime.dialog.cancel')}
          </button>
          <button
            type="submit"
            className={
              turnOn
                ? 'cortex-admin-btn cortex-admin-btn--primary'
                : 'cortex-admin-btn cortex-admin-btn--danger'
            }
            disabled={pending}
          >
            {pending ? t('roles.runtime.dialog.working') : t(`roles.runtime.dialog.${action}.confirm`)}
          </button>
        </div>
      </form>
    </dialog>
  );
}
