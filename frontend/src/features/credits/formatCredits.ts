import { formatNumber } from '../../lib/format';
import type { Locale } from '../../lib/i18n';

/** "1,000" in English, "1.000" in Vietnamese — credit amounts read the same everywhere in the app. */
export function formatCredits(amount: number, locale: Locale): string {
  return formatNumber(amount, locale);
}
