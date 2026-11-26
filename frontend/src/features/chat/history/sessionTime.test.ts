import { formatConversationTime } from './sessionTime';

const t = (key: string, options?: Record<string, unknown>) =>
  options?.time ? `[${key}] ${String(options.time)}` : `[${key}]`;

describe('formatConversationTime', () => {
  const now = new Date(2026, 10, 8, 15, 0);
  const at = (y: number, m: number, d: number, h = 10, min = 0) => new Date(y, m - 1, d, h, min).toISOString();

  it('reads HH:mm for today on a 24-hour clock', () => {
    expect(formatConversationTime(at(2026, 11, 8, 9, 5), now, t, false)).toBe('09:05');
    expect(formatConversationTime(at(2026, 11, 8, 0, 0), now, t, false)).toBe('00:00');
    expect(formatConversationTime(at(2026, 11, 8, 18, 30), now, t, false)).toBe('18:30');
  });

  it('prefixes today with the translated word in the pinned group', () => {
    expect(formatConversationTime(at(2026, 11, 8, 9, 5), now, t, true)).toBe('[row.time.todayAt] 09:05');
  });

  it('reads the translated Yesterday for the previous calendar day, pinned or not', () => {
    expect(formatConversationTime(at(2026, 11, 7, 23, 59), now, t, false)).toBe('[row.time.yesterday]');
    expect(formatConversationTime(at(2026, 11, 7, 0, 0), now, t, true)).toBe('[row.time.yesterday]');
  });

  it('reads dd/mm earlier this year', () => {
    expect(formatConversationTime(at(2026, 3, 1), now, t, false)).toBe('01/03');
    expect(formatConversationTime(at(2026, 11, 6, 23, 59), now, t, true)).toBe('06/11');
  });

  it('reads dd/mm/yyyy in another year', () => {
    expect(formatConversationTime(at(2025, 12, 31), now, t, false)).toBe('31/12/2025');
  });

  it('treats the previous calendar day across a month and year boundary as Yesterday', () => {
    expect(formatConversationTime(at(2026, 11, 30, 12), new Date(2026, 11, 1, 8, 0), t, false)).toBe(
      '[row.time.yesterday]',
    );
    expect(formatConversationTime(at(2025, 12, 31, 12), new Date(2026, 0, 1, 8, 0), t, false)).toBe(
      '[row.time.yesterday]',
    );
  });
});
