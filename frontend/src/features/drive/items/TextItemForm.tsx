import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useId, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { rolesOf } from '../model/driveRoles';
import { textItemSchema, type TextItemFormValues } from '../model/itemForm';
import { fieldMessage, showFieldRefusals } from './formFields';
import { FormActions, FormDialog, FormMessages, NameRoleFields, TextValueField } from './formParts';
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

  return (
    <form className="drive-form" noValidate onSubmit={onSubmit} aria-label={t('driveItems.text.title')}>
      <NameRoleFields
        ids={ids}
        name={register('name')}
        role={register('semanticRole')}
        nameError={fieldMessage(errors.name, t)}
        roleError={fieldMessage(errors.semanticRole, t)}
        roles={roles}
      />
      <TextValueField
        ids={ids}
        text={register('textValue')}
        textError={fieldMessage(errors.textValue, t)}
        length={length}
      />
      <FormMessages messages={messages} />
      <FormActions
        cancelBlocked={create.isPending}
        onCancel={onClose}
        pending={create.isPending}
        submitLabel={t('driveItems.form.save')}
      />
    </form>
  );
}
