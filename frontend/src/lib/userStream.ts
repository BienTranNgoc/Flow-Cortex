import { openEventStream, type EventStream, type StreamEvent } from '@flow-cortex/api-client';
import { useEffect, useRef } from 'react';

export interface UserStreamFollower {
  /** Every connection the server accepts, the first one and each reconnect. */
  onOpen(): void;
  onEvent(event: StreamEvent): void;
}

const followers = new Set<UserStreamFollower>();
let stream: EventStream | null = null;
// A closed stream can still deliver one late callback; it must not reach the next stream's followers.
let generation = 0;

function isVisible(): boolean {
  return document.visibilityState === 'visible';
}

function sync(): void {
  const wanted = followers.size > 0 && isVisible();
  if (wanted && stream === null) {
    const mine = ++generation;
    stream = openEventStream({
      topics: ['user'],
      onOpen: () => {
        if (mine === generation) [...followers].forEach((follower) => follower.onOpen());
      },
      onEvent: (event) => {
        if (mine === generation) [...followers].forEach((follower) => follower.onEvent(event));
      },
    });
  } else if (!wanted && stream !== null) {
    generation += 1;
    stream.close();
    stream = null;
  }
}

/**
 * Follows the tab's one shared `user` stream: open while the tab is visible and anyone follows,
 * closed when it is hidden or the last follower leaves. Returns the unfollow function.
 */
export function followUserStream(follower: UserStreamFollower): () => void {
  if (followers.size === 0) document.addEventListener('visibilitychange', sync);
  followers.add(follower);
  sync();
  return () => {
    if (!followers.delete(follower)) return;
    if (followers.size === 0) document.removeEventListener('visibilitychange', sync);
    sync();
  };
}

/** Follows the shared `user` stream while `enabled`; the latest `follower` receives each call. */
export function useUserStream(follower: UserStreamFollower, enabled: boolean): void {
  const latest = useRef(follower);
  useEffect(() => {
    latest.current = follower;
  });
  useEffect(() => {
    if (!enabled) return undefined;
    return followUserStream({
      onOpen: () => latest.current.onOpen(),
      onEvent: (event) => latest.current.onEvent(event),
    });
  }, [enabled]);
}
