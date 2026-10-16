import { withClientId } from './clientId';
import { getToken, isTokenPersistent, setToken, syncTokenFromStorage } from './tokenStore';

export const REFRESH_PATH = '/api/v1/auth/refresh';
const LOGOUT_PATH = '/api/v1/auth/logout';
const REFRESH_LOCK = 'flowcortex-auth-refresh';

let inFlight: Promise<string | null> | null = null;

/**
 * Exchanges the HttpOnly refresh cookie for a new access token and stores it with the
 * persistence of the token it replaces. Every caller that arrives while a refresh is running
 * shares it, because the server rotates the cookie on each call. A failure clears the token.
 * Uses `fetch` directly: it must never go through the 401-retrying client.
 *
 * Tabs serialise on a Web Lock: two tabs sending the same cookie would trip the server's reuse
 * detection and revoke the session. A tab that finds another tab already stored a new token
 * skips the request.
 */
export function refreshAccessToken(): Promise<string | null> {
  if (inFlight === null) {
    const before = getToken();
    const task = () => requestNewToken(before);
    const run = async () => (navigator.locks ? await navigator.locks.request(REFRESH_LOCK, task) : await task());
    inFlight = run().finally(() => {
      inFlight = null;
    });
  }
  return inFlight;
}

async function requestNewToken(before: string | null): Promise<string | null> {
  syncTokenFromStorage();
  const current = getToken();
  if (current !== null && current !== before) {
    return current;
  }
  const persistent = isTokenPersistent();
  try {
    const response = await fetch(REFRESH_PATH, { method: 'POST', headers: withClientId(REFRESH_PATH) });
    if (response.ok) {
      const { accessToken } = (await response.json()) as { accessToken?: string };
      if (accessToken) {
        setToken(accessToken, persistent);
        return accessToken;
      }
    }
  } catch {
    // Network failure counts as a failed refresh: the caller sees the original 401.
  }
  setToken(null);
  return null;
}

/** Revokes the server session, then forgets the token; local state is cleared even if the call fails. */
export async function signOut(): Promise<void> {
  try {
    await fetch(LOGOUT_PATH, { method: 'POST', headers: withClientId(LOGOUT_PATH) });
  } catch {
    // Offline: the refresh cookie stays until it expires, but this browser is signed out regardless.
  } finally {
    setToken(null);
  }
}

/** Session bootstrap: with no stored token, one refresh attempt; never throws, never retries. */
export async function ensureSession(): Promise<void> {
  if (getToken() === null) {
    await refreshAccessToken();
  }
}
