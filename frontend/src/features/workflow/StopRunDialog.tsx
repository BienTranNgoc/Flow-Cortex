import { useEffect, useId, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { activeLocale } from '../../lib/i18n';
import { formatCredits } from '../credits/formatCredits';

interface StopRunDialogProps {
  open: boolean;
  /** What the run has cost so far; the line is left out when the amount is unknown. */
  chargedCredits: number | null | undefined;
  /** The stop is being sent: both choices hold until it answers. */
  stopping: boolean;
  onKeep: () => void;
  onStop: () => void;
}

/**
 * The confirmation before a run is stopped, as a native modal dialog: focus starts on
 * "Keep running", Escape means "Keep running", and focus goes back to whatever opened it.
 */
export function StopRunDialog({
  open,
  chargedCredits,
  stopping,
  onKeep,
  onStop,
}: Readonly<StopRunDialogProps>) {
  const { t } = useTranslation();
  const titleId = useId();
  const textId = useId();
  const dialogRef = useRef<HTMLDialogElement>(null);
  const keepRef = useRef<HTMLButtonElement>(null);
  const openerRef = useRef<HTMLElement | null>(null);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (open) {
      if (!dialog.open) {
        openerRef.current =
          document.activeElement instanceof HTMLElement ? document.activeElement : null;
        dialog.showModal();
        keepRef.current?.focus();
      }
      return;
    }
    if (dialog.open) dialog.close();
    const opener = openerRef.current;
    openerRef.current = null;
    if (opener?.isConnected) opener.focus();
  }, [open]);

  return (
    <dialog
      ref={dialogRef}
      className="stop-run-dialog"
      aria-labelledby={titleId}
      aria-describedby={textId}
      onCancel={(event) => {
        event.preventDefault();
        if (!stopping) onKeep();
      }}
      // A browser may close the dialog on a repeated Escape despite `preventDefault`.
      onClose={() => {
        if (open) onKeep();
      }}
    >
      <h2 id={titleId}>{t('workflow.stop.title')}</h2>
      <p id={textId}>{t('workflow.stop.text')}</p>
      {typeof chargedCredits === 'number' && (
        <p className="stop-run-charged">
          {t(chargedCredits === 1 ? 'credits.chargedSoFarOne' : 'credits.chargedSoFarOther', {
            amount: formatCredits(chargedCredits, activeLocale()),
          })}
        </p>
      )}
      <div className="stop-run-actions">
        <button
          ref={keepRef}
          type="button"
          onClick={() => {
            if (!stopping) onKeep();
          }}
          aria-disabled={stopping}
        >
          {t('workflow.stop.keep')}
        </button>
        <button
          type="button"
          className="stop-run-confirm"
          onClick={() => {
            if (!stopping) onStop();
          }}
          aria-disabled={stopping}
          aria-busy={stopping}
        >
          {stopping ? t('workflow.stop.stopping') : t('workflow.stop.confirm')}
        </button>
      </div>
    </dialog>
  );
}
