import {
  authHeaders,
  getUnreadNotificationCount,
  listNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  unwrap,
  type NotificationPage,
} from '@flow-cortex/api-client';
import {
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
  type InfiniteData,
} from '@tanstack/react-query';
import { activeLocale } from '../../lib/i18n';
import { NOTIFICATIONS_KEY } from './useNotificationStream';

export type NotificationFilter = 'all' | 'unread';

export const PAGE_SIZE = 20;
export const LATEST_SIZE = 6;

export interface NotificationEntry {
  readonly id: string;
  readonly title: string;
  readonly body: string;
  readonly link: string | undefined;
  readonly read: boolean;
  readonly createdAt: string | undefined;
}

/** A link opens inside the app only when it is an absolute path of this origin. */
export function isInAppLink(link: string | undefined): link is string {
  return (
    link !== undefined && link.startsWith('/') && !link.startsWith('//') && !link.startsWith('/\\')
  );
}

export function entriesOf(page: NotificationPage | undefined): NotificationEntry[] {
  return (page?.items ?? []).flatMap((item) =>
    item.id === undefined
      ? []
      : [
          {
            id: item.id,
            title: item.title ?? '',
            body: item.body ?? '',
            link: item.link,
            read: item.read ?? false,
            createdAt: item.createdAt,
          },
        ],
  );
}

async function fetchPage(filter: NotificationFilter, page: number, size: number): Promise<NotificationPage> {
  const response = await unwrap(
    listNotifications({ filter, page: String(page), size: String(size) }, authHeaders()),
  );
  return response as NotificationPage;
}

export function useUnreadCount(): number {
  const query = useQuery({
    queryKey: [...NOTIFICATIONS_KEY, 'unread-count'],
    queryFn: () => unwrap(getUnreadNotificationCount(authHeaders())),
  });
  return query.data?.unreadCount ?? 0;
}

/**
 * The newest notifications for the bell panel, fetched only while it is open. The server renders
 * text in the request language, so the language is part of the key.
 */
export function useLatest(enabled: boolean) {
  const locale = activeLocale();
  return useQuery({
    queryKey: [...NOTIFICATIONS_KEY, 'latest', locale],
    queryFn: () => fetchPage('all', 0, LATEST_SIZE),
    enabled,
  });
}

/** The notifications page: 20 at a time, `fetchNextPage` while `hasNextPage`. */
export function useNotificationList(filter: NotificationFilter) {
  const locale = activeLocale();
  return useInfiniteQuery<
    NotificationPage,
    Error,
    InfiniteData<NotificationPage, number>,
    readonly unknown[],
    number
  >({
    queryKey: [...NOTIFICATIONS_KEY, 'list', filter, locale],
    queryFn: ({ pageParam }) => fetchPage(filter, pageParam, PAGE_SIZE),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.hasMore ? (last.page ?? 0) + 1 : undefined),
  });
}

export function useMarkRead() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await unwrap(markNotificationRead(id, authHeaders()));
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: NOTIFICATIONS_KEY }),
  });
}

export function useMarkAllRead() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => unwrap(markAllNotificationsRead(authHeaders())),
    onSettled: () => queryClient.invalidateQueries({ queryKey: NOTIFICATIONS_KEY }),
  });
}
