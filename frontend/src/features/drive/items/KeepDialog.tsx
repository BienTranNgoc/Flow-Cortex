import { zodResolver } from '@hookform/resolvers/zod';
import type { SuggestionResponse } from '@flow-cortex/api-client';
import { useEffect, useId, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { keepDefaults, keepRequest } from '../model/keepSuggestion';
import { fieldMessage, nameRoleSchema, showFieldRefusals, type NameRoleValues } from './formFields';
import { FormActions, FormDialog, FormMessages, NameRoleFields } from './formParts';
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

  return (
    <form className="drive-form" noValidate onSubmit={onSubmit} aria-label={t('driveItems.keep.title')}>
      <NameRoleFields
        ids={ids}
        name={register('name')}
        role={register('semanticRole')}
        nameError={fieldMessage(errors.name, t)}
        roleError={fieldMessage(errors.semanticRole, t)}
        roles={defaults.roles}
      />
      <FormMessages messages={messages} />
      <FormActions
        cancelBlocked={create.isPending}
        onCancel={onClose}
        pending={create.isPending}
        submitLabel={t('driveItems.keep.submit')}
      />
    </form>
  );
}
