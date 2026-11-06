import { act, renderHook } from '@testing-library/react';
import { stubMatchMedia } from '../test/matchMedia';
import { parseSidebarMode, SIDEBAR_MODE_KEY, useIsDesktop, useSidebarMode } from './sidebarMode';

describe('parseSidebarMode', () => {
  it.each([
    [null, 'pinned'],
    ['pinned', 'pinned'],
    ['rail', 'rail'],
    ['wide', 'pinned'],
    ['', 'pinned'],
  ] as const)('reads %j as %s', (raw, expected) => {
    expect(parseSidebarMode(raw)).toBe(expected);
  });
});

describe('useSidebarMode', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
  });

  it('starts pinned on a first visit', () => {
    const { result } = renderHook(() => useSidebarMode());

    expect(result.current.mode).toBe('pinned');
  });

  it('keeps the choice across a remount', () => {
    const first = renderHook(() => useSidebarMode());
    act(() => first.result.current.toggle());
    expect(first.result.current.mode).toBe('rail');
    first.unmount();

    const second = renderHook(() => useSidebarMode());

    expect(second.result.current.mode).toBe('rail');
    expect(localStorage.getItem(SIDEBAR_MODE_KEY)).toBe('rail');
  });

  it('reads an unknown stored value as pinned', () => {
    localStorage.setItem(SIDEBAR_MODE_KEY, 'wide');

    const { result } = renderHook(() => useSidebarMode());

    expect(result.current.mode).toBe('pinned');
  });

  it('stays pinned and toggles in memory when storage throws', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError');
    });

    const { result } = renderHook(() => useSidebarMode());
    expect(result.current.mode).toBe('pinned');

    act(() => result.current.toggle());
    expect(result.current.mode).toBe('rail');
    act(() => result.current.toggle());
    expect(result.current.mode).toBe('pinned');
  });
});

describe('useIsDesktop', () => {
  it('treats a browser without matchMedia as desktop', () => {
    expect(typeof window.matchMedia).not.toBe('function');

    const { result } = renderHook(() => useIsDesktop());

    expect(result.current).toBe(true);
  });

  it('follows the 1024px query and drops its listener on unmount', () => {
    const media = stubMatchMedia(false);

    const { result, unmount } = renderHook(() => useIsDesktop());
    expect(result.current).toBe(false);
    expect(media.listenerCount()).toBe(1);

    act(() => media.setMatches(true));
    expect(result.current).toBe(true);

    unmount();
    expect(media.listenerCount()).toBe(0);
  });
});
