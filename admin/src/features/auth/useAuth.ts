import { useEffect, useState, useSyncExternalStore } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  authHeaders,
  ensureSession,
  getToken,
  login as loginRequest,
  logout as logoutRequest,
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

/**
 * False while a signed-out visit is still trying to turn the refresh cookie into a token (expired
 * access token after a reload) — one attempt per mount, so a failure cannot loop.
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
    mutationFn: (input: LoginRequest) => unwrap(loginRequest({ ...input, rememberMe: true })),
    onSuccess: (data) => {
      setToken(data.accessToken ?? null, true);
      queryClient.invalidateQueries({ queryKey: ['me'] });
    },
  });
}

/**
 * Revokes the server session, then forgets the token and every cached result so the next account
 * starts from nothing; local state is cleared even if the call fails.
 */
export async function logout(): Promise<void> {
  try {
    await logoutRequest();
  } catch {
    // Offline: the refresh cookie stays until it expires, but this browser is signed out regardless.
  } finally {
    setToken(null);
    queryClient.clear();
  }
}
