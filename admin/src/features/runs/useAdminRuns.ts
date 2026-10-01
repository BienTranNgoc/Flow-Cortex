import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { authHeaders, listAdminRuns, unwrap, type ListAdminRunsStatus } from '@flow-cortex/api-client';

export const PAGE_SIZE = 20;

/** One page of every user's runs, newest first; the previous page stays visible while the next loads. */
export function useAdminRuns(page: number, status: ListAdminRunsStatus | undefined) {
  return useQuery({
    queryKey: ['admin', 'runs', page, status],
    queryFn: () =>
      unwrap(listAdminRuns({ page, size: PAGE_SIZE, ...(status ? { status } : {}) }, authHeaders())),
    placeholderData: keepPreviousData,
  });
}
