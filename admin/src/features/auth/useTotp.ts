import { useMutation, useQueryClient } from '@tanstack/react-query';
import {
  authHeaders,
  completeTotpSignIn,
  enableTotp,
  setToken,
  startTotpSetup,
  unwrap,
} from '@flow-cortex/api-client';

/** What the setup call returns: the only response that ever carries the secret. */
export interface TotpSetup {
  secret: string;
  otpauthUri: string;
  qrDataUrl: string;
}

/** What turning TOTP on returns: the recovery codes (shown once) and the token of the session that passed TOTP. */
export interface TotpEnabled {
  recoveryCodes: string[];
  accessToken: string;
  expiresIn: number;
}

/** What a completed code step returns; `persistent` is the remember-me of the sign-in it completes. */
export interface TotpSignedIn {
  accessToken: string;
  expiresIn: number;
  persistent: boolean;
}

/**
 * The generated client types only the error bodies of these three operations, so the 2xx body is read
 * through the shape the controller returns; a non-2xx still throws as an `ApiError`.
 */
async function successBody<T>(response: Promise<{ status: number; data: unknown }>): Promise<T> {
  return (await unwrap(response)) as T;
}

/** Creates (or replaces) the account's pending secret. */
export function useStartTotpSetupMutation() {
  return useMutation({
    mutationFn: () => successBody<TotpSetup>(startTotpSetup(authHeaders())),
  });
}

/**
 * Confirms the pending secret. The returned token is not stored here: storing it ends the setup
 * screen, which must stay up until the recovery codes were read.
 */
export function useEnableTotpMutation() {
  return useMutation({
    mutationFn: (code: string) => successBody<TotpEnabled>(enableTotp({ code }, authHeaders())),
  });
}

/** Completes a sign-in that owes a code; the challenge rides in its own cookie, so no bearer token is sent. */
export function useCompleteTotpSignInMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (code: string) => successBody<TotpSignedIn>(completeTotpSignIn({ code })),
    onSuccess: (data) => {
      setToken(data.accessToken, data.persistent);
      queryClient.invalidateQueries({ queryKey: ['me'] });
    },
  });
}
