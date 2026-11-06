import { useEffect, useState } from 'react';

const DESKTOP_QUERY = '(min-width: 1024px)';

function readDesktop(): boolean {
  return typeof window.matchMedia !== 'function' || window.matchMedia(DESKTOP_QUERY).matches;
}

/** True at 1024px and wider. Where `matchMedia` is missing the viewport is treated as desktop. */
export function useIsDesktop(): boolean {
  const [desktop, setDesktop] = useState(readDesktop);

  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return;
    const query = window.matchMedia(DESKTOP_QUERY);
    const onChange = (event: MediaQueryListEvent) => setDesktop(event.matches);
    setDesktop(query.matches);
    query.addEventListener('change', onChange);
    return () => query.removeEventListener('change', onChange);
  }, []);

  return desktop;
}
