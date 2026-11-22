import { useAuthToken } from '@flow-cortex/api-client';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef } from 'react';
import { useFeatures } from '../../app/useFeatures';
import { useUserStream } from '../../lib/userStream';
import { createRefetchCoalescer, type RefetchCoalescer } from '../workflow/refetchCoalescer';

const SUPPORT_REQUESTS_KEY = ['support', 'requests'] as const;

/**
 * Follows the tab's shared `user` stream while a member is signed in with the help widget on, and
 * reads the request list, unread count and mounted threads again on each connect,
 * `support.request.changed` and `resync`, never overlapping.
 */
export function useSupportStream(): void {
  const queryClient = useQueryClient();
  const member = useAuthToken() !== null;
  const { supportWidget } = useFeatures();
  const enabled = member && supportWidget;
  const coalescer = useRef<RefetchCoalescer | null>(null);

  useEffect(() => {
    const active = createRefetchCoalescer(() =>
      queryClient.invalidateQueries({ queryKey: SUPPORT_REQUESTS_KEY }),
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
        if (event.event === 'support.request.changed' || event.event === 'resync') {
          coalescer.current?.request();
        }
      },
    },
    enabled,
  );
}
