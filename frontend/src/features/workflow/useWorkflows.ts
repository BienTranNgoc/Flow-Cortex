import { useQuery, useMutation } from '@tanstack/react-query';
import { getRun, getWorkflow, listWorkflows, startRun } from '../../api/generated/flowCortexApi';
import type { StartRunRequest } from '../../api/generated/flowCortexApi';
import { authHeaders, unwrap } from '../../api/client';

/** ALL authenticated users (owner OR shared, design.md D1/R-049) — no admin gate, unlike `features/users`. */
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

/**
 * Polls `/api/v1/runs/{id}` while the run is still `running` (design.md
 * D5/R-050 — the start call returns before steps finish, so this is what
 * actually shows a step advancing pending -> running -> completed|error).
 * Stops polling once the run reaches either terminal status.
 */
export function useRunQuery(runId: string | undefined) {
  return useQuery({
    queryKey: ['runs', runId],
    queryFn: () => unwrap(getRun(runId as string, authHeaders())),
    enabled: Boolean(runId),
    refetchInterval: (query) => (query.state.data?.status === 'running' ? 1000 : false),
  });
}
