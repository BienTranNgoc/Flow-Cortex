import { openEventStream } from '@flow-cortex/api-client';
import { useEffect, useRef } from 'react';
import { createRefetchCoalescer } from './refetchCoalescer';

/** A run in one of these statuses still changes on its own; every other status is final. */
export function isLiveRunStatus(status: string | undefined): boolean {
  return status === 'running' || status === 'waiting_approval' || status === 'cancelling';
}

/**
 * Holds the `run:<id>` stream while the run is live and calls `refetch` on every `run.updated`,
 * `resync` and (re)connect, never overlapping. Closes when the run turns terminal or on unmount.
 */
export function useRunEvents(
  runId: string | undefined,
  status: string | undefined,
  refetch: () => Promise<unknown>,
): void {
  const refetchRef = useRef(refetch);
  refetchRef.current = refetch;
  const live = Boolean(runId) && isLiveRunStatus(status);

  useEffect(() => {
    if (!live || !runId) {
      return undefined;
    }
    const coalescer = createRefetchCoalescer(() => refetchRef.current());
    const stream = openEventStream({
      topics: [`run:${runId.toLowerCase()}`],
      onOpen: coalescer.request,
      onEvent: (event) => {
        if (event.event === 'run.updated' || event.event === 'resync') {
          coalescer.request();
        }
      },
    });
    return () => {
      coalescer.stop();
      stream.close();
    };
  }, [live, runId]);
}
