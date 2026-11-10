import { applySignal, extendRateLimit, networkBannerMode, rateLimitNoticeMs } from './networkStatus';

const NOW = 1_000_000;

function state(overrides: Partial<Parameters<typeof networkBannerMode>[0]> = {}) {
  return { online: true, rateLimitedUntil: 0, downStreams: new Set<number>(), ...overrides };
}

describe('networkBannerMode', () => {
  it('shows offline even with a live rate limit and a down stream', () => {
    const mode = networkBannerMode(
      state({ online: false, rateLimitedUntil: NOW + 5000, downStreams: new Set([1]) }),
      NOW,
    );
    expect(mode).toBe('offline');
  });

  it('shows rate-limited over a down stream while online', () => {
    const mode = networkBannerMode(
      state({ rateLimitedUntil: NOW + 5000, downStreams: new Set([1]) }),
      NOW,
    );
    expect(mode).toBe('rate-limited');
  });

  it('shows reconnecting with only a down stream', () => {
    expect(networkBannerMode(state({ downStreams: new Set([1]) }), NOW)).toBe('reconnecting');
  });

  it('shows nothing when nothing holds, and when the notice ends exactly now', () => {
    expect(networkBannerMode(state(), NOW)).toBeNull();
    expect(networkBannerMode(state({ rateLimitedUntil: NOW }), NOW)).toBeNull();
  });
});

describe('rateLimitNoticeMs', () => {
  it('lasts the named wait, at least 4 seconds, and none past 60 seconds', () => {
    expect(rateLimitNoticeMs(null)).toBe(4000);
    expect(rateLimitNoticeMs(2)).toBe(4000);
    expect(rateLimitNoticeMs(20)).toBe(20000);
    expect(rateLimitNoticeMs(60)).toBe(60000);
    expect(rateLimitNoticeMs(61)).toBeNull();
    expect(rateLimitNoticeMs(2520)).toBeNull();
  });
});

describe('extendRateLimit', () => {
  it('keeps the later end and ignores a quota-length wait', () => {
    expect(extendRateLimit(0, 20, NOW)).toBe(NOW + 20000);
    expect(extendRateLimit(NOW + 30000, 5, NOW)).toBe(NOW + 30000);
    expect(extendRateLimit(0, 2520, NOW)).toBe(0);
  });
});

describe('applySignal', () => {
  it('adds a down stream, removes it when it is up, and ignores an unknown id', () => {
    const down = applySignal(new Set<number>(), { type: 'stream-down', streamId: 1 });
    expect([...down]).toEqual([1]);
    expect([...applySignal(down, { type: 'stream-up', streamId: 1 })]).toEqual([]);
    expect(applySignal(down, { type: 'stream-up', streamId: 9 })).toBe(down);
  });
});
