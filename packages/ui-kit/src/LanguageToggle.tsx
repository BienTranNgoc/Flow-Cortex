export type ToggleLocale = 'vi' | 'en';

export interface LanguageToggleLabels {
  group: string;
  vi: string;
  en: string;
}

interface LanguageToggleProps {
  value: ToggleLocale;
  onChange: (locale: ToggleLocale) => void;
  labels: LanguageToggleLabels;
}

const OPTIONS: readonly { locale: ToggleLocale; code: string }[] = [
  { locale: 'vi', code: 'VI' },
  { locale: 'en', code: 'EN' },
];

/**
 * VI | EN switch; unstyled apart from its class names, so each app styles it with its own tokens.
 * Each app must define `.visually-hidden`, which carries the full language name of each button.
 */
export function LanguageToggle({ value, onChange, labels }: LanguageToggleProps) {
  return (
    <div role="group" aria-label={labels.group} className="language-toggle">
      {OPTIONS.map(({ locale, code }) => (
        <button
          key={locale}
          type="button"
          className="language-toggle__option"
          aria-pressed={value === locale}
          lang={locale}
          onClick={() => onChange(locale)}
        >
          {code}
          <span className="visually-hidden"> {labels[locale]}</span>
        </button>
      ))}
    </div>
  );
}
