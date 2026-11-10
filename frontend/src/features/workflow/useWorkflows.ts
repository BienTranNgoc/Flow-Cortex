import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  ApiError,
  approveTask,
  authHeaders,
  cancelRun,
  getOutputUrl,
  getReviewTasks,
  getRevisions,
  getRun,
  getRunInputs,
  getWorkflow,
  listWorkflows,
  rejectTask,
  requestUpload,
  saveRevision,
  startProductVideoRun,
  startRun,
  unwrap,
} from '@flow-cortex/api-client';
import type {
  ApproveReviewTaskRequest,
  ProductVideoRunRequest,
  StartRunRequest,
  UploadRequest,
} from '@flow-cortex/api-client';
import { useRunEvents } from './useRunEvents';

/** What a reviewer decided: the edited text for a plain checkpoint, the revision for a script one. */
export interface ApproveTaskInput {
  reviewedPayload?: string | undefined;
  revisionId?: string | undefined;
  expectedVersion?: number | undefined;
}

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

/** Product Video has its own endpoint: typed request (product input + ordered upload ids) and a
 * fixed 9:16 video config. The same call goes through the orchestration layer used by the demo
 * workflows, so the run-progress page and approval checkpoint pick it up unchanged. */
export function useStartProductVideoRunMutation() {
  return useMutation({
    mutationFn: (body: ProductVideoRunRequest) =>
      unwrap(startProductVideoRun(body, authHeaders())),
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

/** Loads the run and, while it is live, refetches it on each push from its event stream. */
export function useRunQuery(runId: string | undefined) {
  const query = useQuery({
    queryKey: ['runs', runId],
    queryFn: () => unwrap(getRun(runId as string, authHeaders())),
    enabled: Boolean(runId),
  });
  useRunEvents(runId, query.data?.status, query.refetch);
  return query;
}

const INPUT_URL_REFRESH_LEAD_MS = 60_000;
// A stale timer longer than ~24.8 days overflows setTimeout and fires at once.
const MAX_INPUT_STALE_MS = 24 * 60 * 60 * 1000;

/** The run's uploaded files with signed links, kept fresh until shortly before the first expires. */
export function useRunInputsQuery(runId: string) {
  return useQuery({
    queryKey: ['runs', runId, 'inputs'],
    queryFn: () => unwrap(getRunInputs(runId, authHeaders())),
    enabled: Boolean(runId),
    staleTime: (query) => {
      const expiries = (query.state.data ?? []).map((input) => Date.parse(input.expiresAt));
      if (expiries.length === 0 || expiries.some(Number.isNaN)) {
        return 0;
      }
      const untilRefresh = Math.min(...expiries) - INPUT_URL_REFRESH_LEAD_MS - Date.now();
      return Math.min(Math.max(0, untilRefresh), MAX_INPUT_STALE_MS);
    },
  });
}

/**
 * The answer is the run itself (`cancelling` or `cancelled`), so it replaces the cached copy; a
 * refetch already in flight is dropped first so its older status cannot land on top. A refusal
 * (e.g. 409, the run already finished) reloads the run to show where it actually is.
 */
export function useCancelRunMutation(runId: string) {
  const queryClient = useQueryClient();
  const runKey = ['runs', runId];
  return useMutation({
    mutationFn: () => unwrap(cancelRun(runId, authHeaders())),
    onMutate: () => queryClient.cancelQueries({ queryKey: runKey, exact: true }),
    onSuccess: (run) => queryClient.setQueryData(runKey, run),
    onError: (error) => {
      if (error instanceof ApiError && error.status < 500) {
        return queryClient.invalidateQueries({ queryKey: runKey, exact: true });
      }
      return undefined;
    },
  });
}

/** Keyed under the run, so invalidating the run after a decision reloads these too. */
export function useReviewTasksQuery(runId: string) {
  return useQuery({
    queryKey: ['runs', runId, 'review-tasks'],
    queryFn: () => unwrap(getReviewTasks(runId, authHeaders())),
    // A review can be created a moment after the run already reads as waiting for it.
    refetchInterval: (query) =>
      query.state.status === 'success' &&
      !query.state.data?.some((task) => task.status === 'pending')
        ? 1000
        : false,
  });
}

/**
 * The task's immutable script revisions, newest number last. Only a task that opted into revisions
 * (`currentRevisionId` set) has any; the panel reads the list for those alone.
 */
export function useRevisionsQuery(taskId: string | undefined) {
  return useQuery({
    queryKey: ['review-tasks', taskId, 'revisions'],
    queryFn: () => unwrap(getRevisions(taskId as string, authHeaders())),
    enabled: Boolean(taskId),
  });
}

/** One revision, appended to the task's own list — the task's `version` comes back on the response. */
export function useSaveRevisionMutation(runId: string, taskId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: { content: string; expectedVersion: number }) =>
      unwrap(saveRevision(taskId, body, authHeaders())),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['review-tasks', taskId, 'revisions'] });
      queryClient.invalidateQueries({ queryKey: ['runs', runId] });
    },
  });
}

/**
 * `reviewedPayload` is sent only when the reviewer changed the content — but a task that keeps
 * script revisions must always name the revision and the task version it saw, or the server
 * rejects the decision with a 409 instead of approving something the reviewer never read.
 */
export function useApproveTaskMutation(runId: string, taskId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: ApproveTaskInput) =>
      unwrap(approveTask(taskId, approveBody(input), authHeaders())),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['runs', runId] }),
  });
}

/**
 * A plain checkpoint approves the edited text; a script checkpoint names the revision it saw.
 * `exactOptionalPropertyTypes` rejects an explicit `undefined`, so a field that is not part of
 * this decision is omitted from the body rather than sent as null.
 */
function approveBody(input: ApproveTaskInput): ApproveReviewTaskRequest | undefined {
  if (input.revisionId !== undefined || input.expectedVersion !== undefined) {
    return {
      ...(input.revisionId === undefined ? {} : { revisionId: input.revisionId }),
      ...(input.expectedVersion === undefined ? {} : { expectedVersion: input.expectedVersion }),
    };
  }
  return input.reviewedPayload === undefined ? undefined : { reviewedPayload: input.reviewedPayload };
}

export function useRejectTaskMutation(runId: string, taskId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => unwrap(rejectTask(taskId, authHeaders())),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['runs', runId] }),
  });
}
