import { createAppI18n } from './createAppI18n';
import { translations } from './test/translations';

const STORAGE_KEY = 'flow-cortex.locale';

const originalStorage = {
  global: Object.getOwnPropertyDescriptor(globalThis, 'localStorage'),
  window: Object.getOwnPropertyDescriptor(window, 'localStorage'),
};

function setNavigatorLanguage(...langs: [string, ...string[]]) {
  Object.defineProperty(navigator, 'language', { value: langs[0], configurable: true });
  Object.defineProperty(navigator, 'languages', { value: langs, configurable: true });
}

function installStorage(storage: Storage) {
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
}

function memoryStorage(seed: Record<string, string> = {}): Storage {
  const store = new Map(Object.entries(seed));
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

function throwingStorage(): Storage {
  const fail = () => {
    throw new DOMException('Storage is disabled', 'SecurityError');
  };
  return { length: 0, key: fail, getItem: fail, setItem: fail, removeItem: fail, clear: fail };
}

function restore(target: object, descriptor: PropertyDescriptor | undefined) {
  if (descriptor) {
    Object.defineProperty(target, 'localStorage', descriptor);
  } else {
    Reflect.deleteProperty(target, 'localStorage');
  }
}

describe('createAppI18n', () => {
  beforeEach(() => {
    document.documentElement.removeAttribute('lang');
  });

  afterEach(() => {
    Reflect.deleteProperty(navigator, 'language');
    Reflect.deleteProperty(navigator, 'languages');
    restore(globalThis, originalStorage.global);
    restore(window, originalStorage.window);
  });

  it('opens in the stored language even when the browser prefers Vietnamese', () => {
    installStorage(memoryStorage({ [STORAGE_KEY]: 'en' }));
    setNavigatorLanguage('vi-VN');

    const { activeLocale, i18n } = createAppI18n(translations);

    expect(activeLocale()).toBe('en');
    expect(i18n.t('common.errorView.title')).toBe('Something went wrong');
    expect(document.documentElement.lang).toBe('en');
  });

  it('opens in Vietnamese when nothing is stored and the browser prefers another language', () => {
    installStorage(memoryStorage());
    setNavigatorLanguage('fr-FR');

    const { activeLocale } = createAppI18n(translations);

    expect(activeLocale()).toBe('vi');
    expect(document.documentElement.lang).toBe('vi');
  });

  it('opens in the first browser language the app supports', () => {
    installStorage(memoryStorage());
    setNavigatorLanguage('fr-FR', 'en-US');

    const { activeLocale, i18n } = createAppI18n(translations);

    expect(activeLocale()).toBe('en');
    expect(i18n.t('common.errorView.title')).toBe('Something went wrong');
  });

  it('stores nothing until the user switches language', () => {
    const storage = memoryStorage();
    installStorage(storage);
    setNavigatorLanguage('en-US');

    const { activeLocale, setLocale } = createAppI18n(translations);

    expect(activeLocale()).toBe('en');
    expect(storage.getItem(STORAGE_KEY)).toBeNull();

    setLocale('vi');

    expect(storage.getItem(STORAGE_KEY)).toBe('vi');
  });

  it('marks the document with the base language and stores a switch', () => {
    const storage = memoryStorage();
    installStorage(storage);
    setNavigatorLanguage('en-US');

    const { activeLocale, setLocale } = createAppI18n(translations);

    expect(activeLocale()).toBe('en');
    expect(document.documentElement.lang).toBe('en');

    setLocale('vi');

    expect(document.documentElement.lang).toBe('vi');
    expect(storage.getItem(STORAGE_KEY)).toBe('vi');
  });

  it('detects from the browser and still switches when storage throws', () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    installStorage(throwingStorage());
    setNavigatorLanguage('vi-VN');

    const { activeLocale, setLocale } = createAppI18n(translations);
    expect(activeLocale()).toBe('vi');

    expect(() => setLocale('en')).not.toThrow();

    expect(activeLocale()).toBe('en');
    expect(document.documentElement.lang).toBe('en');
    expect(consoleError).not.toHaveBeenCalled();
    consoleError.mockRestore();
  });

  it('gives every call its own instance', () => {
    installStorage(memoryStorage());
    setNavigatorLanguage('en-US');

    const first = createAppI18n(translations);
    const second = createAppI18n(translations);
    second.setLocale('vi');

    expect(second.i18n).not.toBe(first.i18n);
    expect(first.activeLocale()).toBe('en');
    expect(second.activeLocale()).toBe('vi');
  });
});
