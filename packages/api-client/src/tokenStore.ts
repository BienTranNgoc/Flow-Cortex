/**
 * Plain (non-React) token store: the single source of truth for "am I signed
 * in", readable from both React components (via {@link subscribeToken} +
 * {@code useSyncExternalStore}) and imperative code outside the component
 * tree (the API client's 401 handling, `./client.ts`) — a query's
 * `onError` runs during a query lifecycle, not a component render, so a
 * plain React Context alone would not reach it.
 *
 * <p>Two backends behind one key:
 * <ul>
 *   <li>`localStorage` — token survives closing the tab/browser, used when
 *       the user ticks "Remember me" at sign-in. Stays until JWT expiry or
 *       explicit logout.</li>
 *   <li>`sessionStorage` — token dies with the tab, used when the user
 *       leaves "Remember me" unticked. Refreshing keeps them signed in;
 *       closing the tab does not.</li>
 * </ul>
 * The two backends are checked in order on read; a `null` write clears both.
 */
const PERSISTENT_KEY = 'flowcortex.auth.token';
const SESSION_KEY = 'flowcortex.auth.sessionToken';

type Listener = () => void;

const listeners = new Set<Listener>();

let token: string | null = readStoredToken();
let tokenPersistent = readStoredPersistence();

function readStoredToken(): string | null {
  try {
    return window.localStorage.getItem(PERSISTENT_KEY)
      ?? window.sessionStorage.getItem(SESSION_KEY);
  } catch {
    // localStorage/sessionStorage unavailable (private mode / disabled) — start signed out.
    return null;
  }
}

function readStoredPersistence(): boolean {
  try {
    return window.localStorage.getItem(PERSISTENT_KEY) !== null;
  } catch {
    return false;
  }
}

export function getToken(): string | null {
  return token;
}

/** Whether the current token lives in `localStorage` (the user chose "remember me"). */
export function isTokenPersistent(): boolean {
  return tokenPersistent;
}

export function setToken(next: null): void;
export function setToken(next: string | null, persistent: boolean): void;
export function setToken(next: string | null, persistent = false): void {
  token = next;
  tokenPersistent = next !== null && persistent;
  try {
    window.localStorage.removeItem(PERSISTENT_KEY);
    window.sessionStorage.removeItem(SESSION_KEY);
    if (next) {
      if (persistent) {
        window.localStorage.setItem(PERSISTENT_KEY, next);
      } else {
        window.sessionStorage.setItem(SESSION_KEY, next);
      }
    }
  } catch {
    // In-memory token still works for this tab even if persistence fails.
  }
  listeners.forEach((listener) => listener());
}

/** Adopts a token another tab stored (storage is shared across tabs, memory is not). */
export function syncTokenFromStorage(): void {
  const stored = readStoredToken();
  if (stored !== null && stored !== token) {
    token = stored;
    tokenPersistent = readStoredPersistence();
    listeners.forEach((listener) => listener());
  }
}

export function subscribeToken(listener: Listener): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}
