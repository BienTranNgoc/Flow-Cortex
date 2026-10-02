import { keepPreviousData, useQuery } from '@tanstack/react-query';
import {
  authHeaders,
  listAdminMediaJobs,
  unwrap,
  type ListAdminMediaJobsStatus,
} from '@flow-cortex/api-client';

export const PAGE_SIZE = 20;

/**
 * One page of remote steps across all runs, newest first. Polled so a reaper marker appears and
 * clears without the operator pressing Refresh.
 */
export function useMediaJobs(page: number, status: ListAdminMediaJobsStatus | undefined) {
  return useQuery({
    queryKey: ['admin', 'media-jobs', page, status],
    queryFn: () =>
      unwrap(listAdminMediaJobs({ page, size: PAGE_SIZE, ...(status ? { status } : {}) }, authHeaders())),
    placeholderData: keepPreviousData,
    refetchInterval: 30_000,
  });
}
