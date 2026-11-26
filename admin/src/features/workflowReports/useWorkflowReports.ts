import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  authHeaders,
  dismissWorkflowReport,
  getWorkflowReport,
  hideWorkflowReport,
  listWorkflowReports,
  removeWorkflowReport,
  resolveWorkflowReport,
  restoreWorkflowReport,
  unwrap,
  type ListWorkflowReportsStatus,
  type ModerationRequest,
  type WorkflowReportDetailResponse,
} from '@flow-cortex/api-client';
import type { ModerationAction } from './moderationActions';

export const PAGE_SIZE = 20;

const reportKey = (id: string) => ['admin', 'workflow-report', id] as const;
const LIST_KEY = ['admin', 'workflow-reports'] as const;

/** One page of reports, newest first; the previous page stays visible while the next loads. */
export function useWorkflowReports(
  page: number,
  status: ListWorkflowReportsStatus | undefined,
  q: string,
) {
  return useQuery({
    queryKey: [...LIST_KEY, page, status ?? '', q],
    queryFn: () =>
      unwrap(
        listWorkflowReports(
          { page, size: PAGE_SIZE, ...(status ? { status } : {}), ...(q ? { q } : {}) },
          authHeaders(),
        ),
      ),
    placeholderData: keepPreviousData,
  });
}

/** One report with its reporter, workflow and the workflow's moderation history. */
export function useWorkflowReport(id: string) {
  return useQuery({
    queryKey: reportKey(id),
    queryFn: () => unwrap(getWorkflowReport(id, authHeaders())),
    enabled: id !== '',
  });
}

const ACTION_CALLS: Record<
  ModerationAction,
  (id: string, body: ModerationRequest, options: RequestInit) => ReturnType<typeof hideWorkflowReport>
> = {
  hide: hideWorkflowReport,
  remove: removeWorkflowReport,
  restore: restoreWorkflowReport,
  dismiss: dismissWorkflowReport,
  resolve: resolveWorkflowReport,
};

export interface ModerationInput {
  readonly action: ModerationAction;
  readonly reason?: string;
  readonly internalNote?: string;
}

/** Applies one action to the report; the answer is the report as read after it, so the page refreshes from it. */
export function useModerateReport(id: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ action, reason, internalNote }: ModerationInput): Promise<WorkflowReportDetailResponse> =>
      unwrap(
        ACTION_CALLS[action](
          id,
          { ...(reason ? { reason } : {}), ...(internalNote ? { internalNote } : {}) },
          authHeaders(),
        ),
      ),
    onSuccess: (report) => queryClient.setQueryData(reportKey(id), report),
    // A 404 or 409 means the workflow changed meanwhile: re-reading shows which actions remain.
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: reportKey(id) }),
        queryClient.invalidateQueries({ queryKey: LIST_KEY }),
      ]),
  });
}
