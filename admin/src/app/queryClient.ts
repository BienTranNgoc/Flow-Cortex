import { reportClientError } from '@flow-cortex/ui-kit';
import { QueryCache, QueryClient } from '@tanstack/react-query';

/**
 * `retry: false`: a problem+json error is a definite answer, not a transient failure. Every failed
 * query is reported the same way a contained render failure is.
 */
export const queryClient = new QueryClient({
  queryCache: new QueryCache({
    onError: (error) => reportClientError(error),
  }),
  defaultOptions: {
    queries: {
      retry: false,
    },
  },
});
