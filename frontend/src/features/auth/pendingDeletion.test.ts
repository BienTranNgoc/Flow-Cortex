import { daysLeft } from './pendingDeletion';

const NOW = new Date('2026-11-01T09:00:00Z');
const HOUR = 3_600_000;
const DAY = 24 * HOUR;

describe('daysLeft', () => {
  it('rounds 12 days and 3 hours up to 13', () => {
    const scheduledAt = new Date(NOW.getTime() + 12 * DAY + 3 * HOUR).toISOString();
    expect(daysLeft(scheduledAt, NOW)).toBe(13);
  });

  it('counts exactly one day as 1', () => {
    expect(daysLeft(new Date(NOW.getTime() + DAY).toISOString(), NOW)).toBe(1);
  });

  it('counts one second left as 1', () => {
    expect(daysLeft(new Date(NOW.getTime() + 1000).toISOString(), NOW)).toBe(1);
  });

  it('is 0 at the deadline and after it', () => {
    expect(daysLeft(NOW.toISOString(), NOW)).toBe(0);
    expect(daysLeft(new Date(NOW.getTime() - 5 * DAY).toISOString(), NOW)).toBe(0);
  });

  it('is 0 for an unreadable deadline', () => {
    expect(daysLeft('not a date', NOW)).toBe(0);
  });
});
