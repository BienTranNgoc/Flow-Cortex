import { useCallback } from 'react';
import { flushSync } from 'react-dom';
import { useNavigate } from 'react-router-dom';

/** `go(to)` opens a just-started run's page from the top and asks it to focus its heading. */
export function useTransitionNavigate() {
  const navigate = useNavigate();
  const go = useCallback(
    (to: string) => {
      const open = () => navigate(to, { state: { focusHeading: true } });
      if (typeof document.startViewTransition === 'function') {
        // The new page must be in the DOM when the callback returns, or the transition snapshots the old one.
        document.startViewTransition(() => {
          flushSync(open);
          window.scrollTo(0, 0);
        });
      } else {
        open();
        window.scrollTo(0, 0);
      }
    },
    [navigate],
  );
  return { go };
}
