import { zodResolver } from '@hookform/resolvers/zod';
import type { DriveItemResponse, UpdateDriveItemRequest } from '@flow-cortex/api-client';
import { useEffect, useId, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { rolesOf } from '../model/driveRoles';
import { editItemSchema, type EditItemFormValues } from '../model/itemForm';
import { fieldMessage, showFieldRefusals } from './formFields';
import { FormActions, FormDialog, FormMessages, NameRoleFields, TextValueField } from './formParts';
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
      <NameRoleFields
        ids={ids}
        name={register('name')}
        role={register('semanticRole')}
        nameError={fieldMessage(errors.name, t)}
        roleError={fieldMessage(errors.semanticRole, t)}
        roles={roles}
      />
      {isText && (
        <TextValueField
          ids={ids}
          text={register('textValue')}
          textError={fieldMessage(errors.textValue, t)}
          length={length}
        />
      )}
      <FormMessages messages={messages} />
      <FormActions
        cancelBlocked={update.isPending}
        onCancel={onClose}
        pending={update.isPending}
        submitLabel={t('driveItems.form.save')}
      />
    </form>
  );
}
