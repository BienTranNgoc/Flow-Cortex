import { ApiError } from '@flow-cortex/api-client';
import { reportClientError } from '@flow-cortex/ui-kit';
import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query';

const PHONE_VERIFICATION_REQUIRED = 'PHONE_VERIFICATION_REQUIRED';

function isPhoneGateRefusal(error: unknown): boolean {
  const problem = error instanceof ApiError && error.status === 403 ? error.problem : undefined;
  return problem !== undefined && (problem as { code?: unknown }).code === PHONE_VERIFICATION_REQUIRED;
}

/**
 * The server holds a member whose phone is unverified: any call but the exempt ones answers 403
 * PHONE_VERIFICATION_REQUIRED. Refetching `/me` lets `PhoneGate` move the member to the
 * verification page; `/me` itself is exempt, so its own failure never loops back here.
 */
function refetchMeWhenHeld(error: unknown, queryKey?: readonly unknown[]) {
  if (isPhoneGateRefusal(error) && queryKey?.[0] !== 'me') {
    void queryClient.invalidateQueries({ queryKey: ['me'] });
  }
}

/**
 * `retry: false` by default — a 401/403/404/409 problem+json error is a
 * definite answer, not a transient failure worth retrying. Every failed
 * query is reported the same way a contained render failure is.
 */
export const queryClient: QueryClient = new QueryClient({
  queryCache: new QueryCache({
    onError: (error, query) => {
      reportClientError(error);
      refetchMeWhenHeld(error, query.queryKey);
    },
  }),
  mutationCache: new MutationCache({
    onError: (error) => refetchMeWhenHeld(error),
  }),
  defaultOptions: {
    queries: {
      retry: false,
    },
  },
});
