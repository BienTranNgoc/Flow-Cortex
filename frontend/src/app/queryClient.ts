import { reportClientError } from '@flow-cortex/ui-kit';
import { QueryCache, QueryClient } from '@tanstack/react-query';

/**
 * `retry: false` by default — a 401/403/404/409 problem+json error is a
 * definite answer, not a transient failure worth retrying. Every failed
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
