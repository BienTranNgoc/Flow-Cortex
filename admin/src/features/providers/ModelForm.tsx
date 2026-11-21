import { ApiError, type AiModel, type AiModelRequest } from '@flow-cortex/api-client';
import type { TFunction } from 'i18next';
import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useCreateModelMutation, useReplaceModelMutation, useVendorModels } from './useAiProviders';

const MAX_KEY = 128;
const MAX_PROVIDER = 32;
const DEFAULT_TOKENS = '4096';

interface FormValues {
  modelKey: string;
  providerKey: string;
  displayName: string;
  maxOutputTokens: string;
  displayOrder: string;
  supportsVision: boolean;
  supportsAudio: boolean;
  active: boolean;
}

type FieldName = 'modelKey' | 'providerKey' | 'displayName' | 'maxOutputTokens' | 'displayOrder';
type FieldErrors = Partial<Record<FieldName, string | undefined>>;

const FIELD_NAMES: readonly FieldName[] = [
  'modelKey',
  'providerKey',
  'displayName',
  'maxOutputTokens',
  'displayOrder',
];

function isWhole(value: string, min: number, max: number): boolean {
  if (!/^\d+$/.test(value.trim())) return false;
  const n = Number(value);
  return n >= min && n <= max;
}

/** Client-side checks that mirror the request limits; the server stays the arbiter. */
function validate(values: FormValues, t: TFunction): FieldErrors {
  const errors: FieldErrors = {};
  const required = t('providers.form.errors.required');
  const tooLong = (max: number) => t('providers.form.errors.tooLong', { max });
  if (values.modelKey.trim() === '') errors.modelKey = required;
  else if (values.modelKey.trim().length > MAX_KEY) errors.modelKey = tooLong(MAX_KEY);
  if (values.providerKey.trim() === '') errors.providerKey = required;
  else if (values.providerKey.trim().length > MAX_PROVIDER)
    errors.providerKey = tooLong(MAX_PROVIDER);
  if (values.displayName.trim() === '') errors.displayName = required;
  else if (values.displayName.trim().length > MAX_KEY) errors.displayName = tooLong(MAX_KEY);
  if (!isWhole(values.maxOutputTokens, 1, 8192)) {
    errors.maxOutputTokens = t('providers.form.errors.tokens');
  }
  if (!isWhole(values.displayOrder, 0, 32767)) {
    errors.displayOrder = t('providers.form.errors.order');
  }
  return errors;
}

function initialValues(entry: AiModel | undefined, providerKeys: readonly string[]): FormValues {
  if (entry) {
    return {
      modelKey: entry.modelKey,
      providerKey: entry.providerKey,
      displayName: entry.displayName,
      maxOutputTokens: String(entry.maxOutputTokens),
      displayOrder: String(entry.displayOrder),
      supportsVision: entry.supportsVision,
      supportsAudio: entry.supportsAudio,
      active: entry.active,
    };
  }
  return {
    modelKey: '',
    providerKey: providerKeys[0] ?? '',
    displayName: '',
    maxOutputTokens: DEFAULT_TOKENS,
    displayOrder: '0',
    supportsVision: false,
    supportsAudio: false,
    active: true,
  };
}

/** The problem body's per-field messages, for the fields this form has. */
function serverFieldErrors(error: unknown): FieldErrors {
  if (!(error instanceof ApiError) || !error.problem?.errors) return {};
  const result: FieldErrors = {};
  for (const name of FIELD_NAMES) {
    const message = error.problem.errors[name];
    if (message) result[name] = message;
  }
  return result;
}

interface ModelFormProps {
  /** The entry being edited; absent when adding. */
  readonly entry?: AiModel | undefined;
  /** Keys of the registered text providers, offered as the provider choice. */
  readonly providerKeys: readonly string[];
  readonly onSaved: (saved: AiModel, created: boolean) => void;
  readonly onClose: () => void;
}

/** Dialog to add a model (id picked from the provider's live list or typed) or to edit one (id fixed). */
export function ModelForm({ entry, providerKeys, onSaved, onClose }: ModelFormProps) {
  const { t } = useTranslation();
  const adding = entry === undefined;
  const dialogRef = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const baseId = useId();
  const listId = `${baseId}-vendor`;
  const [values, setValues] = useState<FormValues>(() => initialValues(entry, providerKeys));
  const [clientErrors, setClientErrors] = useState<FieldErrors>({});
  const create = useCreateModelMutation();
  const replace = useReplaceModelMutation();
  const mutation = adding ? create : replace;
  const vendorProvider = values.providerKey.trim();
  const vendor = useVendorModels(vendorProvider, adding && providerKeys.includes(vendorProvider));

  useEffect(() => {
    const dialog = dialogRef.current;
    if (dialog && !dialog.open) dialog.showModal();
  }, []);

  const serverErrors = serverFieldErrors(mutation.error);
  const problem = mutation.error instanceof ApiError ? mutation.error.problem : undefined;
  const fieldErrors: FieldErrors = { ...serverErrors, ...clientErrors };
  const formError =
    mutation.error && Object.keys(serverErrors).length === 0
      ? (problem?.detail ?? t('providers.form.errors.generic'))
      : undefined;

  const set = <K extends keyof FormValues>(name: K, value: FormValues[K]) => {
    setValues((current) => ({ ...current, [name]: value }));
    mutation.reset();
    if (name in clientErrors) {
      setClientErrors((current) => ({ ...current, [name]: undefined }));
    }
  };

  const pickModelKey = (modelKey: string) => {
    const known = vendor.data?.models?.find((model) => model.id === modelKey);
    setValues((current) => ({
      ...current,
      modelKey,
      displayName:
        known && current.displayName.trim() === ''
          ? (known.displayName ?? known.id ?? '')
          : current.displayName,
    }));
    mutation.reset();
    setClientErrors((current) => ({ ...current, modelKey: undefined }));
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (mutation.isPending) return;
    const errors = validate(values, t);
    setClientErrors(errors);
    if (Object.values(errors).some(Boolean)) return;
    const body: AiModelRequest = {
      modelKey: values.modelKey.trim(),
      providerKey: values.providerKey.trim(),
      displayName: values.displayName.trim(),
      maxOutputTokens: Number(values.maxOutputTokens),
      displayOrder: Number(values.displayOrder),
      supportsVision: values.supportsVision,
      supportsAudio: values.supportsAudio,
      active: values.active,
    };
    mutation.mutate(body, { onSuccess: (saved) => onSaved(saved, adding) });
  };

  const fieldProps = (name: FieldName) => {
    const error = fieldErrors[name];
    return {
      id: `${baseId}-${name}`,
      'aria-invalid': error ? true : undefined,
      'aria-describedby': error ? `${baseId}-${name}-error` : undefined,
    };
  };
  const fieldError = (name: FieldName) =>
    fieldErrors[name] ? (
      <p id={`${baseId}-${name}-error`} className="cortex-admin-form-error">
        {fieldErrors[name]}
      </p>
    ) : null;

  const vendorModels = vendor.data?.models ?? [];
  const vendorFailure = vendor.isError ? 'upstream' : vendor.data?.failure;
  let vendorNote: string | null = null;
  if (adding && providerKeys.includes(vendorProvider)) {
    if (vendor.isPending) vendorNote = t('providers.form.vendorLoading');
    else if (vendorFailure) vendorNote = t('providers.form.vendorFailed', { code: vendorFailure });
    else if (vendorModels.length === 0) vendorNote = t('providers.form.vendorNone');
  }

  return (
    <dialog
      ref={dialogRef}
      className="cortex-admin-account-dialog providers-dialog"
      aria-labelledby={titleId}
      onClose={onClose}
      onClick={(event) => {
        if (event.target === event.currentTarget) dialogRef.current?.close();
      }}
    >
      <form className="providers-dialog__body" onSubmit={submit} noValidate>
        <h2 id={titleId}>
          {adding
            ? t('providers.form.addTitle')
            : t('providers.form.editTitle', { name: entry.displayName })}
        </h2>

        <div className="cortex-admin-field">
          <label htmlFor={`${baseId}-providerKey`}>{t('providers.form.provider')}</label>
          {providerKeys.length > 0 ? (
            <select
              {...fieldProps('providerKey')}
              value={values.providerKey}
              disabled={!adding}
              onChange={(event) => set('providerKey', event.target.value)}
            >
              {!providerKeys.includes(values.providerKey) && (
                <option value={values.providerKey}>{values.providerKey}</option>
              )}
              {providerKeys.map((key) => (
                <option key={key} value={key}>
                  {key}
                </option>
              ))}
            </select>
          ) : (
            <input
              {...fieldProps('providerKey')}
              value={values.providerKey}
              readOnly={!adding}
              maxLength={MAX_PROVIDER}
              onChange={(event) => set('providerKey', event.target.value)}
            />
          )}
          {fieldError('providerKey')}
        </div>

        <div className="cortex-admin-field">
          <label htmlFor={`${baseId}-modelKey`}>{t('providers.form.modelKey')}</label>
          <input
            {...fieldProps('modelKey')}
            value={values.modelKey}
            list={adding ? listId : undefined}
            readOnly={!adding}
            autoComplete="off"
            spellCheck={false}
            maxLength={MAX_KEY}
            onChange={(event) => pickModelKey(event.target.value)}
          />
          {adding && (
            <datalist id={listId}>
              {vendorModels.map((model) => (
                <option key={model.id} value={model.id}>
                  {model.displayName}
                </option>
              ))}
            </datalist>
          )}
          {adding && <small className="providers-hint">{t('providers.form.modelKeyHint')}</small>}
          {vendorNote && (
            <p role={vendorFailure ? 'alert' : 'status'} className="providers-note">
              {vendorNote}
            </p>
          )}
          {fieldError('modelKey')}
        </div>

        <div className="cortex-admin-field">
          <label htmlFor={`${baseId}-displayName`}>{t('providers.form.displayName')}</label>
          <input
            {...fieldProps('displayName')}
            value={values.displayName}
            maxLength={MAX_KEY}
            onChange={(event) => set('displayName', event.target.value)}
          />
          {fieldError('displayName')}
        </div>

        <div className="providers-dialog__pair">
          <div className="cortex-admin-field">
            <label htmlFor={`${baseId}-maxOutputTokens`}>
              {t('providers.form.maxOutputTokens')}
            </label>
            <input
              {...fieldProps('maxOutputTokens')}
              type="number"
              inputMode="numeric"
              min={1}
              max={8192}
              value={values.maxOutputTokens}
              onChange={(event) => set('maxOutputTokens', event.target.value)}
            />
            {fieldError('maxOutputTokens')}
          </div>
          <div className="cortex-admin-field">
            <label htmlFor={`${baseId}-displayOrder`}>{t('providers.form.displayOrder')}</label>
            <input
              {...fieldProps('displayOrder')}
              type="number"
              inputMode="numeric"
              min={0}
              max={32767}
              value={values.displayOrder}
              onChange={(event) => set('displayOrder', event.target.value)}
            />
            {fieldError('displayOrder')}
          </div>
        </div>

        <div className="providers-dialog__checks">
          <label className="providers-check">
            <input
              type="checkbox"
              checked={values.supportsVision}
              onChange={(event) => set('supportsVision', event.target.checked)}
            />
            {t('providers.form.supportsVision')}
          </label>
          <label className="providers-check">
            <input
              type="checkbox"
              checked={values.supportsAudio}
              onChange={(event) => set('supportsAudio', event.target.checked)}
            />
            {t('providers.form.supportsAudio')}
          </label>
          <label className="providers-check">
            <input
              type="checkbox"
              checked={values.active}
              onChange={(event) => set('active', event.target.checked)}
            />
            {t('providers.form.active')}
          </label>
        </div>

        {formError && (
          <p className="cortex-admin-form-error" role="alert">
            {formError}
          </p>
        )}
        <div className="providers-dialog__actions">
          <button
            type="button"
            className="cortex-admin-btn"
            onClick={() => dialogRef.current?.close()}
          >
            {t('providers.form.cancel')}
          </button>
          <button
            type="submit"
            className="cortex-admin-btn cortex-admin-btn--primary"
            disabled={mutation.isPending}
          >
            {mutation.isPending ? t('providers.form.saving') : t('providers.form.save')}
          </button>
        </div>
      </form>
    </dialog>
  );
}
