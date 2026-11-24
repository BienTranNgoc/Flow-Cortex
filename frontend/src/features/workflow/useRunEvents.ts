import { openEventStream, type RunStepResponse } from '@flow-cortex/api-client';
import { useEffect, useRef } from 'react';
import { createRefetchCoalescer } from './refetchCoalescer';

/** A run stays live while its own status can change or any step is still running after a clip fails. */
export function isLiveRunStatus(
  status: string | undefined,
  steps?: readonly Pick<RunStepResponse, 'status'>[],
): boolean {
  return (
    status === 'running' ||
    status === 'waiting_approval' ||
    status === 'cancelling' ||
    Boolean(steps?.some((step) => step.status === 'running'))
  );
}

/**
 * Holds the `run:<id>` stream while the run is live and calls `refetch` on every `run.updated`,
 * `resync` and (re)connect, never overlapping. Closes when the run and all steps settle or on unmount.
 */
export function useRunEvents(
  runId: string | undefined,
  status: string | undefined,
  refetch: () => Promise<unknown>,
  steps?: readonly Pick<RunStepResponse, 'status'>[],
): void {
  const refetchRef = useRef(refetch);
  refetchRef.current = refetch;
  const live = Boolean(runId) && isLiveRunStatus(status, steps);

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
