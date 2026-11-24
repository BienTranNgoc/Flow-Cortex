import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
  type UseQueryResult,
} from '@tanstack/react-query';
import { useEffect, useMemo, useRef, useState } from 'react';
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
import type { RunResultResponse, UpdateRunRequest } from '@flow-cortex/api-client';
import {
  RESULTS_POLL_INTERVAL_MS,
  overrideResults,
  pendingRunIds,
  resultsRefetchInterval,
  settledRunIds,
  toListParams,
  trayRefetchInterval,
  type RunHistoryFilters,
  type RunStatus,
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

function runResultsKey(runIds: readonly string[]) {
  return ['run-results', runIds] as const;
}

async function fetchRunResults(runIds: readonly string[]): Promise<RunResultResponse[]> {
  return (await unwrap(listRunResults({ runId: [...runIds] }, authHeaders()))).results;
}

/**
 * What each run on the page produced, never polled. Kept outside {@link ROOT_KEY} so starring or renaming
 * a run does not refetch it and re-sign every thumbnail; a failed request leaves `data` empty.
 */
export function useRunResultsQuery(runIds: readonly string[]) {
  return useQuery({
    queryKey: runResultsKey(runIds),
    queryFn: () => fetchRunResults(runIds),
    enabled: runIds.length > 0,
    retry: 1,
    staleTime: RESULTS_STALE_MS,
  });
}

/**
 * Polls only the runs of `base` still pending and writes each answer into `base`'s cache for those runs alone,
 * so every other run keeps its signed thumbnail URLs. The first poll waits one interval: `base` just answered.
 */
export function usePendingRunResults(
  runIds: readonly string[],
  base: Pick<UseQueryResult<RunResultResponse[]>, 'data' | 'dataUpdatedAt'>,
): void {
  const queryClient = useQueryClient();
  const pendingIds = useMemo(() => pendingRunIds(base.data), [base.data]);
  useQuery({
    queryKey: ['run-results', 'pending', pendingIds],
    queryFn: async () => {
      const fresh = await fetchRunResults(pendingIds);
      queryClient.setQueryData<RunResultResponse[]>(
        runResultsKey(runIds),
        (current) => current && overrideResults(current, fresh),
      );
      return fresh;
    },
    enabled: pendingIds.length > 0,
    retry: 1,
    initialData: () => base.data?.filter((result) => pendingIds.includes(result.runId)),
    initialDataUpdatedAt: base.dataUpdatedAt,
    staleTime: RESULTS_POLL_INTERVAL_MS,
    refetchInterval: (query) =>
      resultsRefetchInterval(query.state.data, query.state.dataUpdateCount),
  });
}

/**
 * Asks again about each run of `items` that finished since the previous list answer, and writes the answer into
 * `runIds`' cache for those runs alone. The long `staleTime` of {@link useRunResultsQuery} would otherwise keep
 * the summary taken while the run was still running (no files, no registration yet).
 */
export function useRefreshSettledRuns(
  items: readonly { id: string; status: RunStatus }[] | undefined,
  runIds: readonly string[],
): void {
  const queryClient = useQueryClient();
  const previous = useRef<ReadonlyMap<string, RunStatus>>(new Map());
  const [settledIds, setSettledIds] = useState<string[]>([]);
  useEffect(() => {
    if (!items) return;
    const settled = settledRunIds(previous.current, items);
    previous.current = new Map(items.map((run) => [run.id, run.status]));
    if (settled.length > 0) setSettledIds(settled);
  }, [items]);
  useQuery({
    queryKey: ['run-results', 'settled', settledIds],
    queryFn: async () => {
      const key = runResultsKey(runIds);
      // Nothing to merge into, or a request in flight that may predate the run finishing: ask for every run again.
      if (
        queryClient.getQueryData(key) === undefined ||
        queryClient.getQueryState(key)?.fetchStatus === 'fetching'
      ) {
        await queryClient.invalidateQueries({ queryKey: key });
        return [];
      }
      const fresh = await fetchRunResults(settledIds);
      queryClient.setQueryData<RunResultResponse[]>(
        key,
        (current) => current && overrideResults(current, fresh),
      );
      return fresh;
    },
    enabled: settledIds.length > 0,
    retry: 1,
    staleTime: Infinity,
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
