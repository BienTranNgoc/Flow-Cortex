import { QueryClient } from '@tanstack/react-query';

/**
 * `retry: false` by default — a 401/403/404/409 problem+json error is a
 * definite answer, not a transient failure worth retrying.
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: false,
    },
  },
});
