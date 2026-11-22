import { useId, useRef, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { formatNumber } from '../../../lib/format';
import { activeLocale } from '../../../lib/i18n';
import { TEXT_MAX } from '../model/itemForm';
import { useModalDialog } from '../shared/useModalDialog';
import '../shared/DriveShared.css';
import './DriveItems.css';

export function FieldError({ id, message }: Readonly<{ id: string; message: string | undefined }>) {
  if (!message) return null;
  return (
    <p id={id} role="alert" className="drive-field__error">
      {message}
    </p>
  );
}

/** "n / 32,768" under a text field; turns red over the limit. */
export function TextCounter({ id, length }: Readonly<{ id: string; length: number }>) {
  const { t } = useTranslation();
  const locale = activeLocale();
  return (
    <p
      id={id}
      className={length > TEXT_MAX ? 'drive-field__counter drive-field__counter--over' : 'drive-field__counter'}
    >
      {t('driveItems.form.counter', {
        n: formatNumber(length, locale),
        max: formatNumber(TEXT_MAX, locale),
      })}
    </p>
  );
}

/** Form-level refusals, one line each, announced when they appear. */
export function FormMessages({ messages }: Readonly<{ messages: readonly string[] }>) {
  if (messages.length === 0) return null;
  return (
    <div role="alert" className="drive-form__messages">
      {messages.map((message) => (
        <p key={message}>{message}</p>
      ))}
    </div>
  );
}

interface FormDialogProps {
  open: boolean;
  title: string;
  /** A save is running: Escape holds until it answers. */
  busy: boolean;
  onClose: () => void;
  /** Mounted only while open, so a form starts empty every time. */
  children: ReactNode;
}

/** A native modal around one form: focus starts on the title and goes back to the opener on close. */
export function FormDialog({ open, title, busy, onClose, children }: Readonly<FormDialogProps>) {
  const titleId = useId();
  const titleRef = useRef<HTMLHeadingElement>(null);
  const dialogRef = useModalDialog(open, titleRef);

  return (
    <dialog
      ref={dialogRef}
      className="drive-dialog drive-dialog--form"
      aria-labelledby={titleId}
      onCancel={(event) => {
        event.preventDefault();
        if (!busy) onClose();
      }}
      // A browser may close the dialog on a repeated Escape despite `preventDefault`.
      onClose={() => {
        if (!open) return;
        if (busy) dialogRef.current?.showModal();
        else onClose();
      }}
    >
      <h2 id={titleId} ref={titleRef} tabIndex={-1} className="drive-dialog__title">
        {title}
      </h2>
      {open && children}
    </dialog>
  );
}
