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
 * Empties the query cache when the signed-in account changes (sign-out, or another account's
 * token), so no screen shows data fetched for the previous account. A refresh that rotates the
 * token of the same account keeps it.
 */
export function AccountCacheReset() {
  const queryClient = useQueryClient();
  const account = accountOf(useAuthToken());
  const previous = useRef(account);

  useEffect(() => {
    if (previous.current === account) return;
    const hadAccount = previous.current !== null;
    previous.current = account;
    if (hadAccount) queryClient.clear();
  }, [account, queryClient]);

  return null;
}
