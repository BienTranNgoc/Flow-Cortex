import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeToggle } from './ThemeToggle';

afterEach(() => {
  document.documentElement.removeAttribute('data-theme');
  try {
    localStorage.removeItem('flow-cortex:theme');
  } catch {
    /* localStorage may be unavailable in jsdom */
  }
});

describe('ThemeToggle', () => {
  it('switches the root theme attribute when clicked', async () => {
    document.documentElement.setAttribute('data-theme', 'light');
    const user = userEvent.setup();
    render(<ThemeToggle />);

    const button = screen.getByRole('button', { name: /switch to dark theme/i });
    await user.click(button);

    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    expect(localStorage.getItem('flow-cortex:theme')).toBe('dark');
  });

  it('renders the "switch to light" label when the active theme is dark', async () => {
    document.documentElement.setAttribute('data-theme', 'dark');
    render(<ThemeToggle />);
    expect(screen.getByRole('button', { name: /switch to light theme/i })).toBeInTheDocument();
  });
});
