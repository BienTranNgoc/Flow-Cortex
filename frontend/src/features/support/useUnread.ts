import {
  ApiError,
  authHeaders,
  countUnreadSupportRequests,
  useAuthToken,
} from '@flow-cortex/api-client';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef } from 'react';
import { useFeatures } from '../../app/useFeatures';
import { unwrapSupport } from './useSupportKb';

export const UNREAD_KEY = ['support', 'requests', 'unread-count'] as const;
export const LIST_KEY = ['support', 'requests', 'list'] as const;
export const DETAIL_KEY = ['support', 'requests', 'detail'] as const;
const POLL_MS = 60_000;

/** Requests with an admin reply the member has not opened; 0 when signed out or the widget is off. */
export function useUnreadCount(): number {
  const member = useAuthToken() !== null;
  const { supportWidget } = useFeatures();
  const enabled = member && supportWidget;
  const queryClient = useQueryClient();
  const previous = useRef<number | undefined>(undefined);
  const { data } = useQuery({
    queryKey: UNREAD_KEY,
    queryFn: () => unwrapSupport(countUnreadSupportRequests(authHeaders())),
    enabled,
    // A suspended member is refused every time; polling would only repeat the 403.
    refetchInterval: (query) => {
      const error = query.state.error;
      return error instanceof ApiError && error.status === 403 ? false : POLL_MS;
    },
  });
  const count = data?.count;

  // Only a rise triggers the re-read: reading a detail refetches this count, so reacting to "> 0"
  // would loop detail -> count -> detail while another request stays unread. The first load is
  // skipped because mounted threads and lists already fetch fresh.
  useEffect(() => {
    if (!enabled) {
      previous.current = undefined;
      return;
    }
    if (count === undefined) return;
    if (previous.current !== undefined && count > previous.current) {
      void queryClient.invalidateQueries({ queryKey: LIST_KEY });
      void queryClient.invalidateQueries({ queryKey: DETAIL_KEY });
    }
    previous.current = count;
  }, [enabled, count, queryClient]);

  return enabled ? (count ?? 0) : 0;
}
