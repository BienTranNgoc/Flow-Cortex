import { LanguageToggle } from '@flow-cortex/ui-kit';
import { useTranslation } from 'react-i18next';
import { activeLocale, setLocale } from './i18n';

export function AppLanguageToggle() {
  const { t } = useTranslation();
  return (
    <LanguageToggle
      value={activeLocale()}
      onChange={setLocale}
      labels={{
        group: t('common.language.groupLabel'),
        vi: t('common.language.vi'),
        en: t('common.language.en'),
      }}
    />
  );
}
