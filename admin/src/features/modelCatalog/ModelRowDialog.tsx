import {
  ApiError,
  type CatalogModel,
  type CatalogModelCreateRequest,
  type CatalogModelReplaceRequest,
} from '@flow-cortex/api-client';
import type { TFunction } from 'i18next';
import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import {
  MAX_LABEL,
  MAX_MODEL_KEY,
  MODEL_KEY_PATTERN,
  MODES,
  PROVIDER_KEY_PATTERN,
} from './catalogOptions';
import {
  useCreateCatalogModelMutation,
  useModelDescriptors,
  useReplaceCatalogModelMutation,
} from './useModelCatalog';

interface FormValues {
  modelKey: string;
  providerKey: string;
  mode: string;
  vendorLabel: string;
  makerLabel: string;
  enabled: boolean;
  notImplemented: boolean;
}

type FieldName = 'modelKey' | 'providerKey' | 'mode' | 'vendorLabel' | 'makerLabel';
type FieldErrors = Partial<Record<FieldName, string | undefined>>;

const FIELD_NAMES: readonly FieldName[] = [
  'modelKey',
  'providerKey',
  'mode',
  'vendorLabel',
  'makerLabel',
];

/** Client-side checks that mirror the request limits; the server stays the arbiter. */
function validate(values: FormValues, t: TFunction): FieldErrors {
  const errors: FieldErrors = {};
  const required = t('modelCatalog.form.errors.required');
  const tooLong = (max: number) => t('modelCatalog.form.errors.tooLong', { max });
  const modelKey = values.modelKey.trim();
  if (modelKey === '') errors.modelKey = required;
  else if (modelKey.length > MAX_MODEL_KEY) errors.modelKey = tooLong(MAX_MODEL_KEY);
  else if (!MODEL_KEY_PATTERN.test(modelKey))
    errors.modelKey = t('modelCatalog.form.errors.modelKey');
  const providerKey = values.providerKey.trim();
  if (providerKey === '') errors.providerKey = required;
  else if (!PROVIDER_KEY_PATTERN.test(providerKey)) {
    errors.providerKey = t('modelCatalog.form.errors.providerKey');
  }
  if (values.mode === '') errors.mode = required;
  if (values.vendorLabel.trim().length > MAX_LABEL) errors.vendorLabel = tooLong(MAX_LABEL);
  if (values.makerLabel.trim().length > MAX_LABEL) errors.makerLabel = tooLong(MAX_LABEL);
  return errors;
}

function initialValues(entry: CatalogModel | undefined): FormValues {
  if (entry) {
    return {
      modelKey: entry.modelKey,
      providerKey: entry.providerKey,
      mode: entry.mode,
      vendorLabel: entry.vendorLabel ?? '',
      makerLabel: entry.makerLabel ?? '',
      enabled: entry.enabled,
      notImplemented: entry.notImplemented,
    };
  }
  return {
    modelKey: '',
    providerKey: '',
    mode: 'text',
    vendorLabel: '',
    makerLabel: '',
    enabled: true,
    notImplemented: false,
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

interface ModelRowDialogProps {
  readonly capability: string;
  /** The row being edited; absent when adding. */
  readonly entry?: CatalogModel | undefined;
  readonly onSaved: (saved: CatalogModel, created: boolean) => void;
  readonly onClose: () => void;
}

/** Dialog to add a model row (text rows pick from the described models) or to edit one (key fixed). */
export function ModelRowDialog({ capability, entry, onSaved, onClose }: ModelRowDialogProps) {
  const { t } = useTranslation();
  const adding = entry === undefined;
  const dialogRef = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const baseId = useId();
  const [values, setValues] = useState<FormValues>(() => initialValues(entry));
  const [clientErrors, setClientErrors] = useState<FieldErrors>({});
  const create = useCreateCatalogModelMutation(capability);
  const replace = useReplaceCatalogModelMutation(capability);
  const mutation = adding ? create : replace;
  const textMode = values.mode === 'text';
  const descriptors = useModelDescriptors(textMode);
  const described = descriptors.data?.find(
    (descriptor) => descriptor.id === values.modelKey.trim(),
  );
  const pickable = (descriptors.data ?? []).filter(
    (descriptor) => descriptor.status !== 'deprecated' || descriptor.id === entry?.modelKey,
  );
  const usePicker = adding && textMode && descriptors.isSuccess;

  useEffect(() => {
    const dialog = dialogRef.current;
    if (dialog && !dialog.open) dialog.showModal();
  }, []);

  const serverErrors = serverFieldErrors(mutation.error);
  const problem = mutation.error instanceof ApiError ? mutation.error.problem : undefined;
  const fieldErrors: FieldErrors = { ...serverErrors, ...clientErrors };
  const formError =
    mutation.error && Object.keys(serverErrors).length === 0
      ? (problem?.detail ?? t('modelCatalog.form.errors.generic'))
      : undefined;

  const clearFieldError = (name: FieldName) => {
    mutation.reset();
    setClientErrors((current) => ({ ...current, [name]: undefined }));
  };

  const set = <K extends keyof FormValues>(name: K, value: FormValues[K]) => {
    setValues((current) => ({ ...current, [name]: value }));
    if (name in clientErrors) clearFieldError(name as FieldName);
    else mutation.reset();
  };

  const pickModelKey = (modelKey: string) => {
    const known = descriptors.data?.find((descriptor) => descriptor.id === modelKey);
    setValues((current) => ({
      ...current,
      modelKey,
      providerKey: known ? known.providerKey : current.providerKey,
    }));
    mutation.reset();
    setClientErrors((current) => ({ ...current, modelKey: undefined, providerKey: undefined }));
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (mutation.isPending) return;
    const errors = validate(values, t);
    setClientErrors(errors);
    if (Object.values(errors).some(Boolean)) return;
    const vendorLabel = values.vendorLabel.trim();
    const makerLabel = values.makerLabel.trim();
    const common: CatalogModelReplaceRequest = {
      providerKey: values.providerKey.trim(),
      mode: values.mode,
      enabled: values.enabled,
      notImplemented: values.notImplemented,
      ...(vendorLabel === '' ? {} : { vendorLabel }),
      ...(makerLabel === '' ? {} : { makerLabel }),
    };
    if (adding) {
      const body: CatalogModelCreateRequest = { ...common, modelKey: values.modelKey.trim() };
      create.mutate(body, { onSuccess: (saved) => onSaved(saved, true) });
    } else {
      replace.mutate(
        { modelKey: entry.modelKey, body: common },
        { onSuccess: (saved) => onSaved(saved, false) },
      );
    }
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

  const providerLocked = textMode && described !== undefined;
  let pickerNote: string | null = null;
  if (adding && textMode) {
    if (descriptors.isPending) pickerNote = t('modelCatalog.form.descriptorsLoading');
    else if (descriptors.isError) pickerNote = t('modelCatalog.form.descriptorsFailed');
    else if (pickable.length === 0) pickerNote = t('modelCatalog.form.descriptorsNone');
  }

  return (
    <dialog
      ref={dialogRef}
      className="cortex-admin-account-dialog model-catalog-dialog"
      aria-labelledby={titleId}
      onClose={onClose}
      onClick={(event) => {
        if (event.target === event.currentTarget) dialogRef.current?.close();
      }}
    >
      <form className="model-catalog-dialog__body" onSubmit={submit} noValidate>
        <h2 id={titleId}>
          {adding
            ? t('modelCatalog.form.addTitle', { capability })
            : t('modelCatalog.form.editTitle', { name: entry.modelKey })}
        </h2>

        <div className="cortex-admin-field">
          <label htmlFor={`${baseId}-mode`}>{t('modelCatalog.form.mode')}</label>
          <select
            {...fieldProps('mode')}
            value={values.mode}
            onChange={(event) => set('mode', event.target.value)}
          >
            {!MODES.some((mode) => mode === values.mode) && (
              <option value={values.mode}>{values.mode}</option>
            )}
            {MODES.map((mode) => (
              <option key={mode} value={mode}>
                {t(`modelCatalog.modes.${mode}`)}
              </option>
            ))}
          </select>
          {fieldError('mode')}
        </div>

        <div className="cortex-admin-field">
          <label htmlFor={`${baseId}-modelKey`}>{t('modelCatalog.form.modelKey')}</label>
          {usePicker ? (
            <select
              {...fieldProps('modelKey')}
              value={values.modelKey}
              onChange={(event) => pickModelKey(event.target.value)}
            >
              <option value="">{t('modelCatalog.form.modelKeyChoose')}</option>
              {pickable.map((descriptor) => (
                <option key={descriptor.id} value={descriptor.id}>
                  {descriptor.id}
                </option>
              ))}
            </select>
          ) : (
            <input
              {...fieldProps('modelKey')}
              value={values.modelKey}
              readOnly={!adding}
              autoComplete="off"
              spellCheck={false}
              maxLength={MAX_MODEL_KEY}
              onChange={(event) => set('modelKey', event.target.value)}
            />
          )}
          <small className="model-catalog-hint">
            {t(
              usePicker ? 'modelCatalog.form.modelKeyPickerHint' : 'modelCatalog.form.modelKeyHint',
            )}
          </small>
          {pickerNote && (
            <p
              role={descriptors.isError ? 'alert' : 'status'}
              className="model-catalog-note model-catalog-note--inline"
            >
              {pickerNote}
            </p>
          )}
          {fieldError('modelKey')}
        </div>

        <div className="cortex-admin-field">
          <label htmlFor={`${baseId}-providerKey`}>{t('modelCatalog.form.provider')}</label>
          <input
            {...fieldProps('providerKey')}
            value={values.providerKey}
            readOnly={providerLocked}
            autoComplete="off"
            spellCheck={false}
            onChange={(event) => set('providerKey', event.target.value)}
          />
          {providerLocked && (
            <small className="model-catalog-hint">
              {t('modelCatalog.form.providerFromDescriptor')}
            </small>
          )}
          {fieldError('providerKey')}
        </div>

        <div className="model-catalog-dialog__pair">
          <div className="cortex-admin-field">
            <label htmlFor={`${baseId}-vendorLabel`}>{t('modelCatalog.form.vendorLabel')}</label>
            <input
              {...fieldProps('vendorLabel')}
              value={values.vendorLabel}
              maxLength={MAX_LABEL}
              onChange={(event) => set('vendorLabel', event.target.value)}
            />
            {fieldError('vendorLabel')}
          </div>
          <div className="cortex-admin-field">
            <label htmlFor={`${baseId}-makerLabel`}>{t('modelCatalog.form.makerLabel')}</label>
            <input
              {...fieldProps('makerLabel')}
              value={values.makerLabel}
              maxLength={MAX_LABEL}
              onChange={(event) => set('makerLabel', event.target.value)}
            />
            {fieldError('makerLabel')}
          </div>
        </div>

        <div className="model-catalog-dialog__checks">
          <label className="model-catalog-check">
            <input
              type="checkbox"
              checked={values.enabled}
              onChange={(event) => set('enabled', event.target.checked)}
            />
            {t('modelCatalog.form.enabled')}
          </label>
          <label className="model-catalog-check">
            <input
              type="checkbox"
              checked={values.notImplemented}
              onChange={(event) => set('notImplemented', event.target.checked)}
            />
            {t('modelCatalog.form.notImplemented')}
          </label>
        </div>

        {formError && (
          <p className="cortex-admin-form-error" role="alert">
            {formError}
          </p>
        )}
        <div className="model-catalog-dialog__actions">
          <button
            type="button"
            className="cortex-admin-btn"
            onClick={() => dialogRef.current?.close()}
          >
            {t('modelCatalog.form.cancel')}
          </button>
          <button
            type="submit"
            className="cortex-admin-btn cortex-admin-btn--primary"
            disabled={mutation.isPending}
          >
            {mutation.isPending ? t('modelCatalog.form.saving') : t('modelCatalog.form.save')}
          </button>
        </div>
      </form>
    </dialog>
  );
}
