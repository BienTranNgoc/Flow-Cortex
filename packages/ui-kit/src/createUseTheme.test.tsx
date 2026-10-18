import { act, render, screen } from '@testing-library/react';
import { createUseTheme } from './createUseTheme';

const STORAGE_KEY = 'flow-cortex:test:theme';

const { initTheme, useTheme } = createUseTheme({
  storageKey: STORAGE_KEY,
  systemDefault: 'light',
});

type ThemeApi = ReturnType<typeof useTheme>;

function Probe({ onReady }: { readonly onReady: (api: ThemeApi) => void }) {
  const api = useTheme();
  onReady(api);
  return (
    <div>
      <span data-testid="theme">{api.theme}</span>
      <button type="button" onClick={api.toggle}>
        toggle
      </button>
    </div>
  );
}

beforeEach(() => {
  document.documentElement.removeAttribute('data-theme');
  document
    .querySelector('meta[name="color-scheme"]')
    ?.setAttribute('content', 'light dark');
  try {
    localStorage.removeItem(STORAGE_KEY);
  } catch {
    // localStorage may be unavailable in jsdom; nothing to do.
  }
});

describe('createUseTheme', () => {
  it('initTheme applies the system default when no choice is stored', () => {
    initTheme();
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
  });

  it('useTheme reflects [data-theme] on the root and updates it on toggle', () => {
    document.documentElement.setAttribute('data-theme', 'dark');
    const probe = vi.fn();
    render(<Probe onReady={probe} />);
    expect(screen.getByTestId('theme').textContent).toBe('dark');

    const api = probe.mock.calls.at(-1)?.[0] as ThemeApi | undefined;
    act(() => api?.toggle());
    expect(screen.getByTestId('theme').textContent).toBe('light');
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
  });

  it('setTheme persists the choice under the configured storage key', () => {
    document.documentElement.setAttribute('data-theme', 'light');
    const probe = vi.fn();
    const { rerender } = render(<Probe onReady={probe} />);
    const api = probe.mock.calls.at(-1)?.[0] as ThemeApi | undefined;

    act(() => api?.setTheme('dark'));
    rerender(<Probe onReady={probe} />);

    expect(screen.getByTestId('theme').textContent).toBe('dark');
    expect(localStorage.getItem(STORAGE_KEY)).toBe('dark');
  });

  it('initTheme uses a dark system default when configured', () => {
    const dark = createUseTheme({
      storageKey: 'flow-cortex:test:dark',
      systemDefault: 'dark',
    });
    dark.initTheme();
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
  });
});
