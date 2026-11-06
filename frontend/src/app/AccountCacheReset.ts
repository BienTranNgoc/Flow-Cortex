import { useAuthToken } from '@flow-cortex/api-client';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef } from 'react';

/** The account a token belongs to: its JWT `sub`, or the token itself when it cannot be read. */
function accountOf(token: string | null): string | null {
  if (token === null) return null;
  try {
    const payload = token.split('.')[1] ?? '';
    const json = atob(payload.replace(/-/g, '+').replace(/_/g, '/'));
    const sub: unknown = (JSON.parse(json) as { sub?: unknown }).sub;
    return typeof sub === 'string' ? sub : token;
  } catch {
    return token;
  }
}

/**
 * Calls `onLeave` when the signed-in account changes after one was signed in (sign-out, or
 * another account's token). Signing in from a guest session and a refresh that rotates the token
 * of the same account do not count.
 */
export function useAccountLeft(onLeave: () => void) {
  const account = accountOf(useAuthToken());
  const previous = useRef(account);
  const latest = useRef(onLeave);
  latest.current = onLeave;

  useEffect(() => {
    if (previous.current === account) return;
    const hadAccount = previous.current !== null;
    previous.current = account;
    if (hadAccount) latest.current();
  }, [account]);
}

/** Empties the query cache when the account is left, so no screen shows the previous account's data. */
export function AccountCacheReset() {
  const queryClient = useQueryClient();
  useAccountLeft(() => queryClient.clear());
  return null;
}
