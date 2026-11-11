import { formatNumber } from '../../lib/format';
import type { Locale } from '../../lib/i18n';

const UNITS = ['B', 'KB', 'MB', 'GB'] as const;

/** A file size in the largest unit that keeps the number under 1024, one decimal above bytes. */
export function formatBytes(bytes: number, locale: Locale): string {
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < UNITS.length - 1) {
    value /= 1024;
    unit += 1;
  }
  const rounded = unit === 0 ? value : Math.round(value * 10) / 10;
  return `${formatNumber(rounded, locale)} ${UNITS[unit]}`;
}
