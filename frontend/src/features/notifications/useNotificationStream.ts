import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef } from 'react';
import { useUserStream } from '../../lib/userStream';
import { createRefetchCoalescer, type RefetchCoalescer } from '../workflow/refetchCoalescer';

export const NOTIFICATIONS_KEY = ['notifications'] as const;

/**
 * Follows the tab's shared `user` stream and refetches every notification query on each connect,
 * `notifications.changed` and `resync`, never overlapping.
 */
export function useNotificationStream(): void {
  const queryClient = useQueryClient();
  const coalescer = useRef<RefetchCoalescer | null>(null);

  useEffect(() => {
    const active = createRefetchCoalescer(() =>
      queryClient.invalidateQueries({ queryKey: NOTIFICATIONS_KEY }),
    );
    coalescer.current = active;
    return () => {
      active.stop();
      coalescer.current = null;
    };
  }, [queryClient]);

  useUserStream(
    {
      onOpen: () => coalescer.current?.request(),
      onEvent: (event) => {
        if (event.event === 'notifications.changed' || event.event === 'resync') {
          coalescer.current?.request();
        }
      },
    },
    true,
  );
}
