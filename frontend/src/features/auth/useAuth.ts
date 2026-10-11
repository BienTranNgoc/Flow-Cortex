import { useSyncExternalStore } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  authHeaders,
  getToken,
  login as loginRequest,
  me as meRequest,
  register as registerRequest,
  setToken,
  subscribeToken,
  unwrap,
} from '@flow-cortex/api-client';
import type { LoginRequest, RegisterRequest } from '@flow-cortex/api-client';

/** Re-renders the calling component whenever the stored token changes. */
export function useAuthToken(): string | null {
  return useSyncExternalStore(subscribeToken, getToken);
}

/**
 * The signed-in user's own record. Disabled while signed out — there's
 * nothing to fetch, and an unauthenticated call would only bounce back a
 * 401 the query cache has no reason to hold onto.
 */
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
      setToken(data.accessToken ?? null);
      queryClient.invalidateQueries({ queryKey: ['me'] });
    },
  });
}

export function useRegisterMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: RegisterRequest) => unwrap(registerRequest(input)),
    onSuccess: (data) => {
      setToken(data.accessToken ?? null);
      queryClient.invalidateQueries({ queryKey: ['me'] });
    },
  });
}

export function logout(): void {
  setToken(null);
}
