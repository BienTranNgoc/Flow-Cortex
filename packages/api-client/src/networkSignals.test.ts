import { emitNetworkSignal, rateLimitWaitSeconds, subscribeNetworkSignals } from './networkSignals';
import type { NetworkSignal } from './networkSignals';

const NOW = Date.parse('2026-11-08T10:00:00Z');
const inSeconds = (s: number) => new Date(NOW + s * 1000).toUTCString();
const isoIn = (s: number) => new Date(NOW + s * 1000).toISOString();

describe('rateLimitWaitSeconds', () => {
  it('reads a positive retryAfterSeconds from the body', () => {
    expect(rateLimitWaitSeconds({ retryAfterSeconds: 20 }, null, NOW)).toBe(20);
  });

  it('falls through a zero or non-numeric retryAfterSeconds', () => {
    expect(rateLimitWaitSeconds({ retryAfterSeconds: 0 }, '15', NOW)).toBe(15);
    expect(rateLimitWaitSeconds({ retryAfterSeconds: '20' }, null, NOW)).toBeNull();
  });

  it('reads a future retryAt', () => {
    expect(rateLimitWaitSeconds({ retryAt: isoIn(30) }, null, NOW)).toBe(30);
  });

  it('falls through a retryAt in the past or unparseable', () => {
    expect(rateLimitWaitSeconds({ retryAt: isoIn(-30) }, '9', NOW)).toBe(9);
    expect(rateLimitWaitSeconds({ retryAt: 'soon' }, null, NOW)).toBeNull();
  });

  it('reads a seconds Retry-After header', () => {
    expect(rateLimitWaitSeconds(undefined, '15', NOW)).toBe(15);
  });

  it('reads an HTTP-date Retry-After header', () => {
    expect(rateLimitWaitSeconds({}, inSeconds(45), NOW)).toBe(45);
  });

  it('is null when nothing is readable', () => {
    expect(rateLimitWaitSeconds(undefined, null, NOW)).toBeNull();
    expect(rateLimitWaitSeconds('text', 'later', NOW)).toBeNull();
    expect(rateLimitWaitSeconds({}, inSeconds(-5), NOW)).toBeNull();
    expect(rateLimitWaitSeconds({}, '0', NOW)).toBeNull();
  });
});

describe('network signal channel', () => {
  it('delivers an emitted signal to a subscriber and stops after unsubscribe', () => {
    const received: NetworkSignal[] = [];
    const unsubscribe = subscribeNetworkSignals((signal) => received.push(signal));
    emitNetworkSignal({ type: 'stream-down', streamId: 1 });
    unsubscribe();
    emitNetworkSignal({ type: 'stream-up', streamId: 1 });
    expect(received).toEqual([{ type: 'stream-down', streamId: 1 }]);
  });

  it('keeps delivering to the others when one listener throws', () => {
    const received: NetworkSignal[] = [];
    const quiet = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const stopThrowing = subscribeNetworkSignals(() => {
      throw new Error('boom');
    });
    const stopRecording = subscribeNetworkSignals((signal) => received.push(signal));
    emitNetworkSignal({ type: 'rate-limited', waitSeconds: null });
    stopThrowing();
    stopRecording();
    quiet.mockRestore();
    expect(received).toEqual([{ type: 'rate-limited', waitSeconds: null }]);
  });
});
