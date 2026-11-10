import { subscribeNetworkSignals, type NetworkSignal } from '@flow-cortex/api-client';
import { useEffect, useState } from 'react';

export type NetworkBannerMode = 'offline' | 'rate-limited' | 'reconnecting';

export interface NetworkState {
  online: boolean;
  rateLimitedUntil: number;
  downStreams: ReadonlySet<number>;
}

const MIN_NOTICE_MS = 4000;
const MAX_NOTICE_SECONDS = 60;

/** Offline wins over rate-limited, and rate-limited over reconnecting. */
export function networkBannerMode(state: NetworkState, nowMs: number): NetworkBannerMode | null {
  if (!state.online) return 'offline';
  if (state.rateLimitedUntil > nowMs) return 'rate-limited';
  if (state.downStreams.size > 0) return 'reconnecting';
  return null;
}

/** How long a 429 notice shows; null for a named wait over a minute (a quota), which shows none. */
export function rateLimitNoticeMs(waitSeconds: number | null): number | null {
  if (waitSeconds === null) return MIN_NOTICE_MS;
  if (waitSeconds > MAX_NOTICE_SECONDS) return null;
  return Math.max(MIN_NOTICE_MS, waitSeconds * 1000);
}

export function extendRateLimit(
  current: number,
  waitSeconds: number | null,
  nowMs: number,
): number {
  const duration = rateLimitNoticeMs(waitSeconds);
  return duration === null ? current : Math.max(current, nowMs + duration);
}

export function applySignal(
  downStreams: ReadonlySet<number>,
  signal: NetworkSignal,
): ReadonlySet<number> {
  if (signal.type === 'stream-down') {
    if (downStreams.has(signal.streamId)) return downStreams;
    return new Set(downStreams).add(signal.streamId);
  }
  if (signal.type === 'stream-up' && downStreams.has(signal.streamId)) {
    const next = new Set(downStreams);
    next.delete(signal.streamId);
    return next;
  }
  return downStreams;
}

function browserOnline(): boolean {
  return typeof navigator === 'undefined' || navigator.onLine;
}

/** The banner mode to show now: browser connectivity, the 429 notice window and the live streams. */
export function useNetworkStatus(): NetworkBannerMode | null {
  const [online, setOnline] = useState(browserOnline);
  const [rateLimitedUntil, setRateLimitedUntil] = useState(0);
  const [downStreams, setDownStreams] = useState<ReadonlySet<number>>(() => new Set());
  const [tick, setTick] = useState(0);

  useEffect(() => {
    const goOnline = () => setOnline(true);
    const goOffline = () => setOnline(false);
    window.addEventListener('online', goOnline);
    window.addEventListener('offline', goOffline);
    const unsubscribe = subscribeNetworkSignals((signal) => {
      if (signal.type === 'rate-limited') {
        setRateLimitedUntil((current) => extendRateLimit(current, signal.waitSeconds, Date.now()));
      } else {
        setDownStreams((current) => applySignal(current, signal));
      }
    });
    return () => {
      window.removeEventListener('online', goOnline);
      window.removeEventListener('offline', goOffline);
      unsubscribe();
    };
  }, []);

  // `tick` re-arms the timer when it fires a hair early.
  useEffect(() => {
    const remaining = rateLimitedUntil - Date.now();
    if (remaining <= 0) return undefined;
    const timer = window.setTimeout(() => setTick((value) => value + 1), remaining);
    return () => window.clearTimeout(timer);
  }, [rateLimitedUntil, tick]);

  return networkBannerMode({ online, rateLimitedUntil, downStreams }, Date.now());
}
