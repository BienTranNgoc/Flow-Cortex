import { useSyncExternalStore } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ApiError,
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
    mutationFn: ({ remember: _remember, ...credentials }: LoginVariables) =>
      unwrap(loginRequest(credentials)),
    onSuccess: (data, vars) => {
      setToken(data.token ?? null, vars.remember);
      queryClient.invalidateQueries({ queryKey: ['me'] });
    },
  });
}

type LoginVariables = LoginRequest & { remember: boolean };
export type { LoginVariables };

export function useRegisterMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: RegisterRequest) => unwrap(registerRequest(input)),
    onSuccess: (data) => {
      setToken(data.token ?? null, true);
      queryClient.invalidateQueries({ queryKey: ['me'] });
    },
  });
}

export function logout(): void {
  setToken(null);
}

const REMEMBERED_EMAIL_KEY = 'flowcortex.auth.rememberedEmail';

export function getRememberedEmail(): string {
  try {
    return window.localStorage.getItem(REMEMBERED_EMAIL_KEY) ?? '';
  } catch {
    return '';
  }
}

export function setRememberedEmail(email: string): void {
  try {
    window.localStorage.setItem(REMEMBERED_EMAIL_KEY, email);
  } catch {
    // private mode / disabled — skip silently
  }
}

export function clearRememberedEmail(): void {
  try {
    window.localStorage.removeItem(REMEMBERED_EMAIL_KEY);
  } catch {
    // private mode / disabled — skip silently
  }
}

export function detailFrom(mutation: { readonly error: unknown }): string | undefined {
  return mutation.error instanceof ApiError ? mutation.error.problem?.detail : undefined;
}
