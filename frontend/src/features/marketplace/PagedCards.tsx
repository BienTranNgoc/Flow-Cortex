import type { UseInfiniteQueryResult } from '@tanstack/react-query';
import { useId, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Skeleton } from '../../app/Skeleton';
import { StatusBlock } from '../../app/StatusBlock';
import { formatNumber } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import { problemDetailOf } from '../../lib/problemMessage';
import './MarketplaceGrid.css';

interface PagedCardsProps<T> {
  readonly query: UseInfiniteQueryResult<{ items: T[]; total: number }, Error>;
  readonly heading: string;
  readonly card: (item: T) => ReactNode;
  readonly itemKey: (item: T) => string;
  /** What shows when the list is empty and the read did not fail. */
  readonly empty: ReactNode;
}

/**
 * A list of cards read page by page: skeletons while the first page loads, the server's message
 * with a retry when a read fails, the number of results in a status region, and "Load more".
 */
export function PagedCards<T>({
  query,
  heading,
  card,
  itemKey,
  empty,
}: Readonly<PagedCardsProps<T>>) {
  const { t } = useTranslation();
  const titleId = useId();
  const items = query.data?.items ?? [];
  const failed = query.isError;
  const retry = () => {
    if (query.isFetchNextPageError) void query.fetchNextPage();
    else void query.refetch();
  };

  return (
    <section className="mp-section" aria-labelledby={titleId} aria-busy={query.isPending}>
      <h2 id={titleId} className="mp-section__title">
        {heading}
      </h2>
      {query.isPending && (
        <div className="mp-grid" aria-hidden="true">
          <Skeleton shape="card" count={6} />
        </div>
      )}
      {query.data && (
        <output className="mp-section__count">
          {t('workflow.marketplace.results.count', {
            count: query.data.total,
            n: formatNumber(query.data.total, activeLocale()),
          })}
        </output>
      )}
      {failed && (
        <StatusBlock
          kind="error"
          message={problemDetailOf(query.error) ?? t('workflow.marketplace.results.loadError')}
          action={{ label: t('common.state.retry'), onClick: retry }}
        />
      )}
      {query.data && items.length === 0 && !failed && empty}
      {items.length > 0 && (
        <ul className="mp-grid">
          {items.map((item) => (
            <li key={itemKey(item)}>{card(item)}</li>
          ))}
        </ul>
      )}
      {query.hasNextPage && !failed && (
        <button
          type="button"
          className="mp-section__more"
          aria-disabled={query.isFetchingNextPage || undefined}
          onClick={() => {
            if (!query.isFetchingNextPage) void query.fetchNextPage();
          }}
        >
          {query.isFetchingNextPage
            ? t('workflow.marketplace.results.loadingMore')
            : t('workflow.marketplace.results.loadMore')}
        </button>
      )}
    </section>
  );
}
