import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { reasonRequired, type ModerationAction } from './moderationActions';
import './workflowReports.css';

export const TEXT_MAX = 1000;

interface ModerationDialogProps {
  readonly action: ModerationAction;
  readonly pending: boolean;
  /** The localized problem text of the last failed attempt, shown inside the dialog. */
  readonly error: string | undefined;
  readonly onConfirm: (input: { reason: string; internalNote: string }) => void;
  readonly onClose: () => void;
}

/**
 * Modal that confirms one moderation action. A required reason that is blank after trimming stops
 * the submit before any request is sent; the dialog stays open on a failure so the cause is read in place.
 */
export function ModerationDialog({ action, pending, error, onConfirm, onClose }: ModerationDialogProps) {
  const { t } = useTranslation();
  const titleId = useId();
  const reasonId = useId();
  const reasonErrorId = useId();
  const noteId = useId();
  const dialogRef = useRef<HTMLDialogElement>(null);
  const reasonRef = useRef<HTMLTextAreaElement>(null);
  const [reason, setReason] = useState('');
  const [note, setNote] = useState('');
  const [missingReason, setMissingReason] = useState(false);
  const needsReason = reasonRequired(action);
  const takesContentDown = action === 'hide' || action === 'remove';

  useEffect(() => {
    const dialog = dialogRef.current;
    if (dialog && !dialog.open) dialog.showModal();
  }, []);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (pending) return;
    const trimmedReason = reason.trim();
    if (needsReason && trimmedReason === '') {
      setMissingReason(true);
      reasonRef.current?.focus();
      return;
    }
    onConfirm({ reason: trimmedReason, internalNote: note.trim() });
  };

  return (
    <dialog
      ref={dialogRef}
      className="cortex-admin-account-dialog"
      aria-labelledby={titleId}
      onClose={onClose}
      onClick={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <form className="moderation-dialog__body" onSubmit={submit} noValidate>
        <h2 id={titleId}>{t(`workflowReports.dialog.${action}.title`)}</h2>
        <p>{t(`workflowReports.dialog.${action}.description`)}</p>
        <div className="cortex-admin-field">
          <label htmlFor={reasonId}>
            {needsReason ? t('workflowReports.dialog.reasonRequired') : t('workflowReports.dialog.reasonOptional')}
          </label>
          <textarea
            id={reasonId}
            ref={reasonRef}
            rows={3}
            maxLength={TEXT_MAX}
            value={reason}
            required={needsReason}
            aria-invalid={missingReason ? true : undefined}
            aria-describedby={missingReason ? reasonErrorId : undefined}
            readOnly={pending}
            onChange={(event) => {
              setReason(event.target.value);
              setMissingReason(false);
            }}
          />
          {missingReason && (
            <p id={reasonErrorId} className="cortex-admin-form-error" role="alert">
              {t('workflowReports.dialog.reasonMissing')}
            </p>
          )}
        </div>
        <div className="cortex-admin-field">
          <label htmlFor={noteId}>{t('workflowReports.dialog.note')}</label>
          <textarea
            id={noteId}
            rows={3}
            maxLength={TEXT_MAX}
            value={note}
            readOnly={pending}
            onChange={(event) => setNote(event.target.value)}
          />
          <p className="moderation-dialog__hint">{t('workflowReports.dialog.noteHint')}</p>
        </div>
        {error && (
          <p className="cortex-admin-form-error" role="alert">
            {error}
          </p>
        )}
        <div className="moderation-dialog__actions">
          <button type="button" className="cortex-admin-btn" onClick={() => dialogRef.current?.close()}>
            {t('workflowReports.dialog.cancel')}
          </button>
          <button
            type="submit"
            className={`cortex-admin-btn ${takesContentDown ? 'cortex-admin-btn--danger' : 'cortex-admin-btn--primary'}`}
            aria-disabled={pending}
          >
            {pending ? t('workflowReports.dialog.working') : t(`workflowReports.dialog.${action}.confirm`)}
          </button>
        </div>
      </form>
    </dialog>
  );
}
