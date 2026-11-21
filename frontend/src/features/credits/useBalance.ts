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

const POLL_MS = 90_000;

export interface Wallet {
  readonly balance: number;
  readonly granted: number;
}

/**
 * The member's wallet, or `undefined` while credits are off or no read has succeeded; a failed
 * background read keeps the last value. Runs live under `['runs', id]`, so a terminal one
 * refreshes the balance whichever page polled it. A hidden tab does not poll.
 */
export function useBalance(): Wallet | undefined {
  const { credits } = useFeatures();
  const queryClient = useQueryClient();
  const query = useQuery({
    queryKey: BALANCE_KEY,
    queryFn: () => unwrap(balance(authHeaders())),
    enabled: credits,
    refetchInterval: POLL_MS,
    refetchOnWindowFocus: true,
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

  if (!credits || query.data?.balance === undefined) {
    return undefined;
  }
  return { balance: query.data.balance, granted: query.data.granted ?? 0 };
}
