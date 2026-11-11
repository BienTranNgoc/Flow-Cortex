import { keepPreviousData, useQuery } from '@tanstack/react-query';
import {
  authHeaders,
  getAdminWorkflowReport,
  listAdminWorkflows,
  unwrap,
  type ListAdminWorkflowsStatus,
} from '@flow-cortex/api-client';

export const PAGE_SIZE = 20;

/** One page of every owner's live workflows, newest update first; the previous page stays while the next loads. */
export function useAdminWorkflows(
  page: number,
  status: ListAdminWorkflowsStatus | undefined,
  q: string,
) {
  return useQuery({
    queryKey: ['admin', 'workflows', page, status ?? '', q],
    queryFn: () =>
      unwrap(
        listAdminWorkflows(
          { page, size: PAGE_SIZE, ...(status ? { status } : {}), ...(q ? { q } : {}) },
          authHeaders(),
        ),
      ),
    placeholderData: keepPreviousData,
  });
}

/** The usage report of one workflow. */
export function useAdminWorkflowReport(id: string) {
  return useQuery({
    queryKey: ['admin', 'workflow-report', id],
    queryFn: () => unwrap(getAdminWorkflowReport(id, authHeaders())),
    enabled: id !== '',
  });
}
