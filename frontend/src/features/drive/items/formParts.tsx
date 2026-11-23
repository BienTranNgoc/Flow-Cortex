import { useId, useRef, type ReactNode } from 'react';
import type { UseFormRegisterReturn } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { formatNumber } from '../../../lib/format';
import { activeLocale } from '../../../lib/i18n';
import { TEXT_MAX } from '../model/itemForm';
import { useModalDialog } from '../../../lib/useModalDialog';
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

interface NameRoleFieldsProps {
  /** The form's `useId()`; each field's id hangs off it. */
  ids: string;
  name: UseFormRegisterReturn;
  role: UseFormRegisterReturn;
  nameError: string | undefined;
  roleError: string | undefined;
  roles: readonly string[];
  disabled?: boolean;
}

/** The name input and the role select every item form asks for. */
export function NameRoleFields({
  ids,
  name,
  role,
  nameError,
  roleError,
  roles,
  disabled,
}: Readonly<NameRoleFieldsProps>) {
  const { t } = useTranslation();
  return (
    <>
      <div className="drive-field">
        <label htmlFor={`${ids}-name`}>{t('driveItems.form.name')}</label>
        <input
          id={`${ids}-name`}
          type="text"
          autoComplete="off"
          disabled={disabled}
          aria-invalid={nameError ? true : undefined}
          aria-describedby={nameError ? `${ids}-name-error` : undefined}
          {...name}
        />
        <FieldError id={`${ids}-name-error`} message={nameError} />
      </div>
      <div className="drive-field">
        <label htmlFor={`${ids}-role`}>{t('driveItems.form.role')}</label>
        <select
          id={`${ids}-role`}
          disabled={disabled}
          aria-invalid={roleError ? true : undefined}
          aria-describedby={roleError ? `${ids}-role-error` : undefined}
          {...role}
        >
          {roles.map((value) => (
            <option key={value} value={value}>
              {t(`drive.roles.${value}`, { defaultValue: value })}
            </option>
          ))}
        </select>
        <FieldError id={`${ids}-role-error`} message={roleError} />
      </div>
    </>
  );
}

/** A text item's text, with its length counter. */
export function TextValueField({
  ids,
  text,
  textError,
  length,
}: Readonly<{ ids: string; text: UseFormRegisterReturn; textError: string | undefined; length: number }>) {
  const { t } = useTranslation();
  return (
    <div className="drive-field">
      <label htmlFor={`${ids}-text`}>{t('driveItems.form.text')}</label>
      <textarea
        id={`${ids}-text`}
        rows={8}
        aria-invalid={textError ? true : undefined}
        aria-describedby={`${ids}-counter${textError ? ` ${ids}-text-error` : ''}`}
        {...text}
      />
      <TextCounter id={`${ids}-counter`} length={length} />
      <FieldError id={`${ids}-text-error`} message={textError} />
    </div>
  );
}

interface FormActionsProps {
  /** Cancel does nothing while this holds. */
  cancelBlocked: boolean;
  onCancel: () => void;
  /** A save is running: submit reads "Saving" and holds. */
  pending: boolean;
  submitLabel: string;
}

/** Cancel and submit at the foot of an item form. */
export function FormActions({ cancelBlocked, onCancel, pending, submitLabel }: Readonly<FormActionsProps>) {
  const { t } = useTranslation();
  return (
    <div className="drive-dialog__actions">
      <button
        type="button"
        className="drive-button"
        aria-disabled={cancelBlocked || undefined}
        onClick={() => {
          if (!cancelBlocked) onCancel();
        }}
      >
        {t('drive.confirm.cancel')}
      </button>
      <button
        type="submit"
        className="drive-button drive-button--primary"
        aria-disabled={pending || undefined}
        aria-busy={pending || undefined}
      >
        {pending ? t('driveItems.form.saving') : submitLabel}
      </button>
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
