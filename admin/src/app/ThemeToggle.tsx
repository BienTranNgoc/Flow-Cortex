import { useTheme, type Theme } from './useTheme';

interface ThemeToggleProps {
  readonly compact?: boolean;
}

const ICONS: Record<Theme, { label: string; svg: JSX.Element }> = {
  light: {
    // Shown when the user is in dark mode and would click to go light.
    label: 'Switch to light theme',
    svg: (
      <>
        <circle cx="12" cy="12" r="4" />
        <path d="M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
      </>
    ),
  },
  dark: {
    // Shown when the user is in light mode and would click to go dark.
    label: 'Switch to dark theme',
    svg: <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z" />,
  },
};

export function ThemeToggle({ compact = false }: ThemeToggleProps) {
  const { theme, toggle } = useTheme();
  const next: Theme = theme === 'dark' ? 'light' : 'dark';
  const meta = ICONS[next];
  return (
    <button
      type="button"
      className={`cortex-theme-toggle${compact ? ' cortex-theme-toggle--compact' : ''}`}
      onClick={toggle}
      aria-label={meta.label}
      title={meta.label}
    >
      <svg
        xmlns="http://www.w3.org/2000/svg"
        viewBox="0 0 24 24"
        width="18"
        height="18"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.7"
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
      >
        {meta.svg}
      </svg>
      {!compact && <span>{next === 'dark' ? 'Dark' : 'Light'}</span>}
    </button>
  );
}
