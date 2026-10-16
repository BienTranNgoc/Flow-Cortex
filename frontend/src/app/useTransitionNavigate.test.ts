import { act, renderHook } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { useTransitionNavigate } from './useTransitionNavigate';

function renderGo() {
  const keys: string[] = [];
  const hook = renderHook(
    () => {
      const location = useLocation();
      keys.push(location.key);
      return { go: useTransitionNavigate().go, location };
    },
    {
      wrapper: ({ children }: { children: ReactNode }) =>
        createElement(MemoryRouter, { initialEntries: ['/'] }, children),
    },
  );
  return { ...hook, keys };
}

describe('useTransitionNavigate', () => {
  const scrollTo = vi.fn();
  beforeEach(() => {
    scrollTo.mockClear();
    vi.stubGlobal('scrollTo', scrollTo);
  });
  afterEach(() => {
    Reflect.deleteProperty(document, 'startViewTransition');
    vi.unstubAllGlobals();
  });

  it('navigates once at once, asking the run page to focus its heading, where there is no view transition', () => {
    const { result, keys } = renderGo();

    act(() => result.current.go('/runs/1'));

    expect(result.current.location.pathname).toBe('/runs/1');
    expect(result.current.location.state).toEqual({ focusHeading: true });
    expect(new Set(keys).size).toBe(2);
    expect(scrollTo).toHaveBeenCalledWith(0, 0);
  });

  it('navigates inside the callback of one view transition where the browser has it', () => {
    let update: (() => void) | undefined;
    const startViewTransition = vi.fn((callback: () => void) => {
      update = callback;
      return {};
    });
    Object.assign(document, { startViewTransition });
    const { result } = renderGo();

    act(() => result.current.go('/runs/1'));

    expect(startViewTransition).toHaveBeenCalledTimes(1);
    expect(result.current.location.pathname).toBe('/');

    act(() => update?.());

    expect(result.current.location.pathname).toBe('/runs/1');
    expect(result.current.location.state).toEqual({ focusHeading: true });
    expect(scrollTo).toHaveBeenCalledWith(0, 0);
  });
});
