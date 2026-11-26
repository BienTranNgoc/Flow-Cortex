const DAY_MS = 24 * 60 * 60 * 1000;

/**
 * Whole days until a deletion deadline, rounded up: one second left is still 1 day. It is 0 at or
 * past the deadline, and for a value that is not a date.
 */
export function daysLeft(scheduledAt: string, now: Date): number {
  const remaining = new Date(scheduledAt).getTime() - now.getTime();
  return remaining > 0 ? Math.ceil(remaining / DAY_MS) : 0;
}
