import { useQuery } from '@tanstack/react-query';
import { authHeaders, capabilities, unwrap } from '@flow-cortex/api-client';

/** Backend-driven feature flags. Used to hide entry points that would otherwise 503 — the
 * per-endpoint 503 path is still the source of truth, this is the prompt. */
export function useCapabilitiesQuery() {
  return useQuery({
    queryKey: ['capabilities'],
    queryFn: () => unwrap(capabilities(authHeaders())),
    // Capabilities are stable for the session; the SPA boots without them anyway (loading state).
    staleTime: 60_000,
  });
}
