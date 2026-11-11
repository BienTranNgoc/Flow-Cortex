/** What the API client tells the app about the network; the app decides how to show it. */
export type NetworkSignal =
  | { type: 'rate-limited'; waitSeconds: number | null }
  | { type: 'stream-down'; streamId: number }
  | { type: 'stream-up'; streamId: number };

type Listener = (signal: NetworkSignal) => void;

const listeners = new Set<Listener>();

export function subscribeNetworkSignals(listener: Listener): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function emitNetworkSignal(signal: NetworkSignal): void {
  for (const listener of [...listeners]) {
    try {
      listener(signal);
    } catch (error) {
      console.error('network signal listener failed', error);
    }
  }
}

function positiveNumber(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : null;
}

function secondsUntil(instantMs: number, nowMs: number): number | null {
  return Number.isNaN(instantMs) || instantMs <= nowMs
    ? null
    : Math.ceil((instantMs - nowMs) / 1000);
}

/**
 * Seconds a 429 asks the caller to wait: the problem body's positive `retryAfterSeconds`, else its
 * future `retryAt`, else a `Retry-After` header (seconds or HTTP date); null when none is readable.
 */
export function rateLimitWaitSeconds(
  body: unknown,
  retryAfterHeader: string | null,
  nowMs: number,
): number | null {
  const fields = body !== null && typeof body === 'object' ? (body as Record<string, unknown>) : {};
  const named = positiveNumber(fields.retryAfterSeconds);
  if (named !== null) {
    return named;
  }
  if (typeof fields.retryAt === 'string') {
    const wait = secondsUntil(Date.parse(fields.retryAt), nowMs);
    if (wait !== null) {
      return wait;
    }
  }
  const header = retryAfterHeader?.trim();
  if (!header) {
    return null;
  }
  if (/^\d+$/.test(header)) {
    return positiveNumber(Number(header));
  }
  return secondsUntil(Date.parse(header), nowMs);
}
