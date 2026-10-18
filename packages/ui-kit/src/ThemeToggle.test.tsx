import { fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { createUseTheme, type Theme } from './createUseTheme';
import { ThemeToggle } from './ThemeToggle';

const STORAGE_KEY = 'flow-cortex:ui-kit-test:theme';
const { useTheme } = createUseTheme({ storageKey: STORAGE_KEY, systemDefault: 'light' });

const LABELS = {
  switchToLight: 'Switch to light theme',
  switchToDark: 'Switch to dark theme',
  light: 'Light',
  dark: 'Dark',
};

function WiredToggle({ compact }: { readonly compact?: boolean }) {
  const { theme, toggle } = useTheme();
  return <ThemeToggle theme={theme} onToggle={toggle} compact={compact ?? false} labels={LABELS} />;
}

afterEach(() => {
  document.documentElement.removeAttribute('data-theme');
  try {
    localStorage.removeItem(STORAGE_KEY);
  } catch {
    // localStorage may be unavailable in jsdom; nothing to clean.
  }
});

describe('ThemeToggle', () => {
  it('switches the root theme attribute and persists the choice when wired to useTheme', () => {
    document.documentElement.setAttribute('data-theme', 'light');
    render(<WiredToggle />);

    fireEvent.click(screen.getByRole('button', { name: /switch to dark theme/i }));

    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    expect(localStorage.getItem(STORAGE_KEY)).toBe('dark');
    expect(screen.getByRole('button', { name: /switch to light theme/i })).toBeInTheDocument();
  });

  it('renders the "switch to light" label when the active theme is dark', () => {
    render(<ThemeToggle theme="dark" onToggle={() => {}} labels={LABELS} />);
    expect(screen.getByRole('button', { name: /switch to light theme/i })).toBeInTheDocument();
  });

  it('calls onToggle on click and shows the destination label only when not compact', () => {
    function Harness({ compact }: { readonly compact: boolean }) {
      const [theme, setTheme] = useState<Theme>('light');
      return (
        <ThemeToggle
          theme={theme}
          compact={compact}
          labels={LABELS}
          onToggle={() => setTheme((current) => (current === 'dark' ? 'light' : 'dark'))}
        />
      );
    }
    const { rerender } = render(<Harness compact={false} />);
    expect(screen.getByRole('button')).toHaveTextContent('Dark');

    fireEvent.click(screen.getByRole('button'));
    expect(screen.getByRole('button')).toHaveTextContent('Light');

    rerender(<Harness compact />);
    expect(screen.getByRole('button')).toHaveClass('cortex-theme-toggle--compact');
    expect(screen.getByRole('button').textContent).toBe('');
  });
});
