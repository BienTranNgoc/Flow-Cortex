import { openEventStream } from '@flow-cortex/api-client';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { createRefetchCoalescer } from '../workflow/refetchCoalescer';

export const NOTIFICATIONS_KEY = ['notifications'] as const;

function isVisible(): boolean {
  return document.visibilityState === 'visible';
}

/**
 * Holds the `user` stream while the tab is visible and refetches every notification query on each
 * connect, `notifications.changed` and `resync`, never overlapping. A hidden tab closes its stream
 * so background tabs cannot use up the member's stream allowance.
 */
export function useNotificationStream(): void {
  const queryClient = useQueryClient();
  const [visible, setVisible] = useState(isVisible);

  useEffect(() => {
    const onChange = () => setVisible(isVisible());
    document.addEventListener('visibilitychange', onChange);
    return () => document.removeEventListener('visibilitychange', onChange);
  }, []);

  useEffect(() => {
    if (!visible) {
      return undefined;
    }
    const coalescer = createRefetchCoalescer(() =>
      queryClient.invalidateQueries({ queryKey: NOTIFICATIONS_KEY }),
    );
    const stream = openEventStream({
      topics: ['user'],
      onOpen: coalescer.request,
      onEvent: (event) => {
        if (event.event === 'notifications.changed' || event.event === 'resync') {
          coalescer.request();
        }
      },
    });
    return () => {
      coalescer.stop();
      stream.close();
    };
  }, [visible, queryClient]);
}
