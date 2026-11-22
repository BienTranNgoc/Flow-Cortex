import { localeOf } from '@flow-cortex/i18n';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';
import { LegalDocumentBody } from './LegalDocumentBody';
import { formatPublishedAt, readLegalDocument } from './legalDocument';
import { slugForDoc } from './legalPaths';
import { useLegalTitle } from './legalTitle';
import { LegalError, LegalLoading, LegalNotAvailable } from './LegalStates';
import { useLegalPublication } from './useLegal';

/** `/legal/:doc/history/:id` — one earlier publication, under a notice that it is not the current text. */
export function LegalVersionPage() {
  const { t, i18n } = useTranslation();
  const locale = localeOf(i18n);
  const { doc = '', id = '' } = useParams();
  const query = useLegalPublication(doc, id);
  const view = query.data ? readLegalDocument(query.data.sections, locale) : null;
  useLegalTitle(view?.title ?? null);

  if (slugForDoc(doc) === null) return <LegalNotAvailable message={t('legal.history.notAvailable')} />;
  if (query.isPending) return <LegalLoading />;
  if (query.isError && query.data === undefined) {
    return <LegalError message={t('legal.document.error')} onRetry={() => void query.refetch()} />;
  }
  if (!query.data || !view) return <LegalNotAvailable message={t('legal.history.notAvailable')} />;

  return (
    <LegalDocumentBody
      view={view}
      publishedAt={query.data.publishedAt}
      notice={
        <p role="note" className="legal-notice">
          {t('legal.history.olderNotice', { date: formatPublishedAt(query.data.publishedAt, locale) })}{' '}
          <Link to={`/legal/${doc}`}>{t('legal.history.currentLink')}</Link>
        </p>
      }
      footer={
        <p className="legal-doc__more">
          <Link to={`/legal/${doc}/history`} className="action-link">
            {t('legal.document.earlierVersions')}
          </Link>
        </p>
      }
    />
  );
}
