import { useInfiniteQuery } from '@tanstack/react-query';
import { authHeaders, getUserActivity, unwrap } from '@flow-cortex/api-client';

export const ACTIVITY_PAGE_SIZE = 50;
const ACTIVITY_QUERY_KEY = 'userActivity';

/** Query key of one member's timeline; the update mutation invalidates it so a new admin action shows up. */
export function userActivityKey(id: string) {
  return [ACTIVITY_QUERY_KEY, id] as const;
}

/** One member's activity, newest first; each page carries the cursor of the next while more remain. */
export function useUserActivity(id: string) {
  return useInfiniteQuery({
    queryKey: userActivityKey(id),
    queryFn: ({ pageParam }) =>
      unwrap(
        getUserActivity(
          id,
          { limit: ACTIVITY_PAGE_SIZE, ...(pageParam ? { cursor: pageParam } : {}) },
          authHeaders(),
        ),
      ),
    initialPageParam: '',
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
    enabled: id.length > 0,
  });
}
