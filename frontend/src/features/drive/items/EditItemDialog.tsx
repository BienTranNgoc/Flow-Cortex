import { zodResolver } from '@hookform/resolvers/zod';
import type { DriveItemResponse, UpdateDriveItemRequest } from '@flow-cortex/api-client';
import { useEffect, useId, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { rolesOf } from '../model/driveRoles';
import { editItemSchema, type EditItemFormValues } from '../model/itemForm';
import { fieldMessage, showFieldRefusals } from './formFields';
import { FieldError, FormDialog, FormMessages, TextCounter } from './formParts';
import { refusalOf } from './refusal';
import { useUpdateDriveItem } from './useDriveItems';

const FIELDS = ['name', 'semanticRole', 'textValue'] as const;

interface EditItemDialogProps {
  /** The item being edited; `null` keeps the dialog closed. */
  item: DriveItemResponse | null;
  onClose: () => void;
  onSaved: (name: string) => void;
}

/** Changes an item's name, its role within its content type and, for a text item, its text. */
export function EditItemDialog({ item, onClose, onSaved }: Readonly<EditItemDialogProps>) {
  const { t } = useTranslation();
  const [busy, setBusy] = useState(false);
  // The last item stays rendered while the dialog closes, so the title does not blank out.
  const [shown, setShown] = useState(item);
  useEffect(() => {
    if (item) setShown(item);
  }, [item]);

  return (
    <FormDialog
      open={item !== null}
      title={t('driveItems.edit.title', { name: shown?.name ?? '' })}
      busy={busy}
      onClose={onClose}
    >
      {item && <EditFields item={item} onBusyChange={setBusy} onClose={onClose} onSaved={onSaved} />}
    </FormDialog>
  );
}

function EditFields({
  item,
  onBusyChange,
  onClose,
  onSaved,
}: Readonly<{
  item: DriveItemResponse;
  onBusyChange: (busy: boolean) => void;
  onClose: () => void;
  onSaved: (name: string) => void;
}>) {
  const { t } = useTranslation();
  const ids = useId();
  const update = useUpdateDriveItem(item.id);
  const [messages, setMessages] = useState<string[]>([]);
  const isText = item.contentType === 'text';
  const roles = rolesOf(item.contentType);
  const {
    register,
    handleSubmit,
    setError,
    watch,
    formState: { errors },
  } = useForm<EditItemFormValues>({
    resolver: zodResolver(editItemSchema),
    defaultValues: {
      name: item.name,
      semanticRole: item.semanticRole,
      ...(isText ? { textValue: item.textValue ?? '' } : {}),
    },
  });

  useEffect(() => onBusyChange(update.isPending), [update.isPending, onBusyChange]);

  const length = watch('textValue')?.length ?? 0;

  const onSubmit = handleSubmit((values) => {
    if (update.isPending) return;
    setMessages([]);
    const changes: UpdateDriveItemRequest = {
      ...(values.name === item.name ? {} : { name: values.name }),
      ...(values.semanticRole === item.semanticRole ? {} : { semanticRole: values.semanticRole }),
      ...(isText && values.textValue !== undefined && values.textValue !== (item.textValue ?? '')
        ? { textValue: values.textValue }
        : {}),
    };
    if (Object.keys(changes).length === 0) {
      onClose();
      return;
    }
    update.mutate(changes, {
      onSuccess: () => onSaved(values.name),
      onError: (error) => {
        const refusal = refusalOf(error, FIELDS, t('driveItems.form.failed'));
        showFieldRefusals(refusal.byField, FIELDS, setError);
        setMessages(refusal.messages);
      },
    });
  });

  const nameError = fieldMessage(errors.name, t);
  const roleError = fieldMessage(errors.semanticRole, t);
  const textError = fieldMessage(errors.textValue, t);

  return (
    <form className="drive-form" noValidate onSubmit={onSubmit} aria-label={t('driveItems.edit.formLabel')}>
      <dl className="drive-readonly">
        <div>
          <dt>{t('driveItems.edit.contentType')}</dt>
          <dd>{t(`driveItems.types.${item.contentType}`)}</dd>
        </div>
        {item.fileName && (
          <div>
            <dt>{t('driveItems.edit.file')}</dt>
            <dd className="drive-readonly__file">{item.fileName}</dd>
          </div>
        )}
      </dl>
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
      {isText && (
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
      )}
      <FormMessages messages={messages} />
      <div className="drive-dialog__actions">
        <button
          type="button"
          className="drive-button"
          aria-disabled={update.isPending || undefined}
          onClick={() => {
            if (!update.isPending) onClose();
          }}
        >
          {t('drive.confirm.cancel')}
        </button>
        <button
          type="submit"
          className="drive-button drive-button--primary"
          aria-disabled={update.isPending || undefined}
          aria-busy={update.isPending || undefined}
        >
          {update.isPending ? t('driveItems.form.saving') : t('driveItems.form.save')}
        </button>
      </div>
    </form>
  );
}
