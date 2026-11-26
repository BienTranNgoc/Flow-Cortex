import { openEventStream } from '@flow-cortex/api-client';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { detailKey } from './useAdminRequests';

function isVisible(): boolean {
  return document.visibilityState === 'visible';
}

/**
 * Holds the stream of one request while the tab is visible and reads the request again on each
 * connect, `support.request.changed` and `resync`. A hidden tab or a left page closes it; a topic
 * the server refuses ends it for good and the page keeps refreshing from its own reads.
 */
export function useRequestStream(number: number): void {
  const queryClient = useQueryClient();
  const [visible, setVisible] = useState(isVisible);
  const valid = Number.isSafeInteger(number) && number > 0;

  useEffect(() => {
    const onChange = () => setVisible(isVisible());
    document.addEventListener('visibilitychange', onChange);
    return () => document.removeEventListener('visibilitychange', onChange);
  }, []);

  useEffect(() => {
    if (!visible || !valid) {
      return undefined;
    }
    const refetch = () => {
      void queryClient.invalidateQueries({ queryKey: detailKey(number) });
    };
    const stream = openEventStream({
      topics: [`support:request:${number}`],
      onOpen: refetch,
      onEvent: (event) => {
        if (event.event === 'support.request.changed' || event.event === 'resync') {
          refetch();
        }
      },
    });
    return () => stream.close();
  }, [visible, valid, number, queryClient]);
}
