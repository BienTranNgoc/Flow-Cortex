const PERCENT = /^(\d{1,3})(?:[.,](\d{1,2}))?$/;
const MAX_BPS = 10_000;
const VAT_ZONE = 'Asia/Ho_Chi_Minh';

/** A row of the rate timeline; the range is half-open: `validFrom` is in, `validTo` is out. */
export interface RateRange {
  readonly validFrom: string;
  readonly validTo?: string | null;
}

/** A percentage with at most two decimals ("8", "8.5", "8,5") as whole basis points, or null when it is not one. */
export function percentToBps(input: string): number | null {
  const match = PERCENT.exec(input.trim());
  if (!match) return null;
  const bps = Number(match[1]) * 100 + Number((match[2] ?? '').padEnd(2, '0'));
  return bps <= MAX_BPS ? bps : null;
}

export function bpsToPercent(bps: number): string {
  return `${(bps / 100).toFixed(2)}%`;
}

/** The row that covers `day` (YYYY-MM-DD), or null when none does: nothing is guessed. */
export function currentRateOf<T extends RateRange>(rates: readonly T[], day: string): T | null {
  return rates.find((rate) => rate.validFrom <= day && (!rate.validTo || rate.validTo > day)) ?? null;
}

/** The calendar day in Vietnam, where a rate changes at midnight. */
export function vatToday(now: Date = new Date()): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: VAT_ZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(now);
  const part = (type: string) => parts.find((p) => p.type === type)?.value ?? '';
  return `${part('year')}-${part('month')}-${part('day')}`;
}
