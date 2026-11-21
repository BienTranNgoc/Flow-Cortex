import { useEffect, useRef, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import './onboarding.css';

interface JourneyDialogProps {
  titleId: string;
  title: string;
  /** Escape and the close button; the owner decides what leaving means. */
  onClose: () => void;
  children: ReactNode;
  footer: ReactNode;
}

/**
 * A native modal dialog that is open for as long as it is mounted: the title takes focus on open
 * and Escape asks the owner to close instead of closing itself.
 */
export function JourneyDialog({
  titleId,
  title,
  onClose,
  children,
  footer,
}: Readonly<JourneyDialogProps>) {
  const { t } = useTranslation();
  const dialogRef = useRef<HTMLDialogElement>(null);
  const titleRef = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (!dialog.open) dialog.showModal();
    titleRef.current?.focus();
    return () => {
      if (dialog.open) dialog.close();
    };
  }, []);

  return (
    <dialog
      ref={dialogRef}
      className="onboarding-dialog"
      aria-labelledby={titleId}
      onCancel={(event) => {
        event.preventDefault();
        onClose();
      }}
    >
      <div className="onboarding-dialog__body">
        <button
          type="button"
          className="onboarding-dialog__close"
          aria-label={t('onboarding.close')}
          onClick={onClose}
        >
          <svg viewBox="0 0 16 16" width="16" height="16" aria-hidden="true" focusable="false">
            <path
              d="M3 3l10 10M13 3L3 13"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              fill="none"
            />
          </svg>
        </button>
        <h2 id={titleId} ref={titleRef} tabIndex={-1} className="onboarding-dialog__title">
          {title}
        </h2>
        {children}
      </div>
      <div className="onboarding-dialog__footer">{footer}</div>
    </dialog>
  );
}
