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

  function start(): void {
    inFlight = true;
    dirty = false;
    new Promise((resolve) => resolve(refetch()))
      // The query keeps a failed refetch in its own error state; the next event tries again.
      .catch(() => undefined)
      .then(() => {
        inFlight = false;
        if (dirty && !stopped) {
          start();
        }
      });
  }

  return {
    request() {
      if (stopped) {
        return;
      }
      if (inFlight) {
        dirty = true;
      } else {
        start();
      }
    },
    stop() {
      stopped = true;
      dirty = false;
    },
  };
}
