import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  authHeaders,
  login as loginRequest,
  me as meRequest,
  setToken,
  signOut,
  unwrap,
  useAuthToken,
} from '@flow-cortex/api-client';
import type {
  AccessTokenResponse,
  LoginRequest,
  TotpChallengeResponse,
} from '@flow-cortex/api-client';
import { queryClient } from '../../app/queryClient';

export { useAuthToken, useSessionReady } from '@flow-cortex/api-client';
export { accountLockedMessage } from '@flow-cortex/i18n';

/** The signed-in user's own record; disabled while signed out. */
export function useMeQuery() {
  const token = useAuthToken();
  return useQuery({
    queryKey: ['me', token],
    queryFn: () => unwrap(meRequest(authHeaders())),
    enabled: Boolean(token),
    retry: false,
  });
}

/** A 202 from login: the password was right and a code is still owed; no session exists yet. */
export function isTotpChallenge(
  data: AccessTokenResponse | TotpChallengeResponse | undefined,
): data is TotpChallengeResponse {
  return data !== undefined && !('accessToken' in data);
}

export function useLoginMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: LoginRequest) => unwrap(loginRequest({ ...input, rememberMe: true })),
    onSuccess: (data) => {
      if (isTotpChallenge(data)) return;
      setToken(data.accessToken ?? null, true);
      queryClient.invalidateQueries({ queryKey: ['me'] });
    },
  });
}

/** Signs out, then drops every cached result so the next account starts from nothing. */
export async function logout(): Promise<void> {
  await signOut();
  queryClient.clear();
}
