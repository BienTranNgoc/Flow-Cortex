import { useCallback, useEffect, useState } from 'react';

export type Theme = 'light' | 'dark';

export interface UseThemeOptions {
  readonly storageKey: string;
  readonly systemDefault: Theme;
}

function readStoredTheme(storageKey: string): Theme | null {
  try {
    const value = localStorage.getItem(storageKey);
    if (value === 'light' || value === 'dark') return value;
  } catch {
    // localStorage can throw in private mode — fall through to the system pref.
  }
  return null;
}

function systemTheme(systemDefault: Theme): Theme {
  if (typeof window === 'undefined' || !window.matchMedia) return systemDefault;
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

function applyTheme(theme: Theme): void {
  if (typeof document === 'undefined') return;
  document.documentElement.dataset.theme = theme;
  document
    .querySelector('meta[name="color-scheme"]')
    ?.setAttribute('content', theme);
}

export interface CreateUseThemeResult {
  readonly initTheme: () => void;
  readonly useTheme: () => {
    readonly theme: Theme;
    readonly setTheme: (next: Theme) => void;
    readonly toggle: () => void;
  };
}

export function createUseTheme(options: UseThemeOptions): CreateUseThemeResult {
  const { storageKey, systemDefault } = options;

  function initTheme(): void {
    const stored = readStoredTheme(storageKey);
    applyTheme(stored ?? systemTheme(systemDefault));
  }

  function useTheme(): {
    readonly theme: Theme;
    readonly setTheme: (next: Theme) => void;
    readonly toggle: () => void;
  } {
    const [theme, setThemeState] = useState<Theme>(() => {
      if (typeof document === 'undefined') return systemDefault;
      const attr = document.documentElement.getAttribute('data-theme');
      if (attr === 'light' || attr === 'dark') return attr;
      return systemTheme(systemDefault);
    });

    useEffect(() => {
      if (typeof window === 'undefined' || !window.matchMedia) return;
      const mql = window.matchMedia('(prefers-color-scheme: dark)');
      const onChange = (event: MediaQueryListEvent): void => {
        if (readStoredTheme(storageKey) !== null) return;
        const next: Theme = event.matches ? 'dark' : 'light';
        setThemeState(next);
        applyTheme(next);
      };
      mql.addEventListener('change', onChange);
      return () => mql.removeEventListener('change', onChange);
    }, []);

    const setTheme = useCallback((next: Theme) => {
      setThemeState(next);
      try {
        localStorage.setItem(storageKey, next);
      } catch {
        // The in-memory state still updates for the current session.
      }
      applyTheme(next);
    }, []);

    const toggle = useCallback(() => {
      setTheme(theme === 'dark' ? 'light' : 'dark');
    }, [theme, setTheme]);

    return { theme, setTheme, toggle };
  }

  return { initTheme, useTheme };
}
