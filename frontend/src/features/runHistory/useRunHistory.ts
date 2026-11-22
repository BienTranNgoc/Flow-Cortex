import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  authHeaders,
  deleteRun,
  getRunTray,
  getRunUsageSummary,
  listRunResults,
  listRunWorkflows,
  listRuns,
  unwrap,
  updateRun,
  useAuthToken,
} from '@flow-cortex/api-client';
import type { UpdateRunRequest } from '@flow-cortex/api-client';
import {
  resultsRefetchInterval,
  toListParams,
  trayRefetchInterval,
  type RunHistoryFilters,
} from './runHistoryRules';

export const RUN_HISTORY_PAGE_SIZE = 20;

const ROOT_KEY = ['run-history'] as const;

/** Below the 15-minute presign TTL, so a list refetch does not re-sign the thumbnails and miss the browser cache. */
const RESULTS_STALE_MS = 5 * 60_000;

export function useRunHistoryQuery(filters: RunHistoryFilters) {
  const params = toListParams(filters, RUN_HISTORY_PAGE_SIZE);
  return useQuery({
    queryKey: [...ROOT_KEY, 'list', params],
    queryFn: () => unwrap(listRuns(params, authHeaders())),
    placeholderData: keepPreviousData,
  });
}

export function useRunWorkflowsQuery() {
  return useQuery({
    queryKey: [...ROOT_KEY, 'workflows'],
    queryFn: () => unwrap(listRunWorkflows(authHeaders())),
  });
}

export function useUsageSummaryQuery(days: number) {
  return useQuery({
    queryKey: [...ROOT_KEY, 'summary', days],
    queryFn: () => unwrap(getRunUsageSummary({ days }, authHeaders())),
    placeholderData: keepPreviousData,
  });
}

/** The top bar's tray: refetched per {@link trayRefetchInterval} and when the window regains focus. */
export function useRunTrayQuery() {
  const signedIn = useAuthToken() !== null;
  return useQuery({
    queryKey: [...ROOT_KEY, 'tray'],
    queryFn: () => unwrap(getRunTray(authHeaders())),
    enabled: signedIn,
    refetchInterval: (query) => trayRefetchInterval(query.state.data, signedIn),
    refetchOnWindowFocus: true,
  });
}

/**
 * What each run on the page produced. Kept outside {@link ROOT_KEY} so starring or renaming a run
 * does not refetch it and re-sign every thumbnail; a failed request leaves `data` empty and the rows unchanged.
 */
export function useRunResultsQuery(runIds: readonly string[]) {
  return useQuery({
    queryKey: ['run-results', runIds],
    queryFn: async () => (await unwrap(listRunResults({ runId: [...runIds] }, authHeaders()))).results,
    enabled: runIds.length > 0,
    retry: 1,
    staleTime: RESULTS_STALE_MS,
    refetchInterval: (query) =>
      resultsRefetchInterval(query.state.data, query.state.dataUpdateCount),
  });
}

/** A change to one run moves the list, the tray and the usage numbers, so all of them refetch. */
function useInvalidateHistory() {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: ROOT_KEY });
}

export function useUpdateRunMutation() {
  const invalidate = useInvalidateHistory();
  return useMutation({
    mutationFn: ({ id, patch }: { id: string; patch: UpdateRunRequest }) =>
      unwrap(updateRun(id, patch, authHeaders())),
    onSuccess: invalidate,
  });
}

export function useDeleteRunMutation() {
  const invalidate = useInvalidateHistory();
  return useMutation({
    mutationFn: (id: string) => unwrap(deleteRun(id, authHeaders())),
    onSuccess: invalidate,
  });
}
