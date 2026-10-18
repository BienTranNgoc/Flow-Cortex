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
