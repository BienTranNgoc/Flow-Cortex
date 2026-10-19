import { useCallback } from 'react';
import { flushSync } from 'react-dom';
import { useLocation, useNavigate } from 'react-router-dom';

/**
 * `go(to)` swaps one auth screen for another, cross-fading the card in a view transition unless
 * motion is reduced, and asks the new screen to focus its heading. The return path survives.
 */
export function useAuthNavigate() {
  const navigate = useNavigate();
  const from = (useLocation().state as { from?: unknown } | null)?.from;
  const go = useCallback(
    (to: string) => {
      const state =
        typeof from === 'string' ? { from, focusHeading: true } : { focusHeading: true };
      const open = () => navigate(to, { state });
      const reduceMotion =
        typeof window.matchMedia === 'function' &&
        window.matchMedia('(prefers-reduced-motion: reduce)').matches;
      if (typeof document.startViewTransition === 'function' && !reduceMotion) {
        // The new screen must be in the DOM when the callback returns, or the transition snapshots the old one.
        document.startViewTransition(() => flushSync(open));
      } else {
        open();
      }
    },
    [navigate, from],
  );
  return { go };
}
