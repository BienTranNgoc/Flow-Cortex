import { useTranslation } from 'react-i18next';
import { formatVietnamTime } from '../vietnamTime';

/** A request or article timestamp in Vietnam time, in the active language's date order. */
export function RequestTime({ value }: Readonly<{ value: string }>) {
  // Subscribes the cell to language switches; activeLocale() alone would not re-render it.
  useTranslation();
  return <time dateTime={value}>{formatVietnamTime(value)}</time>;
}
