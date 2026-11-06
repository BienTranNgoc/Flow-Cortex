import type { ReactElement } from 'react';
import type { Theme } from './createUseTheme';

export interface ThemeToggleLabels {
  readonly switchToLight: string;
  readonly switchToDark: string;
  readonly light: string;
  readonly dark: string;
}

export interface ThemeToggleProps {
  readonly theme: Theme;
  readonly onToggle: () => void;
  readonly compact?: boolean;
  /** Required so a missing translation is a type error, never an English fallback. */
  readonly labels: ThemeToggleLabels;
}

// Keyed by the theme a click switches TO, so the icon previews the destination.
const ICONS: Record<Theme, ReactElement> = {
  light: (
    <>
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
    </>
  ),
  dark: <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z" />,
};

/** Light/dark switch; each app passes the state from its own `useTheme` so storage keys stay per app. */
export function ThemeToggle({ theme, onToggle, compact = false, labels }: ThemeToggleProps) {
  const next: Theme = theme === 'dark' ? 'light' : 'dark';
  const actionLabel = next === 'dark' ? labels.switchToDark : labels.switchToLight;
  return (
    <button
      type="button"
      className={`cortex-theme-toggle${compact ? ' cortex-theme-toggle--compact' : ''}`}
      onClick={onToggle}
      aria-label={actionLabel}
      title={actionLabel}
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
        {ICONS[next]}
      </svg>
      {!compact && <span>{next === 'dark' ? labels.dark : labels.light}</span>}
    </button>
  );
}
