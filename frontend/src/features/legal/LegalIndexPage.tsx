import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { useLegalTitle } from './legalTitle';
import { LegalError, LegalLoading } from './LegalStates';
import { useLegalIndex } from './useLegal';

/** `/legal` — the navigation lists the documents; this area says what to do with it, or why it is empty. */
export function LegalIndexPage() {
  const { t } = useTranslation();
  const query = useLegalIndex();
  useLegalTitle(t('legal.index.title'));

  let content: ReactNode;
  if (query.isPending) {
    content = <LegalLoading />;
  } else if (query.isError && query.data === undefined) {
    content = <LegalError message={t('legal.index.error')} onRetry={() => void query.refetch()} />;
  } else if ((query.data?.items.length ?? 0) === 0) {
    content = (
      <p role="status" className="legal-state legal-state--empty">
        {t('legal.index.empty')}
      </p>
    );
  } else {
    content = <p className="legal-index__intro">{t('legal.index.intro')}</p>;
  }

  return (
    <section aria-labelledby="legal-index-title">
      <h1 id="legal-index-title" className="legal-doc__title">
        {t('legal.index.title')}
      </h1>
      {content}
    </section>
  );
}
