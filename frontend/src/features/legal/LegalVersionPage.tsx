import { localeOf } from '@flow-cortex/i18n';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';
import { LegalDocumentBody } from './LegalDocumentBody';
import { formatPublishedAt, readLegalDocument } from './legalDocument';
import { slugForDoc } from './legalPaths';
import { useLegalTitle } from './legalTitle';
import { LegalError, LegalLoading, LegalNotAvailable } from './LegalStates';
import { useLegalPublication, usePublishedLegalDocument } from './useLegal';

// The published copy exposes no revision id; a publish stamps both it and its history entry from one
// clock reading, so the instants match. Null while the published copy is loading or unreadable.
function isCurrentPublication(
  publishedAt: string,
  current: { publishedAt: string } | null | undefined,
) {
  if (!current) return null;
  return Date.parse(publishedAt) === Date.parse(current.publishedAt);
}

/** `/legal/:doc/history/:id` — one publication, under a notice that says whether it is the current text. */
export function LegalVersionPage() {
  const { t, i18n } = useTranslation();
  const locale = localeOf(i18n);
  const { doc = '', id = '' } = useParams();
  const query = useLegalPublication(doc, id);
  const currentCopy = usePublishedLegalDocument(doc);
  const view = query.data ? readLegalDocument(query.data.sections, locale) : null;
  useLegalTitle(view?.title ?? null);

  if (slugForDoc(doc) === null) return <LegalNotAvailable message={t('legal.history.notAvailable')} />;
  if (query.isPending) return <LegalLoading />;
  if (query.isError && query.data === undefined) {
    return <LegalError message={t('legal.document.error')} onRetry={() => void query.refetch()} />;
  }
  if (!query.data || !view) return <LegalNotAvailable message={t('legal.history.notAvailable')} />;

  const date = formatPublishedAt(query.data.publishedAt, locale);
  const isCurrent = isCurrentPublication(query.data.publishedAt, currentCopy.data);

  return (
    <LegalDocumentBody
      view={view}
      publishedAt={query.data.publishedAt}
      notice={
        isCurrent === null ? null : (
          <p role="note" className="legal-notice">
            {isCurrent ? (
              t('legal.history.currentNotice', { date })
            ) : (
              <>
                {t('legal.history.olderNotice', { date })}{' '}
                <Link to={`/legal/${doc}`}>{t('legal.history.currentLink')}</Link>
              </>
            )}
          </p>
        )
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
