import { useQuery, useMutation } from '@tanstack/react-query';
import {
  authHeaders,
  getOutputUrl,
  getRun,
  getWorkflow,
  listWorkflows,
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
 * Polls `/api/v1/runs/{id}` while the run is still `running` — the start
 * call returns before steps finish, so this is what actually shows a step
 * advancing pending -> running -> completed|error. Stops polling once the
 * run reaches either terminal status.
 */
export function useRunQuery(runId: string | undefined) {
  return useQuery({
    queryKey: ['runs', runId],
    queryFn: () => unwrap(getRun(runId as string, authHeaders())),
    enabled: Boolean(runId),
    refetchInterval: (query) => (query.state.data?.status === 'running' ? 1000 : false),
  });
}
