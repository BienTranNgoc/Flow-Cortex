import { authHeaders, getRun, unwrap } from '@flow-cortex/api-client';

/**
 * The run page's cache entry, without its own stream: a thread holds one stream for all its runs
 * and refetches the run that changed.
 */
export function runQueryOptions(runId: string) {
  return {
    queryKey: ['runs', runId] as const,
    queryFn: () => unwrap(getRun(runId, authHeaders())),
  };
}
