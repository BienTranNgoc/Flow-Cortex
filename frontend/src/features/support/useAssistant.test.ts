import { ApiError } from '@flow-cortex/api-client';
import { isMinuteCap } from './useAssistant';

function quota(retryAt: string) {
  return new ApiError(429, { status: 429, code: 'assistant-quota', retryAt } as never);
}

afterEach(() => {
  vi.useRealTimers();
});

describe('isMinuteCap', () => {
  it('reads the daily quota from a reset at 00:00 Vietnam time, whatever the client clock says', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-10-23T16:59:40Z'));

    expect(isMinuteCap(quota('2026-10-23T17:00:00Z'))).toBe(false);
  });

  it('reads the per-minute cap from any other reset time, even far from the client clock', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-10-23T08:00:00Z'));

    expect(isMinuteCap(quota('2030-01-01T05:00:20.123Z'))).toBe(true);
    expect(isMinuteCap(quota('2026-10-22T08:00:06Z'))).toBe(true);
  });

  it('treats a missing or unreadable reset time as the daily quota', () => {
    expect(isMinuteCap(new ApiError(429, { status: 429, code: 'assistant-quota' }))).toBe(false);
    expect(isMinuteCap(quota('soon'))).toBe(false);
  });
});
