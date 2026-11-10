import { useSyncExternalStore } from 'react';

interface SessionState {
  /** The member left the first-time journey during this page session. */
  exitedThisSession: boolean;
  /** The user menu asked for a replay that the journey owner has not started yet. */
  replayRequested: boolean;
}

const INITIAL: SessionState = { exitedThisSession: false, replayRequested: false };

let state = INITIAL;
const listeners = new Set<() => void>();

function update(patch: Partial<SessionState>) {
  const next = { ...state, ...patch };
  if (
    next.exitedThisSession === state.exitedThisSession &&
    next.replayRequested === state.replayRequested
  ) {
    return;
  }
  state = next;
  for (const listener of [...listeners]) listener();
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function useOnboardingSession(): SessionState {
  return useSyncExternalStore(subscribe, () => state);
}

export function markExited() {
  update({ exitedThisSession: true });
}

export function requestReplay() {
  update({ replayRequested: true });
}

export function consumeReplayRequest() {
  update({ replayRequested: false });
}

/** Forgets the session when another account signs in on the same page. */
export function resetOnboardingSession() {
  update(INITIAL);
}
