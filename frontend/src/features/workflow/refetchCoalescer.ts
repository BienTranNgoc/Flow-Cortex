export interface RefetchCoalescer {
  /** Refetch now when idle, otherwise once more after the running refetch settles. */
  request: () => void;
  /** Drops the pending trailing refetch and ignores later requests. */
  stop: () => void;
}

/** Runs `refetch` one at a time; `invalidateQueries` either restarts the fetch or loses the event. */
export function createRefetchCoalescer(refetch: () => Promise<unknown>): RefetchCoalescer {
  let inFlight = false;
  let dirty = false;
  let stopped = false;

  async function start(): Promise<void> {
    inFlight = true;
    dirty = false;
    try {
      await refetch();
    } catch {
      // The query keeps a failed refetch in its own error state; the next event tries again.
    }
    inFlight = false;
    if (dirty && !stopped) {
      void start();
    }
  }

  return {
    request() {
      if (stopped) {
        return;
      }
      if (inFlight) {
        dirty = true;
      } else {
        void start();
      }
    },
    stop() {
      stopped = true;
      dirty = false;
    },
  };
}
