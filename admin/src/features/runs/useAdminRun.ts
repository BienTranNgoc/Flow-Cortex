import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { authHeaders, cancelAdminRun, getAdminRun, unwrap } from '@flow-cortex/api-client';

const runKey = (id: string) => ['admin', 'run', id] as const;

/** One run with its steps, never its input or output. */
export function useAdminRun(id: string) {
  return useQuery({
    queryKey: runKey(id),
    queryFn: () => unwrap(getAdminRun(id, authHeaders())),
    enabled: id !== '',
  });
}

/** Stops the run; the answer is the run as read now, so the page and the list refresh from it. */
export function useCancelAdminRun(id: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => unwrap(cancelAdminRun(id, authHeaders())),
    onSuccess: (run) => queryClient.setQueryData(runKey(id), run),
    // A 409 means the run finished meanwhile: re-reading it removes the stop action.
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: runKey(id) }),
        queryClient.invalidateQueries({ queryKey: ['admin', 'runs'] }),
      ]),
  });
}
