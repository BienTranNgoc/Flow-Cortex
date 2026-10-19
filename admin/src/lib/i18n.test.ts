const originalStorage = {
  global: Object.getOwnPropertyDescriptor(globalThis, 'localStorage'),
  window: Object.getOwnPropertyDescriptor(window, 'localStorage'),
};

function restore(target: object, descriptor: PropertyDescriptor | undefined) {
  if (descriptor) {
    Object.defineProperty(target, 'localStorage', descriptor);
  } else {
    Reflect.deleteProperty(target, 'localStorage');
  }
}

function emptyStorage(): Storage {
  const store = new Map<string, string>();
  return {
    get length() {
      return store.size;
    },
    key: (i: number) => [...store.keys()][i] ?? null,
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => {
      store.set(k, String(v));
    },
    removeItem: (k: string) => {
      store.delete(k);
    },
    clear: () => store.clear(),
  };
}

describe('i18n', () => {
  afterEach(() => {
    Reflect.deleteProperty(navigator, 'language');
    Reflect.deleteProperty(navigator, 'languages');
    restore(globalThis, originalStorage.global);
    restore(window, originalStorage.window);
  });

  it('opens in English when nothing is stored and the browser prefers en-US, storing only a switch', async () => {
    const storage = emptyStorage();
    Object.defineProperty(globalThis, 'localStorage', {
      value: storage,
      configurable: true,
      writable: true,
    });
    Object.defineProperty(window, 'localStorage', {
      value: storage,
      configurable: true,
      writable: true,
    });
    Object.defineProperty(navigator, 'language', { value: 'en-US', configurable: true });
    Object.defineProperty(navigator, 'languages', { value: ['en-US'], configurable: true });
    document.documentElement.removeAttribute('lang');

    vi.resetModules();
    const { activeLocale, setLocale, default: i18n } = await import('./i18n');

    expect(activeLocale()).toBe('en');
    expect(i18n.t('common.errorView.title')).toBe('Something went wrong');
    expect(document.documentElement.lang).toBe('en');
    expect(storage.getItem('flow-cortex.locale')).toBeNull();

    setLocale('vi');

    expect(storage.getItem('flow-cortex.locale')).toBe('vi');
  });
});
