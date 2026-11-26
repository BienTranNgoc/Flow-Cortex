import { useEffect } from 'react';
import { useOutletContext } from 'react-router-dom';

export interface LegalOutletContext {
  readonly setTitle: (title: string | null) => void;
}

/** Names the browser tab after the page; `LegalLayout` appends the brand and restores the old title on leaving. */
export function useLegalTitle(title: string | null): void {
  const { setTitle } = useOutletContext<LegalOutletContext>();
  useEffect(() => {
    setTitle(title);
    return () => setTitle(null);
  }, [setTitle, title]);
}
