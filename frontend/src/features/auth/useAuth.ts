import { useEffect, useState, useSyncExternalStore } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ApiError,
  authHeaders,
  ensureSession,
  getToken,
  login as loginRequest,
  logout as logoutRequest,
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
 * False while a signed-out visit is still trying to turn the refresh cookie into a token (OAuth
 * landing, expired access token after a reload) — one attempt per mount, so a failure cannot loop.
 */
export function useSessionReady(): boolean {
  const token = useAuthToken();
  const [attempted, setAttempted] = useState(() => getToken() !== null);
  useEffect(() => {
    if (attempted) return;
    let mounted = true;
    void ensureSession().then(() => {
      if (mounted) setAttempted(true);
    });
    return () => {
      mounted = false;
    };
  }, [attempted]);
  return token !== null || attempted;
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
    mutationFn: (credentials: LoginVariables) => unwrap(loginRequest(credentials)),
    onSuccess: (data, vars) => {
      setToken(data.accessToken ?? null, vars.rememberMe);
      queryClient.invalidateQueries({ queryKey: ['me'] });
    },
  });
}

export type LoginVariables = LoginRequest & { rememberMe: boolean };

export function useRegisterMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: RegisterRequest) =>
      unwrap(registerRequest({ ...input, rememberMe: true })),
    onSuccess: (data) => {
      setToken(data.accessToken ?? null, true);
      queryClient.invalidateQueries({ queryKey: ['me'] });
    },
  });
}

/** Revokes the server session, then forgets the token; local state is cleared even if the call fails. */
export async function logout(): Promise<void> {
  try {
    await logoutRequest();
  } catch {
    // Offline: the refresh cookie stays until it expires, but this browser is signed out regardless.
  } finally {
    setToken(null);
  }
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

export type SubmitDataState = 'idle' | 'pending' | 'success';

/** Resolves the form's `data-state` from a success flag and the mutation's pending flag. */
export function submitState(succeeded: boolean, isPending: boolean): SubmitDataState {
  if (succeeded) return 'success';
  if (isPending) return 'pending';
  return 'idle';
}
