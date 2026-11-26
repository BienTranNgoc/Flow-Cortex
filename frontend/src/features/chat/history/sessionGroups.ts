export type TimeGroup = 'today' | 'last7' | 'earlier';
export type SortOrder = 'newest' | 'oldest';

export interface DayBoundaries {
  todayStart: Date;
  weekStart: Date;
}

export interface ConversationGroup<T> {
  group: TimeGroup;
  items: T[];
}

/**
 * Local midnight of `now` and local midnight 7 calendar days before it. Calendar arithmetic, not
 * 7 x 24 h, so a daylight-saving change inside the window keeps both at midnight. The same two
 * instants go to the counts request, which is what keeps the group sizes equal to the rows shown.
 */
export function dayBoundaries(now: Date): DayBoundaries {
  const year = now.getFullYear();
  const month = now.getMonth();
  const day = now.getDate();
  return {
    todayStart: new Date(year, month, day),
    weekStart: new Date(year, month, day - 7),
  };
}

export function timeGroupOf(lastActivityAt: string, boundaries: DayBoundaries): TimeGroup {
  const at = new Date(lastActivityAt).getTime();
  if (at >= boundaries.todayStart.getTime()) return 'today';
  if (at >= boundaries.weekStart.getTime()) return 'last7';
  return 'earlier';
}

/** Splits rows into time groups, keeping the given row order; empty groups are dropped. */
export function groupConversations<T extends { lastActivityAt: string }>(
  items: readonly T[],
  boundaries: DayBoundaries,
  sort: SortOrder,
): ConversationGroup<T>[] {
  const buckets: Record<TimeGroup, T[]> = { today: [], last7: [], earlier: [] };
  for (const item of items) buckets[timeGroupOf(item.lastActivityAt, boundaries)].push(item);
  const order: TimeGroup[] = sort === 'oldest' ? ['earlier', 'last7', 'today'] : ['today', 'last7', 'earlier'];
  return order.filter((group) => buckets[group].length > 0).map((group) => ({ group, items: buckets[group] }));
}
