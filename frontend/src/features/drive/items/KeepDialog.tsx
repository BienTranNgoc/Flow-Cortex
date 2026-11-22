import { zodResolver } from '@hookform/resolvers/zod';
import type { SuggestionResponse } from '@flow-cortex/api-client';
import { useEffect, useId, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { keepDefaults, keepRequest } from '../model/keepSuggestion';
import { fieldMessage, nameRoleSchema, showFieldRefusals, type NameRoleValues } from './formFields';
import { FieldError, FormDialog, FormMessages } from './formParts';
import { refusalOf } from './refusal';
import { useCreateDriveItem } from './useDriveItems';

const FIELDS = ['name', 'semanticRole'] as const;

interface KeepDialogProps {
  /** The candidate being kept; `null` keeps the dialog closed. */
  candidate: SuggestionResponse | null;
  onClose: () => void;
  onKept: (candidate: SuggestionResponse, name: string) => void;
}

/** Saves one suggestion into Drive under a name and a role of its kind. */
export function KeepDialog({ candidate, onClose, onKept }: Readonly<KeepDialogProps>) {
  const { t } = useTranslation();
  const [busy, setBusy] = useState(false);
  return (
    <FormDialog open={candidate !== null} title={t('driveItems.keep.title')} busy={busy} onClose={onClose}>
      {candidate && <KeepFields candidate={candidate} onBusyChange={setBusy} onClose={onClose} onKept={onKept} />}
    </FormDialog>
  );
}

function KeepFields({
  candidate,
  onBusyChange,
  onClose,
  onKept,
}: Readonly<{
  candidate: SuggestionResponse;
  onBusyChange: (busy: boolean) => void;
  onClose: () => void;
  onKept: (candidate: SuggestionResponse, name: string) => void;
}>) {
  const { t } = useTranslation();
  const ids = useId();
  const create = useCreateDriveItem();
  const defaults = keepDefaults(candidate);
  const [messages, setMessages] = useState<string[]>([]);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<NameRoleValues>({
    resolver: zodResolver(nameRoleSchema),
    defaultValues: { name: defaults.name, semanticRole: defaults.role },
  });

  useEffect(() => onBusyChange(create.isPending), [create.isPending, onBusyChange]);

  const onSubmit = handleSubmit((values) => {
    if (create.isPending) return;
    setMessages([]);
    const request = keepRequest(candidate, values.name, values.semanticRole);
    if ('refusal' in request) {
      setMessages([t('driveItems.suggestions.tooLong')]);
      return;
    }
    create.mutate(request, {
      onSuccess: () => onKept(candidate, values.name),
      onError: (error) => {
        // Notably `runOutput`: the output is gone or not keepable, a cause no field of this form shows.
        const refusal = refusalOf(error, FIELDS, t('driveItems.form.failed'));
        showFieldRefusals(refusal.byField, FIELDS, setError);
        setMessages(refusal.messages);
      },
    });
  });

  const nameError = fieldMessage(errors.name, t);
  const roleError = fieldMessage(errors.semanticRole, t);

  return (
    <form className="drive-form" noValidate onSubmit={onSubmit} aria-label={t('driveItems.keep.title')}>
      <div className="drive-field">
        <label htmlFor={`${ids}-name`}>{t('driveItems.form.name')}</label>
        <input
          id={`${ids}-name`}
          type="text"
          autoComplete="off"
          aria-invalid={nameError ? true : undefined}
          aria-describedby={nameError ? `${ids}-name-error` : undefined}
          {...register('name')}
        />
        <FieldError id={`${ids}-name-error`} message={nameError} />
      </div>
      <div className="drive-field">
        <label htmlFor={`${ids}-role`}>{t('driveItems.form.role')}</label>
        <select
          id={`${ids}-role`}
          aria-invalid={roleError ? true : undefined}
          aria-describedby={roleError ? `${ids}-role-error` : undefined}
          {...register('semanticRole')}
        >
          {defaults.roles.map((role) => (
            <option key={role} value={role}>
              {t(`drive.roles.${role}`, { defaultValue: role })}
            </option>
          ))}
        </select>
        <FieldError id={`${ids}-role-error`} message={roleError} />
      </div>
      <FormMessages messages={messages} />
      <div className="drive-dialog__actions">
        <button
          type="button"
          className="drive-button"
          aria-disabled={create.isPending || undefined}
          onClick={() => {
            if (!create.isPending) onClose();
          }}
        >
          {t('drive.confirm.cancel')}
        </button>
        <button
          type="submit"
          className="drive-button drive-button--primary"
          aria-disabled={create.isPending || undefined}
          aria-busy={create.isPending || undefined}
        >
          {create.isPending ? t('driveItems.form.saving') : t('driveItems.keep.submit')}
        </button>
      </div>
    </form>
  );
}
