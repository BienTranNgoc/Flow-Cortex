/**
 * Plain (non-React) token store: the single source of truth for "am I signed
 * in", readable from both React components (via {@link subscribeToken} +
 * {@code useSyncExternalStore}) and imperative code outside the component
 * tree (the API client's 401 handling, `src/api/client.ts`) — a query's
 * `onError` runs during a query lifecycle, not a component render, so a
 * plain React Context alone would not reach it.
 */
const STORAGE_KEY = 'flowcortex.auth.token';

type Listener = () => void;

const listeners = new Set<Listener>();

let token: string | null = readStoredToken();

function readStoredToken(): string | null {
  try {
    return window.localStorage.getItem(STORAGE_KEY);
  } catch {
    // localStorage unavailable (private mode / disabled) — start signed out.
    return null;
  }
}

export function getToken(): string | null {
  return token;
}

export function setToken(next: string | null): void {
  token = next;
  try {
    if (next) {
      window.localStorage.setItem(STORAGE_KEY, next);
    } else {
      window.localStorage.removeItem(STORAGE_KEY);
    }
  } catch {
    // In-memory token still works for this tab even if persistence fails.
  }
  listeners.forEach((listener) => listener());
}

export function subscribeToken(listener: Listener): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}
