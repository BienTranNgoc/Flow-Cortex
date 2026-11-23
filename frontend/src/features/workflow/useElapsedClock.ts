import { useEffect, useState } from 'react';
import { computeElapsedSeconds, formatElapsedClock } from './stepDisplay';

/** Hooks a 1-second timer while an active step/run progresses so completed ones stay static. */
export function useElapsedClock(
  startedAt: string | undefined,
  finishedAt: string | undefined,
  active: boolean,
): string | null {
  const [nowMs, setNowMs] = useState(() => Date.now());

  useEffect(() => {
    if (!active || !startedAt || finishedAt) return;
    const id = window.setInterval(() => setNowMs(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, [active, startedAt, finishedAt]);

  const secs = computeElapsedSeconds(startedAt, finishedAt, nowMs);
  return secs === null ? null : formatElapsedClock(secs);
}
