import { act, render, screen } from '@testing-library/react';
import { useTheme } from './useTheme';

type ThemeApi = ReturnType<typeof useTheme>;

afterEach(() => {
  document.documentElement.removeAttribute('data-theme');
  document
    .querySelector('meta[name="color-scheme"]')
    ?.setAttribute('content', 'light dark');
  try {
    localStorage.removeItem('flow-cortex:theme');
  } catch {
    // localStorage may be unavailable in jsdom; nothing to do.
  }
});

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

describe('useTheme', () => {
  it('returns the theme currently on the root and updates [data-theme] on toggle', () => {
    document.documentElement.setAttribute('data-theme', 'dark');
    const probe = vi.fn();
    render(<Probe onReady={probe} />);
    expect(screen.getByTestId('theme').textContent).toBe('dark');

    const api = probe.mock.calls.at(-1)?.[0] as ThemeApi | undefined;
    act(() => api?.toggle());
    expect(screen.getByTestId('theme').textContent).toBe('light');
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
  });

  it('persists the explicit choice so the system pref stops overriding it', () => {
    document.documentElement.setAttribute('data-theme', 'light');
    const probe = vi.fn();
    const { rerender } = render(<Probe onReady={probe} />);
    const api = probe.mock.calls.at(-1)?.[0] as ThemeApi | undefined;

    act(() => api?.setTheme('dark'));
    rerender(<Probe onReady={probe} />);

    expect(screen.getByTestId('theme').textContent).toBe('dark');
    expect(localStorage.getItem('flow-cortex:theme')).toBe('dark');
  });
});
