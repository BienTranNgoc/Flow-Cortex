import { authHeaders, countOpenSupportRequests } from '@flow-cortex/api-client';
import { useQuery } from '@tanstack/react-query';
import { unwrapSupport } from './unwrapSupport';

export const OPEN_COUNT_POLL_MS = 60_000;

/** Requests in new and in-progress, refreshed every minute while the console is open. */
export function useOpenCount(enabled: boolean) {
  return useQuery({
    queryKey: ['admin', 'support', 'requests', 'open-count'],
    queryFn: () => unwrapSupport(countOpenSupportRequests(authHeaders())),
    select: (result) => result.count,
    enabled,
    refetchInterval: OPEN_COUNT_POLL_MS,
  });
}
