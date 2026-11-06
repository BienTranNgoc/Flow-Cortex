import i18next, { type i18n as I18n } from 'i18next';
import LanguageDetector from 'i18next-browser-languagedetector';
import { initReactI18next } from 'react-i18next';
import { type Locale, STORAGE_KEY, SUPPORTED_LOCALES } from './locale';

/** One app's translation tree per locale, keyed by area (`common`, `auth`, ...). */
export type AppTranslations = Record<Locale, Record<string, object>>;

export interface AppI18n {
  readonly i18n: I18n;
  readonly activeLocale: () => Locale;
  readonly setLocale: (locale: Locale) => void;
}

// Private browsing and storage-blocking settings make localStorage throw; the browser language then decides.
const storedLocale = {
  name: 'storedLocale',
  lookup(): string | undefined {
    try {
      return window.localStorage.getItem(STORAGE_KEY) ?? undefined;
    } catch {
      return undefined;
    }
  },
};

export function localeOf(i18n: I18n): Locale {
  const resolved = i18n.resolvedLanguage?.split('-')[0];
  return resolved === 'en' ? 'en' : 'vi';
}

/** Switches the UI language and remembers it; never throws when storage is unavailable. */
export function switchLocale(i18n: I18n, locale: Locale): void {
  void i18n.changeLanguage(locale);
  try {
    window.localStorage.setItem(STORAGE_KEY, locale);
  } catch {
    // The switch still applies to the open tab.
  }
}

/** Builds and starts a fresh i18next instance for one app, bound to react-i18next. */
export function createAppI18n(translations: AppTranslations): AppI18n {
  const detector = new LanguageDetector();
  detector.addDetector(storedLocale);

  const i18n = i18next.createInstance();
  const activeLocale = () => localeOf(i18n);

  // Screen readers pick their voice from <html lang>, and browser translation reads it as the source language.
  function applyDocumentLang() {
    document.documentElement.lang = activeLocale();
  }

  i18n.on('languageChanged', applyDocumentLang);

  void i18n
    .use(detector)
    .use(initReactI18next)
    .init({
      resources: {
        vi: { translation: translations.vi },
        en: { translation: translations.en },
      },
      supportedLngs: SUPPORTED_LOCALES,
      fallbackLng: 'vi',
      load: 'languageOnly',
      interpolation: { escapeValue: false },
      detection: {
        order: ['storedLocale', 'navigator'],
        // Only an explicit switch is remembered; a detected language would otherwise pin itself.
        caches: [],
      },
    })
    .then(applyDocumentLang);

  return {
    i18n,
    activeLocale,
    setLocale: (locale) => switchLocale(i18n, locale),
  };
}
