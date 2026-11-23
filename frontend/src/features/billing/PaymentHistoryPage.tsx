import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { PageHeading } from '../../app/PageHeading';
import { Skeleton } from '../../app/Skeleton';
import { StatusBlock } from '../../app/StatusBlock';
import { formatDateTime, formatNumber } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import { StatusChip } from './StatusChip';
import { useTransactionList } from './useTransactions';
import './billing.css';

const COLUMNS = ['date', 'description', 'kind', 'amount', 'status'] as const;
const OPENED_AT_FORMAT: Intl.DateTimeFormatOptions = { dateStyle: 'medium', timeStyle: 'short' };

/** The member's payments, newest first, 20 a page, with a switch to keep only the settled ones. */
export function PaymentHistoryPage() {
  const { t } = useTranslation();
  const locale = activeLocale();
  const [page, setPage] = useState(0);
  const [settled, setSettled] = useState(false);
  const list = useTransactionList(page, settled);

  const rows = list.data?.data ?? [];
  const total = list.data?.meta.total ?? 0;
  const size = list.data?.meta.size ?? 1;
  const pages = Math.max(1, Math.ceil(total / size));
  const refreshing = list.isPlaceholderData || list.isFetching;

  const onSettledChange = (next: boolean) => {
    setSettled(next);
    setPage(0);
  };

  return (
    <div className="billing-page">
      <PageHeading title={t('billing.history.title')} lede={t('billing.history.lede')} />

      <div className="billing-toolbar">
        <label className="billing-switch">
          <input
            type="checkbox"
            role="switch"
            checked={settled}
            onChange={(event) => onSettledChange(event.target.checked)}
          />
          <span>{t('billing.history.settledOnly')}</span>
        </label>
      </div>

      <section aria-busy={refreshing} className="billing-list">
        {list.isPending && <Skeleton shape="row" count={5} />}
        {list.isError && (
          <StatusBlock
            kind="error"
            message={t('billing.history.loadError')}
            action={{ label: t('billing.history.retry'), onClick: () => void list.refetch() }}
          />
        )}
        {list.isSuccess && total === 0 && (
          <div className="status-block">
            <p className="status-block__message">
              {t(settled ? 'billing.history.emptySettled' : 'billing.history.empty')}
            </p>
          </div>
        )}
        {list.isSuccess && total > 0 && (
          <>
            {/* The roles are stated because below 768px CSS makes each row a card, which drops native table semantics in some browsers. */}
            <table
              className="billing-table"
              role="table"
              aria-label={t('billing.history.tableLabel')}
            >
              <thead role="rowgroup">
                <tr role="row">
                  {COLUMNS.map((column) => (
                    <th key={column} role="columnheader" scope="col" data-column={column}>
                      {t(`billing.history.column.${column}`)}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody role="rowgroup">
                {rows.map((row) => (
                  <tr key={row.id} role="row" className="billing-row">
                    <td role="cell" data-column="date">
                      <time dateTime={row.openedAt}>
                        {formatDateTime(row.openedAt, locale, OPENED_AT_FORMAT)}
                      </time>
                    </td>
                    <td role="cell" data-column="description">
                      <Link to={`/billing/transactions/${row.id}`} className="billing-row__link">
                        {row.description}
                      </Link>
                    </td>
                    <td role="cell" data-column="kind">
                      {t(`billing.kind.${row.kind}`)}
                    </td>
                    <td role="cell" data-column="amount">
                      {t('billing.amount', { amount: formatNumber(row.amountVnd, locale) })}
                    </td>
                    <td role="cell" data-column="status">
                      <StatusChip status={row.status} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>

            <nav className="billing-pagination" aria-label={t('billing.history.pagination.label')}>
              <button
                type="button"
                disabled={page === 0}
                onClick={() => setPage((current) => Math.max(0, current - 1))}
              >
                {t('billing.history.pagination.previous')}
              </button>
              <span className="billing-pagination__position" aria-live="polite">
                {t('billing.history.pagination.position', { page: page + 1, pages })}
              </span>
              <button
                type="button"
                disabled={page + 1 >= pages}
                onClick={() => setPage((current) => current + 1)}
              >
                {t('billing.history.pagination.next')}
              </button>
            </nav>
          </>
        )}
      </section>
    </div>
  );
}
