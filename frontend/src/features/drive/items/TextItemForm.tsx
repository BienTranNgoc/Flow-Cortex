import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useId, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { rolesOf } from '../model/driveRoles';
import { textItemSchema, type TextItemFormValues } from '../model/itemForm';
import { fieldMessage, showFieldRefusals } from './formFields';
import { FieldError, FormDialog, FormMessages, TextCounter } from './formParts';
import { refusalOf } from './refusal';
import { useCreateDriveItem } from './useDriveItems';

const FIELDS = ['name', 'semanticRole', 'textValue'] as const;

interface TextItemFormProps {
  open: boolean;
  onClose: () => void;
  onSaved: (name: string) => void;
}

/** Writes a new text item: a name, one of the text roles and the text. */
export function TextItemForm({ open, onClose, onSaved }: Readonly<TextItemFormProps>) {
  const { t } = useTranslation();
  const [busy, setBusy] = useState(false);
  return (
    <FormDialog open={open} title={t('driveItems.text.title')} busy={busy} onClose={onClose}>
      <TextItemFields onBusyChange={setBusy} onClose={onClose} onSaved={onSaved} />
    </FormDialog>
  );
}

function TextItemFields({
  onBusyChange,
  onClose,
  onSaved,
}: Readonly<{
  onBusyChange: (busy: boolean) => void;
  onClose: () => void;
  onSaved: (name: string) => void;
}>) {
  const { t } = useTranslation();
  const ids = useId();
  const create = useCreateDriveItem();
  const [messages, setMessages] = useState<string[]>([]);
  const roles = rolesOf('text');
  const {
    register,
    handleSubmit,
    setError,
    watch,
    formState: { errors },
  } = useForm<TextItemFormValues>({
    resolver: zodResolver(textItemSchema),
    defaultValues: { name: '', semanticRole: roles[0] ?? '', textValue: '' },
  });

  useEffect(() => onBusyChange(create.isPending), [create.isPending, onBusyChange]);

  const length = watch('textValue').length;

  const onSubmit = handleSubmit((values) => {
    if (create.isPending) return;
    setMessages([]);
    create.mutate(
      {
        name: values.name,
        contentType: 'text',
        semanticRole: values.semanticRole,
        textValue: values.textValue,
      },
      {
        onSuccess: () => onSaved(values.name),
        onError: (error) => {
          const refusal = refusalOf(error, FIELDS, t('driveItems.form.failed'));
          showFieldRefusals(refusal.byField, FIELDS, setError);
          setMessages(refusal.messages);
        },
      },
    );
  });

  const nameError = fieldMessage(errors.name, t);
  const roleError = fieldMessage(errors.semanticRole, t);
  const textError = fieldMessage(errors.textValue, t);

  return (
    <form className="drive-form" noValidate onSubmit={onSubmit} aria-label={t('driveItems.text.title')}>
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
          {roles.map((role) => (
            <option key={role} value={role}>
              {t(`drive.roles.${role}`, { defaultValue: role })}
            </option>
          ))}
        </select>
        <FieldError id={`${ids}-role-error`} message={roleError} />
      </div>
      <div className="drive-field">
        <label htmlFor={`${ids}-text`}>{t('driveItems.form.text')}</label>
        <textarea
          id={`${ids}-text`}
          rows={8}
          aria-invalid={textError ? true : undefined}
          aria-describedby={`${ids}-counter${textError ? ` ${ids}-text-error` : ''}`}
          {...register('textValue')}
        />
        <TextCounter id={`${ids}-counter`} length={length} />
        <FieldError id={`${ids}-text-error`} message={textError} />
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
          {create.isPending ? t('driveItems.form.saving') : t('driveItems.form.save')}
        </button>
      </div>
    </form>
  );
}
