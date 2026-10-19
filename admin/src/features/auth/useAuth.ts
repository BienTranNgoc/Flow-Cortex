import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ApiError,
  authHeaders,
  login as loginRequest,
  me as meRequest,
  setToken,
  signOut,
  unwrap,
  useAuthToken,
} from '@flow-cortex/api-client';
import type { LoginRequest } from '@flow-cortex/api-client';
import type { TFunction } from 'i18next';
import { queryClient } from '../../app/queryClient';
import { formatDateTime } from '../../lib/format';
import type { Locale } from '../../lib/i18n';

export { useAuthToken, useSessionReady } from '@flow-cortex/api-client';

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

const LOCK_TIME_FORMAT: Intl.DateTimeFormatOptions = { dateStyle: 'short', timeStyle: 'short' };

/**
 * In Vietnamese, the account-locked sentence with the lock end in vi-VN form;
 * otherwise nothing, so English keeps the server `detail`.
 */
export function accountLockedMessage(
  error: unknown,
  t: TFunction,
  locale: Locale,
): string | undefined {
  if (locale !== 'vi' || !(error instanceof ApiError)) return undefined;
  const problem = error.problem ?? {};
  if (typeof problem.type !== 'string' || !problem.type.endsWith('account-locked')) {
    return undefined;
  }
  if (typeof problem.lockedUntil !== 'string') return undefined;
  const lockedUntil = new Date(problem.lockedUntil);
  if (Number.isNaN(lockedUntil.getTime())) return undefined;
  return t('auth.login.accountLocked', {
    time: formatDateTime(lockedUntil, 'vi', LOCK_TIME_FORMAT),
  });
}

/** Signs out, then drops every cached result so the next account starts from nothing. */
export async function logout(): Promise<void> {
  await signOut();
  queryClient.clear();
}
