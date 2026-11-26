import { authHeaders, countUnreadSupportRequests, useAuthToken } from '@flow-cortex/api-client';
import { useQuery } from '@tanstack/react-query';
import { useFeatures } from '../../app/useFeatures';
import { unwrapSupport } from './useSupportKb';

export const UNREAD_KEY = ['support', 'requests', 'unread-count'] as const;
export const LIST_KEY = ['support', 'requests', 'list'] as const;
export const DETAIL_KEY = ['support', 'requests', 'detail'] as const;

/**
 * Requests with an admin reply the member has not opened; 0 when signed out or the widget is off.
 * Read on load and on window focus; `useSupportStream` refreshes it on every stream signal.
 */
export function useUnreadCount(): number {
  const member = useAuthToken() !== null;
  const { supportWidget } = useFeatures();
  const enabled = member && supportWidget;
  const { data } = useQuery({
    queryKey: UNREAD_KEY,
    queryFn: () => unwrapSupport(countUnreadSupportRequests(authHeaders())),
    enabled,
  });

  return enabled ? (data?.count ?? 0) : 0;
}
