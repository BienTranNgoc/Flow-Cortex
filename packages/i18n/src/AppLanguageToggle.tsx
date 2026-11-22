import { LanguageToggle } from '@flow-cortex/ui-kit';
import { useTranslation } from 'react-i18next';
import { localeOf, switchLocale } from './createAppI18n';
import type { Locale } from './locale';

interface AppLanguageToggleProps {
  /** Called after the language has switched, so an app can save the choice. */
  readonly onSwitch?: (locale: Locale) => void;
}

/** The VI | EN switch bound to the app's i18next instance; labels come from its `common.language` keys. */
export function AppLanguageToggle({ onSwitch }: Readonly<AppLanguageToggleProps>) {
  const { t, i18n } = useTranslation();
  return (
    <LanguageToggle
      value={localeOf(i18n)}
      onChange={(locale) => {
        switchLocale(i18n, locale);
        onSwitch?.(locale);
      }}
      labels={{
        group: t('common.language.groupLabel'),
        vi: t('common.language.vi'),
        en: t('common.language.en'),
      }}
    />
  );
}
