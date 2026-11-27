import { act, renderHook } from '@testing-library/react';
import { useLockActive } from './useTotp';

describe('useLockActive', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('is active while the lock end is ahead and ends by itself at that moment', () => {
    const lockedUntil = new Date(Date.now() + 60_000);
    const { result } = renderHook(() => useLockActive(lockedUntil));
    expect(result.current).toBe(true);

    act(() => {
      vi.advanceTimersByTime(59_000);
    });
    expect(result.current).toBe(true);

    act(() => {
      vi.advanceTimersByTime(1_500);
    });
    expect(result.current).toBe(false);
  });

  it('is not active without a lock end or once it is behind us', () => {
    expect(renderHook(() => useLockActive(null)).result.current).toBe(false);
    expect(renderHook(() => useLockActive(undefined)).result.current).toBe(false);
    expect(renderHook(() => useLockActive(new Date(Date.now() - 1))).result.current).toBe(false);
  });
});
