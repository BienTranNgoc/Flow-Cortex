import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import {
  authHeaders,
  completeTotpSignIn as completeTotpSignInRequest,
  disableTotp as disableTotpRequest,
  enableTotp as enableTotpRequest,
  getToken,
  getTotpStatus as getTotpStatusRequest,
  isTokenPersistent,
  regenerateTotpRecoveryCodes as regenerateRequest,
  setToken,
  startTotpSetup as startTotpSetupRequest,
  unwrap,
  useAuthToken,
} from '@flow-cortex/api-client';

export const TOTP_STATUS_KEY = ['totp-status'] as const;

// A timer longer than this overflows setTimeout's 32-bit delay and fires at once.
const MAX_TIMER_MS = 2_147_483_647;

/** Whether `lockedUntil` is still ahead; flips to false by itself the moment the lock ends. */
export function useLockActive(lockedUntil: Date | null | undefined): boolean {
  const [tick, setTick] = useState(0);
  const end = lockedUntil?.getTime();
  useEffect(() => {
    if (end === undefined) return undefined;
    const remaining = end - Date.now();
    if (remaining <= 0) return undefined;
    // Each firing re-renders and re-arms until the lock is behind us, so a capped delay still ends on time.
    const timer = setTimeout(
      () => setTick((count) => count + 1),
      Math.min(remaining, MAX_TIMER_MS),
    );
    return () => clearTimeout(timer);
  }, [end, tick]);
  return end !== undefined && end > Date.now();
}

/** The signed-in account's own second-factor state; disabled while signed out. */
export function useTotpStatusQuery() {
  const token = useAuthToken();
  return useQuery({
    queryKey: TOTP_STATUS_KEY,
    queryFn: () => unwrap(getTotpStatusRequest(authHeaders())),
    enabled: Boolean(token),
    retry: false,
  });
}

/**
 * Starts (or restarts, while unconfirmed) the setup; the answer carries the secret once. `gcTime: 0` drops
 * the finished mutation from the cache as soon as the caller resets it, so the secret lives in the section.
 */
export function useStartTotpSetupMutation() {
  return useMutation({
    gcTime: 0,
    mutationFn: () => unwrap(startTotpSetupRequest(authHeaders())),
  });
}

/**
 * Confirms the pending secret with a code. The server ends every session of the account and answers with
 * the one that passed the code, so the new token replaces this browser's (keeping where it was stored);
 * the recovery codes in the answer are shown once. `gcTime: 0` keeps them out of the mutation cache.
 */
export function useEnableTotpMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    gcTime: 0,
    mutationFn: (code: string) => unwrap(enableTotpRequest({ code }, authHeaders())),
    onSuccess: (data) => {
      // `/me` is keyed by token. Without an entry for the new one, the gates above the profile fall back
      // to "checking" and unmount the page, and with it the recovery codes that are shown only once.
      const known = queryClient.getQueryData(['me', getToken()]);
      if (known !== undefined) queryClient.setQueryData(['me', data.accessToken], known);
      setToken(data.accessToken, isTokenPersistent());
      void queryClient.invalidateQueries({ queryKey: TOTP_STATUS_KEY });
      void queryClient.invalidateQueries({ queryKey: ['me'] });
    },
  });
}

/** Turns TOTP off for a code or recovery code; the sessions stay. */
export function useDisableTotpMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (code: string) => unwrap(disableTotpRequest({ code }, authHeaders())),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: TOTP_STATUS_KEY });
      void queryClient.invalidateQueries({ queryKey: ['me'] });
    },
  });
}

/** Replaces the recovery codes for a TOTP code; the answer carries the new codes once. */
export function useRegenerateRecoveryCodesMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    gcTime: 0,
    mutationFn: (code: string) => unwrap(regenerateRequest({ code }, authHeaders())),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: TOTP_STATUS_KEY }),
  });
}

/**
 * Completes a sign-in that owes a code. The request carries no bearer token: the challenge cookie the
 * login set identifies it. The answer's `persistent` says where the token is stored, like a password login.
 */
export function useCompleteTotpSignInMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (code: string) => unwrap(completeTotpSignInRequest({ code })),
    onSuccess: (data) => {
      setToken(data.accessToken, data.persistent);
      void queryClient.invalidateQueries({ queryKey: ['me'] });
    },
  });
}
