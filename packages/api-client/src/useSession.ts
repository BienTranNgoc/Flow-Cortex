import { useEffect, useState, useSyncExternalStore } from 'react';
import { ensureSession } from './session';
import { getToken, subscribeToken } from './tokenStore';

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
