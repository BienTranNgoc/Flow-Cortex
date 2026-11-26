import { localeOf } from '@flow-cortex/i18n';
import { useTranslation } from 'react-i18next';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { formatDateTime } from '../../lib/format';
import { hasNextPage, pageOf, slugForDoc } from './legalPaths';
import { useLegalTitle } from './legalTitle';
import { LegalError, LegalLoading, LegalNotAvailable } from './LegalStates';
import { useLegalHistory } from './useLegal';

const ENTRY_TIME: Intl.DateTimeFormatOptions = { dateStyle: 'long', timeStyle: 'short' };

/** `/legal/:doc/history` — the document's publications, newest first, one page at a time. */
export function LegalHistoryPage() {
  const { t, i18n } = useTranslation();
  const locale = localeOf(i18n);
  const { doc = '' } = useParams();
  const [searchParams, setSearchParams] = useSearchParams();
  const page = pageOf(searchParams.get('page'));
  const query = useLegalHistory(doc, page - 1);
  useLegalTitle(t('legal.history.title'));

  if (slugForDoc(doc) === null) return <LegalNotAvailable message={t('legal.document.notAvailable')} />;
  if (query.isPending) return <LegalLoading />;
  if (query.isError && query.data === undefined) {
    return <LegalError message={t('legal.history.error')} onRetry={() => void query.refetch()} />;
  }
  const items = query.data?.items ?? [];
  const hasPrevious = page > 1;
  const hasNext = query.data !== undefined && hasNextPage(page, query.data.size, query.data.total);
  const goTo = (target: number) => setSearchParams(target <= 1 ? {} : { page: String(target) });

  return (
    <section className="legal-history" aria-labelledby="legal-history-title">
      <h1 id="legal-history-title" className="legal-doc__title">
        {t('legal.history.title')}
      </h1>
      <p className="legal-doc__more">
        <Link to={`/legal/${doc}`} className="action-link">
          {t('legal.history.currentLink')}
        </Link>
      </p>
      {items.length === 0 ? (
        <p role="status" className="legal-state legal-state--empty">
          {t('legal.history.empty')}
        </p>
      ) : (
        <ol className="legal-history__list">
          {items.map((entry) => (
            <li key={entry.revisionId}>
              <Link to={`/legal/${doc}/history/${entry.revisionId}`} className="legal-history__entry">
                <time dateTime={entry.publishedAt}>
                  {formatDateTime(entry.publishedAt, locale, ENTRY_TIME)}
                </time>
              </Link>
            </li>
          ))}
        </ol>
      )}
      {(hasPrevious || hasNext) && (
        <nav className="legal-history__pager" aria-label={t('legal.history.pager')}>
          <button type="button" disabled={!hasPrevious} onClick={() => goTo(page - 1)}>
            {t('legal.history.previous')}
          </button>
          <button type="button" disabled={!hasNext} onClick={() => goTo(page + 1)}>
            {t('legal.history.next')}
          </button>
        </nav>
      )}
    </section>
  );
}
