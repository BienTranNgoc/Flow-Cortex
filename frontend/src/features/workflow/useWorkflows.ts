import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  approveTask,
  authHeaders,
  getOutputUrl,
  getReviewTasks,
  getRun,
  getWorkflow,
  listWorkflows,
  rejectTask,
  requestUpload,
  startRun,
  unwrap,
} from '@flow-cortex/api-client';
import type { StartRunRequest, UploadRequest } from '@flow-cortex/api-client';

/** ALL authenticated users (owner OR shared) — no admin gate. */
export function useWorkflowsQuery() {
  return useQuery({
    queryKey: ['workflows'],
    queryFn: () => unwrap(listWorkflows(authHeaders())),
  });
}

export function useWorkflowQuery(id: string) {
  return useQuery({
    queryKey: ['workflows', id],
    queryFn: () => unwrap(getWorkflow(id, authHeaders())),
    enabled: Boolean(id),
  });
}

export function useStartRunMutation(workflowId: string) {
  return useMutation({
    mutationFn: (input: StartRunRequest) => unwrap(startRun(workflowId, input, authHeaders())),
  });
}

/** Asks for a presigned PUT for one file; the file itself never goes through the API. */
export function useCreateUploadMutation() {
  return useMutation({
    mutationFn: (body: UploadRequest) => unwrap(requestUpload(body, authHeaders())),
  });
}

/** A fresh, short-lived download URL for one output of a finished step, requested on demand. */
export function useOutputUrlMutation(runId: string, stepOrder: number, name: string) {
  return useMutation({
    mutationFn: () => unwrap(getOutputUrl(runId, stepOrder, name, authHeaders())),
  });
}

/**
 * Polls `/api/v1/runs/{id}` while the run is still `running` or `waiting_approval` — the start
 * call returns before steps finish, so this is what actually shows a step
 * advancing pending -> running -> completed|error, and a review decided elsewhere still moves
 * the page on. Stops polling once the run reaches either terminal status.
 */
export function useRunQuery(runId: string | undefined) {
  return useQuery({
    queryKey: ['runs', runId],
    queryFn: () => unwrap(getRun(runId as string, authHeaders())),
    enabled: Boolean(runId),
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      return status === 'running' || status === 'waiting_approval' ? 1000 : false;
    },
  });
}

/** Keyed under the run, so invalidating the run after a decision reloads these too. */
export function useReviewTasksQuery(runId: string) {
  return useQuery({
    queryKey: ['runs', runId, 'review-tasks'],
    queryFn: () => unwrap(getReviewTasks(runId, authHeaders())),
  });
}

/** `reviewedPayload` is sent only when the reviewer changed the content. */
export function useApproveTaskMutation(runId: string, taskId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (reviewedPayload: string | undefined) =>
      unwrap(
        approveTask(
          taskId,
          reviewedPayload === undefined ? undefined : { reviewedPayload },
          authHeaders(),
        ),
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['runs', runId] }),
  });
}

export function useRejectTaskMutation(runId: string, taskId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => unwrap(rejectTask(taskId, authHeaders())),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['runs', runId] }),
  });
}
