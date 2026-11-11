import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { authHeaders, listAdminRuns, unwrap, type ListAdminRunsStatus } from '@flow-cortex/api-client';

export const PAGE_SIZE = 20;

/** `from` is inclusive and `to` exclusive, both instants; a field left out is not filtered. */
export interface RunFilters {
  status?: ListAdminRunsStatus | undefined;
  workflowId?: string | undefined;
  from?: string | undefined;
  to?: string | undefined;
}

function definedOnly({ status, workflowId, from, to }: RunFilters) {
  return {
    ...(status ? { status } : {}),
    ...(workflowId ? { workflowId } : {}),
    ...(from ? { from } : {}),
    ...(to ? { to } : {}),
  };
}

/** One page of every user's runs, newest first; the previous page stays visible while the next loads. */
export function useAdminRuns(page: number, filters: RunFilters, enabled = true) {
  const applied = definedOnly(filters);
  return useQuery({
    queryKey: ['admin', 'runs', page, applied],
    queryFn: () => unwrap(listAdminRuns({ page, size: PAGE_SIZE, ...applied }, authHeaders())),
    placeholderData: keepPreviousData,
    enabled,
  });
}
