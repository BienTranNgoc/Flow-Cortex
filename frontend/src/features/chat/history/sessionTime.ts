export type Translate = (key: string, options?: Record<string, unknown>) => string;

const pad = (value: number) => String(value).padStart(2, '0');

function sameDay(a: Date, b: Date): boolean {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
}

/**
 * Row time label in the device time zone on a 24-hour clock: HH:mm today (prefixed in the Pinned
 * group), Yesterday, dd/mm earlier this year, dd/mm/yyyy in another year. `t` is the chat-namespace
 * translator and supplies `row.time.todayAt` (with `{{time}}`) and `row.time.yesterday`.
 */
export function formatConversationTime(iso: string, now: Date, t: Translate, pinned: boolean): string {
  const at = new Date(iso);
  if (sameDay(at, now)) {
    const time = `${pad(at.getHours())}:${pad(at.getMinutes())}`;
    return pinned ? t('row.time.todayAt', { time }) : time;
  }
  const yesterday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1);
  if (sameDay(at, yesterday)) return t('row.time.yesterday');
  const dayMonth = `${pad(at.getDate())}/${pad(at.getMonth() + 1)}`;
  return at.getFullYear() === now.getFullYear() ? dayMonth : `${dayMonth}/${at.getFullYear()}`;
}
