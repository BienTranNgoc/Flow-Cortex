import { useSyncExternalStore } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  authHeaders,
  getToken,
  login as loginRequest,
  me as meRequest,
  setToken,
  subscribeToken,
  unwrap,
} from '@flow-cortex/api-client';
import type { LoginRequest } from '@flow-cortex/api-client';
import { queryClient } from '../../app/queryClient';

/** Re-renders the calling component whenever the stored token changes. */
export function useAuthToken(): string | null {
  return useSyncExternalStore(subscribeToken, getToken);
}

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

export function useLoginMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: LoginRequest) => unwrap(loginRequest(input)),
    onSuccess: (data) => {
      setToken(data.accessToken ?? null, true);
      queryClient.invalidateQueries({ queryKey: ['me'] });
    },
  });
}

/** Signs out and forgets every cached result, so the next account starts from nothing. */
export function logout(): void {
  setToken(null);
  queryClient.clear();
}
