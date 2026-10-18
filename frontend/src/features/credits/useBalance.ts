import { useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';
import { authHeaders, balance, unwrap } from '@flow-cortex/api-client';
import { useFeatures } from '../../app/useFeatures';

const BALANCE_KEY = ['credits', 'balance'] as const;
const TERMINAL_RUN_STATUSES = new Set(['completed', 'error', 'cancelled']);

/** Marks the balance stale; the header refetches it, a page without one fetches it on next use. */
export function invalidateBalance(queryClient: QueryClient): Promise<void> {
  return queryClient.invalidateQueries({ queryKey: BALANCE_KEY, exact: true });
}

function isRunKey(key: readonly unknown[]): boolean {
  return key.length === 2 && key[0] === 'runs';
}

/**
 * The member's balance, or `undefined` while credits are off, loading, or the request failed.
 * Every run the app shows lives under `['runs', id]`, so a run that lands in a terminal status
 * refreshes the balance whichever page polled it.
 */
export function useBalance(): number | undefined {
  const { credits } = useFeatures();
  const queryClient = useQueryClient();
  const query = useQuery({
    queryKey: BALANCE_KEY,
    queryFn: () => unwrap(balance(authHeaders())),
    enabled: credits,
  });

  useEffect(() => {
    if (!credits) {
      return undefined;
    }
    return queryClient.getQueryCache().subscribe((event) => {
      if (event.type !== 'updated' || event.action.type !== 'success') {
        return;
      }
      const status = (event.query.state.data as { status?: unknown } | undefined)?.status;
      if (isRunKey(event.query.queryKey) && TERMINAL_RUN_STATUSES.has(String(status))) {
        void invalidateBalance(queryClient);
      }
    });
  }, [credits, queryClient]);

  if (!credits || query.isError) {
    return undefined;
  }
  return query.data?.balance;
}
