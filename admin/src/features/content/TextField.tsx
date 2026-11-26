import { useId } from 'react';
import { useTranslation } from 'react-i18next';
import { formatNumber } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import { characterCount, type ContentLocale } from './documentModel';

interface TextFieldProps {
  readonly label: string;
  readonly value: string;
  /** The language of the text; absent for a value both languages share. */
  readonly locale?: ContentLocale | undefined;
  /** Characters allowed; the counter shows the count against it and the field is invalid above it. */
  readonly max?: number | undefined;
  readonly rows?: number | undefined;
  readonly type?: 'text' | 'date' | undefined;
  readonly placeholder?: string | undefined;
  /** A line of help under the field. */
  readonly hint?: string | undefined;
  readonly readOnly: boolean;
  /** The API's message for this field, shown until the next save. */
  readonly error?: string | undefined;
  readonly onChange: (value: string) => void;
}

/** One language of a localized text: a single-line input, or a textarea when `rows` is given. */
export function TextField({
  label,
  value,
  locale,
  max,
  rows,
  type = 'text',
  placeholder,
  hint,
  readOnly,
  error,
  onChange,
}: TextFieldProps) {
  const { t } = useTranslation();
  const id = useId();
  const counterId = `${id}-count`;
  const errorId = `${id}-error`;
  const hintId = `${id}-hint`;
  const count = characterCount(value);
  const over = max !== undefined && count > max;
  const shownMax = max === undefined ? '' : formatNumber(max, activeLocale());
  const message = error ?? (over ? t('content.editor.tooLong', { max: shownMax }) : undefined);
  const describedBy = [
    hint ? hintId : undefined,
    max === undefined ? undefined : counterId,
    message ? errorId : undefined,
  ]
    .filter(Boolean)
    .join(' ');
  const common = {
    id,
    value,
    lang: locale,
    placeholder,
    readOnly,
    'aria-invalid': message ? true : undefined,
    'aria-describedby': describedBy || undefined,
    onChange: (event: { target: { value: string } }) => onChange(event.target.value),
  };

  return (
    <div className="cortex-admin-field content-field">
      <label htmlFor={id}>{label}</label>
      {rows === undefined ? (
        <input type={type} {...common} />
      ) : (
        <textarea rows={rows} {...common} />
      )}
      {hint && (
        <small id={hintId} className="content-hint">
          {hint}
        </small>
      )}
      {max !== undefined && (
        <small
          id={counterId}
          className={over ? 'content-count content-count--over' : 'content-count'}
        >
          {t('content.editor.count', {
            current: formatNumber(count, activeLocale()),
            max: shownMax,
          })}
        </small>
      )}
      {message && (
        <p id={errorId} className="cortex-admin-form-error" role="alert">
          {message}
        </p>
      )}
    </div>
  );
}
