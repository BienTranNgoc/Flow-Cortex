import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { NAME_LIMIT, type NamePair, type TaxonomyKind } from './useTrendTaxonomy';
import type { NameField, TrendFailure } from './trendProblem';

const FIELDS: readonly NameField[] = ['nameVi', 'nameEn'];

interface TaxonomyEntryDialogProps {
  readonly kind: TaxonomyKind;
  /** The record being edited; absent when adding. */
  readonly entry?:
    | { readonly nameVi: string; readonly nameEn: string; readonly key?: string }
    | undefined;
  /** Resolves with the refusal to show in place, or `null` once the write went through. */
  readonly onSubmit: (names: NamePair) => Promise<TrendFailure | null>;
  readonly onClose: () => void;
}

type FieldErrors = Partial<Record<NameField, string | undefined>>;

/** Modal holding both names of a category or tag; it stays open on a refusal so the reason is read at the field. */
export function TaxonomyEntryDialog({ kind, entry, onSubmit, onClose }: TaxonomyEntryDialogProps) {
  const { t } = useTranslation();
  const max = NAME_LIMIT[kind];
  const titleId = useId();
  const baseId = useId();
  const dialogRef = useRef<HTMLDialogElement>(null);
  const inputs = useRef<Record<NameField, HTMLInputElement | null>>({ nameVi: null, nameEn: null });
  const [values, setValues] = useState<NamePair>({
    nameVi: entry?.nameVi ?? '',
    nameEn: entry?.nameEn ?? '',
  });
  const [errors, setErrors] = useState<FieldErrors>({});
  const [message, setMessage] = useState<string | undefined>();
  const [pending, setPending] = useState(false);
  const inputId = (field: NameField) => `${baseId}-${field}`;

  useEffect(() => {
    const dialog = dialogRef.current;
    if (dialog && !dialog.open) dialog.showModal();
  }, []);

  const focusFirstInvalid = (invalid: FieldErrors) => {
    const first = FIELDS.find((field) => invalid[field] !== undefined);
    if (first) inputs.current[first]?.focus();
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (pending) return;
    const names: NamePair = { nameVi: values.nameVi.trim(), nameEn: values.nameEn.trim() };
    const invalid: FieldErrors = {};
    for (const field of FIELDS) {
      if (names[field].length < 1 || names[field].length > max) {
        invalid[field] = t('trend.dialog.errors.name', { max });
      }
    }
    setErrors(invalid);
    setMessage(undefined);
    if (Object.keys(invalid).length > 0) {
      focusFirstInvalid(invalid);
      return;
    }
    setPending(true);
    const failure = await onSubmit(names);
    setPending(false);
    if (failure === null) return;
    setErrors(failure.fields);
    setMessage(failure.message);
    focusFirstInvalid(failure.fields);
  };

  const edit = (field: NameField, value: string) => {
    setValues((current) => ({ ...current, [field]: value }));
    setErrors((current) => ({ ...current, [field]: undefined }));
  };

  const titleKey = `trend.dialog.${entry ? 'edit' : 'add'}${kind === 'category' ? 'Category' : 'Tag'}`;

  return (
    <dialog
      ref={dialogRef}
      className="trend-dialog"
      aria-labelledby={titleId}
      onClose={onClose}
    >
      <form className="trend-dialog__body" onSubmit={submit} noValidate>
        <h2 id={titleId}>{t(titleKey)}</h2>
        {FIELDS.map((field) => {
          const error = errors[field];
          return (
            <div className="cortex-admin-field" key={field}>
              <label htmlFor={inputId(field)}>{t(`trend.dialog.${field}`)}</label>
              <input
                id={inputId(field)}
                ref={(node) => {
                  inputs.current[field] = node;
                }}
                value={values[field]}
                aria-invalid={error ? true : undefined}
                aria-describedby={`${inputId(field)}-hint${error ? ` ${inputId(field)}-error` : ''}`}
                onChange={(event) => edit(field, event.target.value)}
              />
              <p id={`${inputId(field)}-hint`} className="trend-hint">
                {t('trend.dialog.hint', { max })}
              </p>
              {error && (
                <p id={`${inputId(field)}-error`} className="cortex-admin-form-error" role="alert">
                  {error}
                </p>
              )}
            </div>
          );
        })}
        {entry?.key !== undefined && (
          <div className="trend-key">
            <span className="trend-key__label">{t('trend.dialog.key')}</span>
            <code>{entry.key}</code>
            <p className="trend-hint">{t('trend.dialog.keyHint')}</p>
          </div>
        )}
        {message && (
          <p className="cortex-admin-form-error" role="alert">
            {message}
          </p>
        )}
        <div className="trend-dialog__actions">
          <button type="button" className="cortex-admin-btn" onClick={() => dialogRef.current?.close()}>
            {t('trend.dialog.cancel')}
          </button>
          <button
            type="submit"
            className="cortex-admin-btn cortex-admin-btn--primary"
            disabled={pending}
          >
            {pending ? t('trend.dialog.saving') : t('trend.dialog.save')}
          </button>
        </div>
      </form>
    </dialog>
  );
}
