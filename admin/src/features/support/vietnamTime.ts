import { formatDateTime } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';

const OPTIONS: Intl.DateTimeFormatOptions = {
  timeZone: 'Asia/Ho_Chi_Minh',
  hourCycle: 'h23',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
};

/**
 * A timestamp in Vietnam time on a 24-hour clock, ordered by the active language, whatever the
 * browser's zone. Callers re-render on a language switch through `useTranslation()`.
 */
export function formatVietnamTime(value: string): string {
  return formatDateTime(value, activeLocale(), OPTIONS);
}
