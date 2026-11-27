import { useMutation, useQueryClient } from '@tanstack/react-query';
import { authHeaders, putRunFeedback, unwrap } from '@flow-cortex/api-client';
import type { RunFeedbackRequest } from '@flow-cortex/api-client';

/** Stores or replaces the owner's verdict on a run, then re-reads the run so it carries the stored feedback. */
export function useRunFeedback(runId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: RunFeedbackRequest) => unwrap(putRunFeedback(runId, body, authHeaders())),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['runs', runId], exact: true }),
  });
}
