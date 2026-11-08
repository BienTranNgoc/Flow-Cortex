/**
 * jsdom has no `matchMedia`. `stubMatchMedia(matches)` installs one whose every query reports
 * `matches`; `setMatches(next)` flips it and fires the registered `change` listeners.
 * The original (or its absence) is restored after each test.
 */
type Listener = (event: MediaQueryListEvent) => void;

interface MatchMediaStub {
  setMatches: (next: boolean) => void;
  listenerCount: () => number;
}

const restorers: Array<() => void> = [];

afterEach(() => {
  while (restorers.length > 0) restorers.pop()?.();
});

export function stubMatchMedia(matches: boolean): MatchMediaStub {
  let current = matches;
  const listeners = new Set<Listener>();
  const original = Object.getOwnPropertyDescriptor(window, 'matchMedia');

  const matchMedia = (query: string): MediaQueryList =>
    ({
      get matches() {
        return current;
      },
      media: query,
      onchange: null,
      addEventListener: (_type: string, listener: Listener) => listeners.add(listener),
      removeEventListener: (_type: string, listener: Listener) => listeners.delete(listener),
      addListener: (listener: Listener) => listeners.add(listener),
      removeListener: (listener: Listener) => listeners.delete(listener),
      dispatchEvent: () => true,
    }) as unknown as MediaQueryList;

  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    writable: true,
    value: matchMedia,
  });
  restorers.push(() => {
    if (original) Object.defineProperty(window, 'matchMedia', original);
    else Reflect.deleteProperty(window, 'matchMedia');
  });

  return {
    setMatches(next) {
      current = next;
      for (const listener of [...listeners]) {
        listener({ matches: next, media: '' } as MediaQueryListEvent);
      }
    },
    listenerCount: () => listeners.size,
  };
}
