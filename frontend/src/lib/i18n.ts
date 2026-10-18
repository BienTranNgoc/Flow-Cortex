import i18next from 'i18next';
import LanguageDetector from 'i18next-browser-languagedetector';
import { initReactI18next } from 'react-i18next';
import enAuth from '../locales/en/auth.json';
import enCommon from '../locales/en/common.json';
import enCredits from '../locales/en/credits.json';
import enDashboard from '../locales/en/dashboard.json';
import enWorkflow from '../locales/en/workflow.json';
import viAuth from '../locales/vi/auth.json';
import viCommon from '../locales/vi/common.json';
import viCredits from '../locales/vi/credits.json';
import viDashboard from '../locales/vi/dashboard.json';
import viWorkflow from '../locales/vi/workflow.json';

export const SUPPORTED_LOCALES = ['vi', 'en'] as const;
export type Locale = (typeof SUPPORTED_LOCALES)[number];

export const STORAGE_KEY = 'flow-cortex.locale';

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

const detector = new LanguageDetector();
detector.addDetector(storedLocale);

const i18n = i18next.createInstance();

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
      vi: {
        translation: {
          common: viCommon,
          auth: viAuth,
          workflow: viWorkflow,
          credits: viCredits,
          dashboard: viDashboard,
        },
      },
      en: {
        translation: {
          common: enCommon,
          auth: enAuth,
          workflow: enWorkflow,
          credits: enCredits,
          dashboard: enDashboard,
        },
      },
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

export default i18n;

export function activeLocale(): Locale {
  const resolved = i18n.resolvedLanguage?.split('-')[0];
  return resolved === 'en' ? 'en' : 'vi';
}

/** Switches the UI language and remembers it; never throws when storage is unavailable. */
export function setLocale(locale: Locale): void {
  void i18n.changeLanguage(locale);
  try {
    window.localStorage.setItem(STORAGE_KEY, locale);
  } catch {
    // The switch still applies to the open tab.
  }
}
