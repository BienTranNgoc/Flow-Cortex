import {
  authHeaders,
  getUnreadNotificationCount,
  listNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  type NotificationPage,
} from '@flow-cortex/api-client';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { unwrapSupport } from '../features/support/unwrapSupport';
import { activeLocale } from '../lib/i18n';

export const ADMIN_NOTIFICATIONS_KEY = ['admin', 'notifications'] as const;
export const ADMIN_UNREAD_POLL_MS = 60_000;
export const PANEL_SIZE = 10;

const AUDIENCE = { audience: 'admin' } as const;

export interface AdminNotification {
  readonly id: string;
  readonly title: string;
  readonly body: string;
  readonly link: string | undefined;
  readonly read: boolean;
  readonly createdAt: string | undefined;
}

/** A link opens inside the console only when it is an absolute path of this origin. */
export function isInAppLink(link: string | undefined): link is string {
  return (
    link !== undefined && link.startsWith('/') && !link.startsWith('//') && !link.startsWith('/\\')
  );
}

export function notificationsOf(page: NotificationPage | undefined): AdminNotification[] {
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

/** Unread admin-audience notifications of the signed-in account, refreshed every minute. */
export function useAdminUnreadCount(): number {
  const query = useQuery({
    queryKey: [...ADMIN_NOTIFICATIONS_KEY, 'unread-count'],
    queryFn: () => unwrapSupport(getUnreadNotificationCount(AUDIENCE, authHeaders())),
    select: (result) => result.unreadCount,
    refetchInterval: ADMIN_UNREAD_POLL_MS,
  });
  return query.data ?? 0;
}

/**
 * The latest admin notifications for the bell panel, fetched only while it is open. The server
 * renders text in the request language, so the language is part of the key.
 */
export function useAdminNotificationList(open: boolean) {
  const locale = activeLocale();
  return useQuery({
    queryKey: [...ADMIN_NOTIFICATIONS_KEY, 'list', locale],
    queryFn: () =>
      unwrapSupport(
        listNotifications({ ...AUDIENCE, page: '0', size: String(PANEL_SIZE) }, authHeaders()),
      ),
    enabled: open,
  });
}

export function useMarkAdminRead() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => unwrapSupport(markNotificationRead(id, authHeaders())),
    onSettled: () => queryClient.invalidateQueries({ queryKey: ADMIN_NOTIFICATIONS_KEY }),
  });
}

export function useMarkAllAdminRead() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => unwrapSupport(markAllNotificationsRead(AUDIENCE, authHeaders())),
    onSettled: () => queryClient.invalidateQueries({ queryKey: ADMIN_NOTIFICATIONS_KEY }),
  });
}
