import { useEffect, useReducer } from 'react';

/** True until `lockedUntil` passes, re-rendering the caller at that instant so a disabled control comes back on its own. */
export function useIsLocked(lockedUntil: string | undefined): boolean {
  const [, rerender] = useReducer((tick: number) => tick + 1, 0);
  const end = lockedUntil === undefined ? Number.NaN : Date.parse(lockedUntil);
  const remaining = end - Date.now();
  const locked = remaining > 0;
  useEffect(() => {
    if (!locked) return;
    const timer = setTimeout(rerender, remaining);
    return () => clearTimeout(timer);
    // `remaining` moves every render; the end instant is what decides when to wake.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [end, locked]);
  return locked;
}
