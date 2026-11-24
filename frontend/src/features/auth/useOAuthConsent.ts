import { useMutation, useQuery } from '@tanstack/react-query';
import {
  authHeaders,
  checkOAuthAuthorization,
  grantOAuthAuthorization,
  unwrap,
  useAuthToken,
  type CheckOAuthAuthorizationParams,
  type GrantRequest,
} from '@flow-cortex/api-client';

/**
 * The server's verdict on the authorization request in the page URL: the registered client name,
 * the verified redirect URI and whether this account may consent. Asked once per link.
 */
export function useOAuthCheckQuery(params: CheckOAuthAuthorizationParams) {
  const token = useAuthToken();
  return useQuery({
    queryKey: ['oauth-consent-check', token, params],
    queryFn: () => unwrap(checkOAuthAuthorization(params, authHeaders())),
    enabled: Boolean(token),
    staleTime: Infinity,
    refetchOnWindowFocus: false,
  });
}

/** Spends the step-up proof for a code; the answer is the redirect the browser follows. */
export function useGrantOAuthMutation() {
  return useMutation({
    mutationFn: (request: GrantRequest) => unwrap(grantOAuthAuthorization(request, authHeaders())),
  });
}
