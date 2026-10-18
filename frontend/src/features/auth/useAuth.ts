import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ApiError,
  authHeaders,
  confirmEmail as confirmEmailRequest,
  login as loginRequest,
  me as meRequest,
  openPasswordLink as openPasswordLinkRequest,
  register as registerRequest,
  resendConfirmation as resendConfirmationRequest,
  setPassword as setPasswordRequest,
  setToken,
  unwrap,
  useAuthToken,
} from '@flow-cortex/api-client';
import type {
  ConfirmEmailResponse,
  LoginRequest,
  RegisterRequest,
  SetPasswordRequest,
} from '@flow-cortex/api-client';

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

/** `confirmation_sent` (202) leaves the visitor signed out until the emailed link is opened. */
export type RegisterOutcome = 'signed_in' | 'confirmation_sent';

export function useRegisterMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: Omit<RegisterRequest, 'rememberMe'>): Promise<RegisterOutcome> => {
      const data = await unwrap(registerRequest({ ...input, rememberMe: true }));
      if (!('accessToken' in data)) {
        return 'confirmation_sent';
      }
      setToken(data.accessToken ?? null, true);
      queryClient.invalidateQueries({ queryKey: ['me'] });
      return 'signed_in';
    },
  });
}

/** What an opened emailed link leads to: a password to set, with the one-time token it needs. */
export interface OpenedLink {
  passwordSetupRequired: boolean;
  passwordSetupToken: string | null;
}

/**
 * Spends an emailed confirmation link; the server signs the member in for this browser session only.
 * Confirming drops the password chosen at sign-up, so the result usually asks for a new one.
 */
export function useConfirmEmailMutation() {
  return useLinkMutation((token) => unwrap(confirmEmailRequest({ token })));
}

/** Spends an emailed set-password link; like confirming, it signs in and hands out a setup token. */
export function useOpenPasswordLinkMutation() {
  return useLinkMutation((token) => unwrap(openPasswordLinkRequest({ token })));
}

function useLinkMutation(spend: (token: string) => Promise<object>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (token: string): Promise<OpenedLink> => {
      const data = await spend(token);
      const opened = 'accessToken' in data ? (data as ConfirmEmailResponse) : null;
      setToken(opened?.accessToken ?? null, false);
      return {
        passwordSetupRequired: opened?.passwordSetupRequired ?? false,
        passwordSetupToken: opened?.passwordSetupToken ?? null,
      };
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['me'] });
    },
  });
}

export type SetPasswordVariables = Omit<SetPasswordRequest, 'username'> & { username?: string };

/**
 * The signed-in account's first password, with the names and username chosen alongside it. The server
 * answers 409 once it has one, 422 when the setup token is spent or expired; on success it ends every
 * other session and this one continues on the token it returns.
 */
export function useSetPasswordMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: SetPasswordVariables) => unwrap(setPasswordRequest(input, authHeaders())),
    onSuccess: (data) => {
      setToken('accessToken' in data ? (data.accessToken ?? null) : null, false);
      queryClient.invalidateQueries({ queryKey: ['me'] });
    },
  });
}

/** Always answered 202 by the server, so success says nothing about whether the email exists. */
export function useResendConfirmationMutation() {
  return useMutation({
    mutationFn: (email: string) => unwrap(resendConfirmationRequest({ email })),
  });
}

export const EMAIL_NOT_CONFIRMED = 'EMAIL_NOT_CONFIRMED';
export const CONFIRMATION_LINK_INVALID = 'CONFIRMATION_LINK_INVALID';
export const PASSWORD_LINK_INVALID = 'PASSWORD_LINK_INVALID';
export const PASSWORD_SETUP_TOKEN_INVALID = 'PASSWORD_SETUP_TOKEN_INVALID';

/** The problem's `code` extension member, when the error is an API refusal that carries one. */
export function problemCodeFrom(error: unknown): unknown {
  const problem = error instanceof ApiError ? error.problem : undefined;
  return problem && 'code' in problem ? problem.code : undefined;
}

/** Field name to message from a 400/409 problem's `errors`; empty for any other error. */
export function fieldErrorsFrom(error: unknown): Record<string, string> {
  return (error instanceof ApiError ? error.problem?.errors : undefined) ?? {};
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
