/** Asia/Ho_Chi_Minh is UTC+7 all year (no daylight saving), so a day edge is a fixed offset from UTC. */
const DAY_ZONE_OFFSET_MS = 7 * 60 * 60 * 1000;
const DAY_MS = 24 * 60 * 60 * 1000;
const DAY = /^(\d{4})-(\d{2})-(\d{2})$/;

function dayStartMs(day: string | undefined): number | undefined {
  const match = day ? DAY.exec(day) : null;
  if (!match) return undefined;
  const [year, month, date] = [Number(match[1]), Number(match[2]), Number(match[3])];
  const utc = new Date(Date.UTC(year, month - 1, date));
  const real =
    utc.getUTCFullYear() === year && utc.getUTCMonth() === month - 1 && utc.getUTCDate() === date;
  return real ? utc.getTime() - DAY_ZONE_OFFSET_MS : undefined;
}

function instant(ms: number): string {
  return new Date(ms).toISOString().replace('.000Z', 'Z');
}

/**
 * The request's `from` (inclusive) and `to` (exclusive) for chosen calendar days `YYYY-MM-DD`:
 * `from` is the first day's 00:00 and `to` the day after the last day's 00:00, both in Nodi's day zone.
 * A missing or non-calendar value leaves that end open.
 */
export function dayRangeToInstants(
  fromDay: string | undefined,
  toDay: string | undefined,
): { from?: string; to?: string } {
  const from = dayStartMs(fromDay);
  const to = dayStartMs(toDay);
  return {
    ...(from === undefined ? {} : { from: instant(from) }),
    ...(to === undefined ? {} : { to: instant(to + DAY_MS) }),
  };
}

/** Whether `day` is a real calendar day written `YYYY-MM-DD`. */
export function isCalendarDay(day: string | undefined): day is string {
  return dayStartMs(day) !== undefined;
}

/** Whether both days are real and the start lies after the end, which the API refuses. */
export function isRangeReversed(fromDay: string | undefined, toDay: string | undefined): boolean {
  const from = dayStartMs(fromDay);
  const to = dayStartMs(toDay);
  return from !== undefined && to !== undefined && from > to;
}
