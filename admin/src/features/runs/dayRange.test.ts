import { dayRangeToInstants, isCalendarDay, isRangeReversed } from './dayRange';

describe('dayRangeToInstants', () => {
  it('maps the chosen days to Asia/Ho_Chi_Minh day edges and includes the last day whole', () => {
    expect(dayRangeToInstants('2026-11-02', '2026-11-03')).toEqual({
      from: '2026-11-01T17:00:00Z',
      to: '2026-11-03T17:00:00Z',
    });
  });

  it('does not depend on the browser zone', () => {
    const original = process.env.TZ;
    process.env.TZ = 'America/Los_Angeles';
    try {
      expect(dayRangeToInstants('2026-11-02', '2026-11-02')).toEqual({
        from: '2026-11-01T17:00:00Z',
        to: '2026-11-02T17:00:00Z',
      });
    } finally {
      if (original === undefined) delete process.env.TZ;
      else process.env.TZ = original;
    }
  });

  it('carries the end day across a month and a year boundary', () => {
    expect(dayRangeToInstants(undefined, '2026-12-31')).toEqual({ to: '2026-12-31T17:00:00Z' });
    expect(dayRangeToInstants('2028-02-29', undefined)).toEqual({ from: '2028-02-28T17:00:00Z' });
  });

  it('leaves an open end out and ignores a value that is not a calendar day', () => {
    expect(dayRangeToInstants(undefined, undefined)).toEqual({});
    expect(dayRangeToInstants('2026-13-01', '2026-02-30')).toEqual({});
    expect(dayRangeToInstants('yesterday', '')).toEqual({});
  });
});

describe('isCalendarDay', () => {
  it('accepts a real day and refuses anything else', () => {
    expect(isCalendarDay('2026-11-02')).toBe(true);
    expect(isCalendarDay('2026-02-30')).toBe(false);
    expect(isCalendarDay('11/02/2026')).toBe(false);
    expect(isCalendarDay(undefined)).toBe(false);
  });
});

describe('isRangeReversed', () => {
  it('is true only when the start day is after the end day', () => {
    expect(isRangeReversed('2026-11-03', '2026-11-02')).toBe(true);
    expect(isRangeReversed('2026-11-02', '2026-11-02')).toBe(false);
    expect(isRangeReversed(undefined, '2026-11-02')).toBe(false);
  });
});
