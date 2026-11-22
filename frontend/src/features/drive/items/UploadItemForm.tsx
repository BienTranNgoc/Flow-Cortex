import { ApiError } from '@flow-cortex/api-client';
import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useId, useRef, useState, type ChangeEvent, type FormEvent } from 'react';
import { useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { extensionOf, sizeProblem, AUDIO, IMAGE, VIDEO, type FileProblem, type UploadKind } from '../../workflow/startRunSchema';
import { UploadFailedError } from '../../workflow/uploadFile';
import { rolesOf, type ContentType } from '../model/driveRoles';
import { nameFromFileName } from '../model/keepSuggestion';
import { fieldMessage, nameRoleSchema, showFieldRefusals, type NameRoleValues } from './formFields';
import { FieldError, FormDialog, FormMessages } from './formParts';
import { refusalOf } from './refusal';
import { useUploadDriveItem } from './useDriveItems';

const KINDS: Record<Exclude<ContentType, 'text'>, UploadKind> = { image: IMAGE, video: VIDEO, audio: AUDIO };

const FORM_FIELDS = ['name', 'semanticRole'] as const;
// Refusals about the file itself all belong under the file field.
const FILE_FIELDS = ['fileName', 'size', 'uploadId', 'contentType'] as const;
const VISIBLE_FIELDS = [...FORM_FIELDS, ...FILE_FIELDS];

const FILE_REQUIRED: FileProblem = { messageKey: 'driveItems.validation.fileRequired' };

/** A check made here (translated at render) or a sentence the server wrote (shown as received). */
type FileMessage = { kind: 'check'; problem: FileProblem } | { kind: 'server'; message: string };

type Phase = { kind: 'sending'; percent: number } | { kind: 'saving' };

function pickProblem(file: File, type: Exclude<ContentType, 'text'>): FileProblem | null {
  const kind = KINDS[type];
  if (!kind.extensions.includes(extensionOf(file.name))) {
    return {
      messageKey: 'driveItems.validation.fileType',
      values: { formats: kind.extensions.map((extension) => `.${extension}`).join(', ') },
    };
  }
  return sizeProblem(file, kind);
}

interface UploadItemFormProps {
  open: boolean;
  contentType: Exclude<ContentType, 'text'>;
  onClose: () => void;
  onSaved: (name: string) => void;
}

/** Uploads a file as a new item of one media type: send, then save, and Cancel only while sending. */
export function UploadItemForm({ open, contentType, onClose, onSaved }: Readonly<UploadItemFormProps>) {
  const { t } = useTranslation();
  const [busy, setBusy] = useState(false);
  return (
    <FormDialog
      open={open}
      title={t(`driveItems.upload.title.${contentType}`)}
      busy={busy}
      onClose={onClose}
    >
      <UploadFields contentType={contentType} onBusyChange={setBusy} onClose={onClose} onSaved={onSaved} />
    </FormDialog>
  );
}

function UploadFields({
  contentType,
  onBusyChange,
  onClose,
  onSaved,
}: Readonly<{
  contentType: Exclude<ContentType, 'text'>;
  onBusyChange: (busy: boolean) => void;
  onClose: () => void;
  onSaved: (name: string) => void;
}>) {
  const { t } = useTranslation();
  const ids = useId();
  const upload = useUploadDriveItem();
  const roles = rolesOf(contentType);
  const [file, setFile] = useState<File | null>(null);
  const [inputKey, setInputKey] = useState(0);
  const [fileMessage, setFileMessage] = useState<FileMessage | null>(null);
  const [phase, setPhase] = useState<Phase | null>(null);
  const [messages, setMessages] = useState<string[]>([]);
  const [cancelled, setCancelled] = useState(false);
  const controllerRef = useRef<AbortController | null>(null);
  const {
    register,
    handleSubmit,
    setError,
    setValue,
    resetField,
    formState: { errors, dirtyFields },
  } = useForm<NameRoleValues>({
    resolver: zodResolver(nameRoleSchema),
    defaultValues: { name: '', semanticRole: roles[0] ?? '' },
  });

  useEffect(() => onBusyChange(phase !== null), [phase, onBusyChange]);
  useEffect(() => () => controllerRef.current?.abort(), []);

  const onPick = (event: ChangeEvent<HTMLInputElement>) => {
    if (phase) return;
    const picked = event.target.files?.[0] ?? null;
    setFile(picked);
    setMessages([]);
    setCancelled(false);
    const problem = picked ? pickProblem(picked, contentType) : null;
    setFileMessage(problem ? { kind: 'check', problem } : null);
    if (picked && !dirtyFields.name) {
      setValue('name', nameFromFileName(picked.name), { shouldDirty: false });
    }
  };

  const send = (picked: File, values: NameRoleValues) => {
    const controller = new AbortController();
    controllerRef.current = controller;
    setMessages([]);
    setCancelled(false);
    setFileMessage(null);
    setPhase({ kind: 'sending', percent: 0 });
    upload.mutate(
      {
        file: picked,
        name: values.name,
        contentType,
        semanticRole: values.semanticRole,
        signal: controller.signal,
        onProgress: (percent) =>
          setPhase((current) => (current?.kind === 'sending' ? { kind: 'sending', percent } : current)),
        onSaving: () => setPhase({ kind: 'saving' }),
      },
      {
        onSuccess: () => onSaved(values.name),
        onError: (error) => {
          setPhase(null);
          if (controller.signal.aborted) {
            // A cancel rejects like a network failure; it is not an error to show.
            setFile(null);
            setInputKey((key) => key + 1);
            resetField('name');
            setCancelled(true);
            return;
          }
          if (error instanceof UploadFailedError) {
            setMessages([t('driveItems.upload.putFailed')]);
            return;
          }
          const refusal = refusalOf(error, VISIBLE_FIELDS, t('driveItems.form.failed'));
          showFieldRefusals(refusal.byField, FORM_FIELDS, setError);
          const fileSentences = FILE_FIELDS.flatMap((field) => refusal.byField[field] ?? []);
          if (fileSentences.length > 0) {
            setFileMessage({ kind: 'server', message: [...new Set(fileSentences)].join(' ') });
          }
          // A 503 (the media engine is off) is a wait, not a fix: the server's sentence alone does not say so.
          const unavailable = error instanceof ApiError && error.status === 503 && refusal.messages.length > 0;
          setMessages(unavailable ? [...refusal.messages, t('driveItems.upload.tryAgainLater')] : refusal.messages);
        },
      },
    );
  };

  const onSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (phase) return;
    const problem = file
      ? fileMessage?.kind === 'check'
        ? fileMessage.problem
        : null
      : FILE_REQUIRED;
    if (problem) setFileMessage({ kind: 'check', problem });
    void handleSubmit((values) => {
      if (file && !problem) send(file, values);
    })(event);
  };

  const nameError = fieldMessage(errors.name, t);
  const roleError = fieldMessage(errors.semanticRole, t);
  const fileError =
    fileMessage?.kind === 'server'
      ? fileMessage.message
      : fileMessage
        ? t(fileMessage.problem.messageKey, fileMessage.problem.values ?? {})
        : undefined;

  // Announced in steps of ten so a screen reader is not read every progress event.
  const announced =
    phase?.kind === 'sending'
      ? t('driveItems.upload.sending', { percent: Math.floor(phase.percent / 10) * 10 })
      : phase?.kind === 'saving'
        ? t('driveItems.upload.saving')
        : cancelled
          ? t('driveItems.upload.cancelled')
          : '';

  return (
    <form className="drive-form" noValidate onSubmit={onSubmit} aria-label={t(`driveItems.upload.title.${contentType}`)}>
      <div className="drive-field">
        <label htmlFor={`${ids}-file`}>{t('driveItems.upload.file')}</label>
        <input
          key={inputKey}
          id={`${ids}-file`}
          type="file"
          accept={KINDS[contentType].extensions.map((extension) => `.${extension}`).join(',')}
          disabled={phase !== null}
          aria-invalid={fileError ? true : undefined}
          aria-describedby={fileError ? `${ids}-file-error` : undefined}
          onChange={onPick}
        />
        <FieldError id={`${ids}-file-error`} message={fileError} />
      </div>
      <div className="drive-field">
        <label htmlFor={`${ids}-name`}>{t('driveItems.form.name')}</label>
        <input
          id={`${ids}-name`}
          type="text"
          autoComplete="off"
          disabled={phase !== null}
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
          disabled={phase !== null}
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
      {phase && (
        <div className="drive-upload__phase">
          <p className="drive-upload__label">
            {phase.kind === 'sending'
              ? t('driveItems.upload.sending', { percent: phase.percent })
              : t('driveItems.upload.saving')}
          </p>
          {phase.kind === 'sending' ? (
            <progress
              className="drive-upload__progress"
              max={100}
              value={phase.percent}
              aria-label={t('driveItems.upload.progress')}
            />
          ) : (
            <progress className="drive-upload__progress" aria-label={t('driveItems.upload.saving')} />
          )}
        </div>
      )}
      <output className="visually-hidden">{announced}</output>
      <FormMessages messages={messages} />
      <div className="drive-dialog__actions">
        <button
          type="button"
          className="drive-button"
          aria-disabled={phase?.kind === 'saving' || undefined}
          onClick={() => {
            if (phase?.kind === 'saving') return;
            if (phase) controllerRef.current?.abort();
            else onClose();
          }}
        >
          {t('drive.confirm.cancel')}
        </button>
        <button
          type="submit"
          className="drive-button drive-button--primary"
          aria-disabled={phase !== null || undefined}
          aria-busy={phase !== null || undefined}
        >
          {phase ? t('driveItems.form.saving') : t('driveItems.upload.submit')}
        </button>
      </div>
    </form>
  );
}
