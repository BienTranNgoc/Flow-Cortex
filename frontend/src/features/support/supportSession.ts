import { newUuidV4 } from './uuid';

/** The guest's support session id: a UUID v4 kept in `localStorage`, sent as `X-Support-Session`. */
export const SUPPORT_SESSION_KEY = 'nodi.support.session';

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

// Used when storage throws (blocked or private mode): the id then lasts for this page load only.
let memoryId: string | null = null;

function readStored(): string | null {
  try {
    return window.localStorage.getItem(SUPPORT_SESSION_KEY);
  } catch {
    return null;
  }
}

function store(id: string): boolean {
  try {
    window.localStorage.setItem(SUPPORT_SESSION_KEY, id);
    return true;
  } catch {
    return false;
  }
}

/** The stored id, or a new one (stored) when it is absent, malformed or unreadable. */
export function supportSessionId(): string {
  const stored = readStored();
  if (stored && UUID_V4.test(stored)) return stored;
  if (memoryId) return memoryId;
  const id = newUuidV4();
  if (!store(id)) memoryId = id;
  return id;
}
