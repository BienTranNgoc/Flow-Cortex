import { localeOf } from '@flow-cortex/i18n';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';
import { LegalDocumentBody } from './LegalDocumentBody';
import { readLegalDocument } from './legalDocument';
import { slugForDoc } from './legalPaths';
import { useLegalTitle } from './legalTitle';
import { LegalError, LegalLoading, LegalNotAvailable } from './LegalStates';
import { usePublishedLegalDocument } from './useLegal';

/** `/legal/:doc` — the published copy of one legal document. */
export function LegalDocumentPage() {
  const { t, i18n } = useTranslation();
  const { doc = '' } = useParams();
  const query = usePublishedLegalDocument(doc);
  const view = query.data ? readLegalDocument(query.data.sections, localeOf(i18n)) : null;
  useLegalTitle(view?.title ?? null);

  if (slugForDoc(doc) === null) return <LegalNotAvailable message={t('legal.document.notAvailable')} />;
  if (query.isPending) return <LegalLoading />;
  if (query.isError && query.data === undefined) {
    return <LegalError message={t('legal.document.error')} onRetry={() => void query.refetch()} />;
  }
  if (!query.data || !view) return <LegalNotAvailable message={t('legal.document.notAvailable')} />;

  return (
    <LegalDocumentBody
      view={view}
      publishedAt={query.data.publishedAt}
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
