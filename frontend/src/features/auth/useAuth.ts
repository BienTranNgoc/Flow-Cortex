import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ApiError,
  authHeaders,
  login as loginRequest,
  me as meRequest,
  register as registerRequest,
  setToken,
  unwrap,
  useAuthToken,
} from '@flow-cortex/api-client';
import type { LoginRequest, RegisterRequest } from '@flow-cortex/api-client';
import type { TFunction } from 'i18next';
import { formatDateTime } from '../../lib/format';
import type { Locale } from '../../lib/i18n';

export { signOut as logout, useAuthToken, useSessionReady } from '@flow-cortex/api-client';

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

/** The server's `detail` for a failed request, else the generic retry message; nothing before a failure. */
export function failureMessage(
  mutation: { readonly error: unknown },
  t: TFunction,
): string | undefined {
  if (!mutation.error) return undefined;
  const detail = mutation.error instanceof ApiError ? mutation.error.problem?.detail : undefined;
  return detail || t('common.errors.requestFailed');
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

export type SubmitDataState = 'idle' | 'pending' | 'success';

/** Resolves the form's `data-state` from a success flag and the mutation's pending flag. */
export function submitState(succeeded: boolean, isPending: boolean): SubmitDataState {
  if (succeeded) return 'success';
  if (isPending) return 'pending';
  return 'idle';
}
