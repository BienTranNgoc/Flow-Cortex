import { LanguageToggle } from '@flow-cortex/ui-kit';
import { useTranslation } from 'react-i18next';
import { localeOf, switchLocale } from './createAppI18n';

/** The VI | EN switch bound to the app's i18next instance; labels come from its `common.language` keys. */
export function AppLanguageToggle() {
  const { t, i18n } = useTranslation();
  return (
    <LanguageToggle
      value={localeOf(i18n)}
      onChange={(locale) => switchLocale(i18n, locale)}
      labels={{
        group: t('common.language.groupLabel'),
        vi: t('common.language.vi'),
        en: t('common.language.en'),
      }}
    />
  );
}
