import type { Locale } from '@flow-cortex/i18n';
import { formatNumber } from '../../../lib/format';

const UNITS = ['B', 'KB', 'MB', 'GB'] as const;

/** A file size in binary units with at most one decimal, e.g. `1.5 MB`. */
export function formatBytes(bytes: number, locale: Locale): string {
  let value = bytes;
  let unit = 0;
  while (unit < UNITS.length - 1 && Math.round(value * 10) / 10 >= 1024) {
    value /= 1024;
    unit += 1;
  }
  return `${formatNumber(value, locale, { maximumFractionDigits: unit === 0 ? 0 : 1 })} ${UNITS[unit]}`;
}
