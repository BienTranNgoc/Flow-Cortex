import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';

const isPlainLeftClick = (event: MouseEvent) =>
  event.button === 0 && !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey;

/**
 * While `active`, closing the tab raises the browser prompt and a click on an in-app link is held
 * as `pending` until `leave` or `stay`. Links are caught at the document because the router has no
 * blocker API; the browser's Back button is not caught.
 */
export function useUnsavedGuard(active: boolean) {
  const navigate = useNavigate();
  const [pending, setPending] = useState<string | null>(null);

  useEffect(() => {
    if (!active) return undefined;
    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };
    const onClick = (event: MouseEvent) => {
      if (event.defaultPrevented || !isPlainLeftClick(event)) return;
      const anchor = event.target instanceof Element ? event.target.closest('a[href]') : null;
      if (
        !(anchor instanceof HTMLAnchorElement) ||
        anchor.target === '_blank' ||
        anchor.hasAttribute('download')
      ) {
        return;
      }
      const url = new URL(anchor.href, window.location.href);
      const here = window.location;
      const samePage = url.pathname === here.pathname && url.search === here.search;
      if (url.origin !== here.origin || samePage) return;
      event.preventDefault();
      event.stopPropagation();
      setPending(`${url.pathname}${url.search}${url.hash}`);
    };
    window.addEventListener('beforeunload', onBeforeUnload);
    document.addEventListener('click', onClick, true);
    return () => {
      window.removeEventListener('beforeunload', onBeforeUnload);
      document.removeEventListener('click', onClick, true);
    };
  }, [active]);

  const stay = useCallback(() => setPending(null), []);
  const leave = useCallback(() => {
    if (pending !== null) navigate(pending);
    setPending(null);
  }, [navigate, pending]);

  return { pending, stay, leave };
}
