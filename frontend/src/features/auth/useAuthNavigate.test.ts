import { act, renderHook } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { useAuthNavigate } from './useAuthNavigate';

function renderGo() {
  return renderHook(() => ({ go: useAuthNavigate().go, location: useLocation() }), {
    wrapper: ({ children }: { children: ReactNode }) =>
      createElement(
        MemoryRouter,
        { initialEntries: [{ pathname: '/login', state: { from: '/profile' } }] },
        children,
      ),
  });
}

function stubReducedMotion(reduce: boolean) {
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => ({
      matches: reduce && query === '(prefers-reduced-motion: reduce)',
      media: query,
    })),
  );
}

describe('useAuthNavigate', () => {
  afterEach(() => {
    Reflect.deleteProperty(document, 'startViewTransition');
    vi.unstubAllGlobals();
  });

  it('swaps screens inside a view transition, asking for the heading and keeping the return path', () => {
    stubReducedMotion(false);
    const startViewTransition = vi.fn((update: () => void) => {
      update();
      return {};
    });
    Object.assign(document, { startViewTransition });
    const { result } = renderGo();

    act(() => result.current.go('/register'));

    expect(startViewTransition).toHaveBeenCalledTimes(1);
    expect(result.current.location.pathname).toBe('/register');
    expect(result.current.location.state).toEqual({ from: '/profile', focusHeading: true });
  });

  it('swaps at once under reduced motion and still asks for the heading', () => {
    stubReducedMotion(true);
    const startViewTransition = vi.fn();
    Object.assign(document, { startViewTransition });
    const { result } = renderGo();

    act(() => result.current.go('/register'));

    expect(startViewTransition).not.toHaveBeenCalled();
    expect(result.current.location.pathname).toBe('/register');
    expect(result.current.location.state).toEqual({ from: '/profile', focusHeading: true });
  });
});
