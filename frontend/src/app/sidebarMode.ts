import { useCallback, useEffect, useState } from 'react';

export type SidebarMode = 'pinned' | 'rail';

export const SIDEBAR_MODE_KEY = 'flow-cortex:sidebar-mode';
const DESKTOP_QUERY = '(min-width: 1024px)';

/** Labels are the safe default: only the exact stored `rail` collapses the sidebar. */
export function parseSidebarMode(raw: string | null): SidebarMode {
  return raw === 'rail' ? 'rail' : 'pinned';
}

function readStoredMode(): SidebarMode {
  try {
    return parseSidebarMode(localStorage.getItem(SIDEBAR_MODE_KEY));
  } catch {
    // Storage can be blocked; the sidebar then lives in memory for the session.
    return 'pinned';
  }
}

export function useSidebarMode() {
  const [mode, setModeState] = useState<SidebarMode>(readStoredMode);
  const setMode = useCallback((next: SidebarMode) => {
    try {
      localStorage.setItem(SIDEBAR_MODE_KEY, next);
    } catch {
      // Not persisted; the choice still applies until reload.
    }
    setModeState(next);
  }, []);
  const toggle = useCallback(() => setMode(mode === 'pinned' ? 'rail' : 'pinned'), [mode, setMode]);
  return { mode, setMode, toggle };
}

function canMatchMedia(): boolean {
  return typeof window.matchMedia === 'function';
}

/** True at 1024px and wider; a browser without `matchMedia` counts as desktop. */
export function useIsDesktop(): boolean {
  const [desktop, setDesktop] = useState(() =>
    canMatchMedia() ? window.matchMedia(DESKTOP_QUERY).matches : true,
  );
  useEffect(() => {
    if (!canMatchMedia()) return;
    const query = window.matchMedia(DESKTOP_QUERY);
    const onChange = (event: MediaQueryListEvent) => setDesktop(event.matches);
    setDesktop(query.matches);
    query.addEventListener('change', onChange);
    return () => query.removeEventListener('change', onChange);
  }, []);
  return desktop;
}
