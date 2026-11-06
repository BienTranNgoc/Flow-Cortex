import { SupportRequestCreateType, type SupportRequestCreate } from '@flow-cortex/api-client';
import { useEffect, useId, useRef, useState, type ChangeEvent, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { addAttachments, ATTACHMENT_ACCEPT } from './attachments';
import { useSupportPanel, type RequestPrefill } from './supportPanelState';
import { ContactLine } from './HomeTab';
import {
  failureOf,
  isSuspended,
  textLength,
  useCreateRequest,
  useSubmitKey,
  useUploadedIds,
} from './useRequests';

const TYPES = Object.values(SupportRequestCreateType);

function Preview({ file, onRemove, disabled }: Readonly<{ file: File; onRemove: () => void; disabled: boolean }>) {
  const { t } = useTranslation();
  const [url, setUrl] = useState<string | null>(null);
  useEffect(() => {
    const created = URL.createObjectURL(file);
    setUrl(created);
    return () => URL.revokeObjectURL(created);
  }, [file]);
  return (
    <li className="support-attachments__item">
      {url && <img className="support-attachments__thumb" src={url} alt={file.name} />}
      <span className="support-attachments__name">{file.name}</span>
      <button
        type="button"
        className="support-button"
        aria-label={t('support.requests.attachRemove', { name: file.name })}
        disabled={disabled}
        onClick={onRemove}
      >
        <svg width="16" height="16" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
          <path d="M6 6l12 12M18 6L6 18" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
        </svg>
      </button>
    </li>
  );
}

/** Up to three images with previews; a refused file keeps the rest of the form and says why. */
export function AttachmentPicker({
  files,
  onChange,
  disabled,
}: Readonly<{ files: File[]; onChange: (files: File[]) => void; disabled: boolean }>) {
  const { t } = useTranslation();
  const inputId = useId();
  const [refused, setRefused] = useState<string[]>([]);

  const onPick = (event: ChangeEvent<HTMLInputElement>) => {
    const result = addAttachments(files, [...(event.target.files ?? [])], t);
    event.target.value = '';
    setRefused(result.refused);
    if (result.files.length !== files.length) onChange(result.files);
  };

  return (
    <div className="support-field">
      <label className="support-field__label" htmlFor={inputId}>
        {t('support.requests.attachLabel')}
      </label>
      <input
        id={inputId}
        className="support-attachments__input"
        type="file"
        accept={ATTACHMENT_ACCEPT}
        multiple
        disabled={disabled}
        onChange={onPick}
      />
      {refused.length > 0 && (
        <ul className="support-field__error" role="alert">
          {refused.map((reason) => (
            <li key={reason}>{reason}</li>
          ))}
        </ul>
      )}
      {files.length > 0 && (
        <ul className="support-attachments">
          {files.map((file, index) => (
            <Preview
              key={`${file.name}-${file.lastModified}-${index}`}
              file={file}
              disabled={disabled}
              onRemove={() => {
                setRefused([]);
                onChange(files.filter((_, at) => at !== index));
              }}
            />
          ))}
        </ul>
      )}
    </div>
  );
}

/** The 403 answer for a suspended member, followed by the contact line when one is configured. */
export function SuspendedNotice() {
  const { t } = useTranslation();
  return (
    <div className="support-state" role="alert">
      <p>{t('support.requests.suspended')}</p>
      <ContactLine />
    </div>
  );
}

/** A failed submit or action, with a reload when the request changed elsewhere. */
export function FailureNotice({ error, onReload }: Readonly<{ error: unknown; onReload?: () => void }>) {
  const { t } = useTranslation();
  if (isSuspended(error)) return <SuspendedNotice />;
  const failure = failureOf(error, t);
  return (
    <div className="support-state" role="alert">
      <p>{failure.message}</p>
      {failure.reload && onReload && (
        <button type="button" className="support-button" onClick={onReload}>
          {t('support.requests.reload')}
        </button>
      )}
    </div>
  );
}

interface FormErrors {
  type?: string | undefined;
  subject?: string | undefined;
  description?: string | undefined;
}

/** Holds locale keys, translated at render so a language switch re-renders them. */
function validate(type: string, subject: string, description: string): FormErrors {
  const errors: FormErrors = {};
  if (!type) errors.type = 'support.requests.typeRequired';
  const subjectLength = textLength(subject);
  if (subjectLength < 5 || subjectLength > 120) errors.subject = 'support.requests.subjectLength';
  const descriptionLength = textLength(description);
  if (descriptionLength < 10 || descriptionLength > 5000) {
    errors.description = 'support.requests.descriptionLength';
  }
  return errors;
}

/** The new-request form; on success the tab shows the created request. */
export function RequestForm({ prefill }: Readonly<{ prefill?: RequestPrefill | null }>) {
  const { t } = useTranslation();
  const { dispatch } = useSupportPanel();
  const create = useCreateRequest();
  const submitKey = useSubmitKey();
  const uploaded = useUploadedIds();
  const [type, setType] = useState('');
  const [subject, setSubject] = useState('');
  const [description, setDescription] = useState(prefill?.description ?? '');
  const [files, setFiles] = useState<File[]>([]);
  const [errors, setErrors] = useState<FormErrors>({});
  const [failed, setFailed] = useState<{ error: unknown } | null>(null);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const ids = { type: useId(), subject: useId(), description: useId() };
  const fieldRefs = {
    type: useRef<HTMLSelectElement>(null),
    subject: useRef<HTMLInputElement>(null),
    description: useRef<HTMLTextAreaElement>(null),
  };

  const edited = <T,>(set: (value: T) => void) => (value: T) => {
    submitKey.reset();
    set(value);
  };

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    if (busyRef.current) return;
    const found = validate(type, subject, description);
    setErrors(found);
    const firstInvalid = (['type', 'subject', 'description'] as const).find((field) => found[field]);
    if (firstInvalid) {
      fieldRefs[firstInvalid].current?.focus();
      return;
    }
    busyRef.current = true;
    setBusy(true);
    setFailed(null);
    try {
      const uploadIds = await uploaded(files);
      const body: SupportRequestCreate = {
        type: type as SupportRequestCreate['type'],
        subject: subject.trim(),
        description: description.trim(),
        ...(uploadIds.length > 0 ? { uploadIds } : {}),
        ...(prefill?.aiConversationId ? { aiConversationId: prefill.aiConversationId } : {}),
      };
      const created = await create.mutateAsync({ body, key: submitKey.current() });
      dispatch({ type: 'selectRequest', request: created.number });
    } catch (error) {
      const { fields } = failureOf(error, t);
      setFailed({ error });
      setErrors({ type: fields.type, subject: fields.subject, description: fields.description });
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  const errorId = (field: keyof FormErrors) => (errors[field] ? `${ids[field]}-error` : undefined);

  return (
    <form className="support-request-form" noValidate onSubmit={onSubmit}>
      <div className="support-field">
        <label className="support-field__label" htmlFor={ids.type}>
          {t('support.requests.typeLabel')}
        </label>
        <select
          id={ids.type}
          ref={fieldRefs.type}
          className="support-field__input"
          value={type}
          aria-invalid={errors.type ? true : undefined}
          aria-describedby={errorId('type')}
          disabled={busy}
          onChange={(event) => edited(setType)(event.target.value)}
        >
          <option value="" disabled>
            {t('support.requests.typePlaceholder')}
          </option>
          {TYPES.map((value) => (
            <option key={value} value={value}>
              {t(`support.requests.types.${value}`)}
            </option>
          ))}
        </select>
        {errors.type && (
          <p id={errorId('type')} className="support-field__error">
            {t(errors.type)}
          </p>
        )}
      </div>
      <div className="support-field">
        <label className="support-field__label" htmlFor={ids.subject}>
          {t('support.requests.subjectLabel')}
        </label>
        <input
          id={ids.subject}
          ref={fieldRefs.subject}
          className="support-field__input"
          value={subject}
          aria-invalid={errors.subject ? true : undefined}
          aria-describedby={errorId('subject')}
          disabled={busy}
          onChange={(event) => edited(setSubject)(event.target.value)}
        />
        {errors.subject && (
          <p id={errorId('subject')} className="support-field__error">
            {t(errors.subject)}
          </p>
        )}
      </div>
      <div className="support-field">
        <label className="support-field__label" htmlFor={ids.description}>
          {t('support.requests.descriptionLabel')}
        </label>
        <textarea
          id={ids.description}
          ref={fieldRefs.description}
          className="support-field__input support-field__input--multiline"
          value={description}
          rows={5}
          aria-invalid={errors.description ? true : undefined}
          aria-describedby={errorId('description')}
          disabled={busy}
          onChange={(event) => edited(setDescription)(event.target.value)}
        />
        {errors.description && (
          <p id={errorId('description')} className="support-field__error">
            {t(errors.description)}
          </p>
        )}
      </div>
      {prefill?.aiConversationId && <p className="support-contact">{t('support.requests.conversationLinked')}</p>}
      <AttachmentPicker files={files} onChange={edited(setFiles)} disabled={busy} />
      {failed && (
        <FailureNotice
          error={failed.error}
          onReload={() => dispatch({ type: 'selectRequest', request: null })}
        />
      )}
      <button type="submit" className="support-button support-button--primary" disabled={busy}>
        {busy ? t('support.requests.sending') : t('support.requests.submit')}
      </button>
    </form>
  );
}
