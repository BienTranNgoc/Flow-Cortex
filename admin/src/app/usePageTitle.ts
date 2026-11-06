import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';

export function usePageTitle(page: string): void {
  const { t } = useTranslation();
  const appTitle = t('common.header.title');
  useEffect(() => {
    document.title = `${page} · ${appTitle}`;
  }, [page, appTitle]);
}
