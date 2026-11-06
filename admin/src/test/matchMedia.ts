type ChangeListener = (event: MediaQueryListEvent) => void;

const original = Object.getOwnPropertyDescriptor(window, 'matchMedia');

/**
 * jsdom has no `matchMedia`. Installs one whose `matches` is `matches`; `setMatches` flips it and
 * fires every registered `change` listener, as crossing a breakpoint does. Restored after each test.
 */
export function stubMatchMedia(matches: boolean) {
  let current = matches;
  const listeners = new Set<ChangeListener>();
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    writable: true,
    value: (query: string): MediaQueryList =>
      ({
        get matches() {
          return current;
        },
        media: query,
        onchange: null,
        addEventListener: (type: string, listener: ChangeListener) => {
          if (type === 'change') listeners.add(listener);
        },
        removeEventListener: (type: string, listener: ChangeListener) => {
          if (type === 'change') listeners.delete(listener);
        },
        addListener: (listener: ChangeListener) => listeners.add(listener),
        removeListener: (listener: ChangeListener) => listeners.delete(listener),
        dispatchEvent: () => false,
      }) as unknown as MediaQueryList,
  });
  return {
    setMatches(next: boolean) {
      current = next;
      for (const listener of listeners) {
        listener({ matches: next } as MediaQueryListEvent);
      }
    },
    listenerCount: () => listeners.size,
  };
}

afterEach(() => {
  if (original) {
    Object.defineProperty(window, 'matchMedia', original);
  } else {
    Reflect.deleteProperty(window, 'matchMedia');
  }
});
