import type { TransactionDetailResponse } from '@flow-cortex/api-client';
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';
import { PageHeading } from '../../app/PageHeading';
import { Skeleton } from '../../app/Skeleton';
import { StatusBlock } from '../../app/StatusBlock';
import { formatDateTime, formatNumber } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import { StatusChip } from './StatusChip';
import { useTransaction } from './useTransactions';
import './billing.css';

const HISTORY_PATH = '/billing/transactions';
const CODED_EVENTS: readonly string[] = ['payment_failed', 'payment_expired'];
const EVENT_TIME_FORMAT: Intl.DateTimeFormatOptions = { dateStyle: 'medium', timeStyle: 'medium' };

/** One payment: its facts above the ordered timeline, with the provider code of a failed or expired outcome. */
export function TransactionDetailPage() {
  const { t } = useTranslation();
  const { transactionId = '' } = useParams();
  const lookup = useTransaction(transactionId);
  const notFound = lookup.data?.kind === 'notFound';

  return (
    <div className="billing-page">
      <PageHeading
        title={t('billing.detail.title')}
        actions={
          notFound ? undefined : (
            <Link to={HISTORY_PATH} className="action-link">
              {t('billing.detail.back')}
            </Link>
          )
        }
      />

      <section aria-busy={lookup.isPending}>
        {lookup.isPending && <Skeleton shape="card" count={1} />}
        {lookup.isError && (
          <StatusBlock
            kind="error"
            message={t('billing.detail.loadError')}
            action={{ label: t('billing.detail.retry'), onClick: () => void lookup.refetch() }}
          />
        )}
        {notFound && (
          <StatusBlock
            kind="empty"
            message={t('billing.detail.notFound')}
            action={{ label: t('billing.detail.back'), to: HISTORY_PATH }}
          />
        )}
        {lookup.data?.kind === 'found' && (
          <TransactionFacts transaction={lookup.data.transaction} />
        )}
      </section>
    </div>
  );
}

function TransactionFacts({ transaction }: Readonly<{ transaction: TransactionDetailResponse }>) {
  const { t } = useTranslation();
  const locale = activeLocale();
  const none = t('billing.none');
  const dateTime = (value: string) => (
    <time dateTime={value}>{formatDateTime(value, locale, EVENT_TIME_FORMAT)}</time>
  );

  const facts: readonly { key: string; value: ReactNode }[] = [
    { key: 'description', value: transaction.description },
    { key: 'kind', value: t(`billing.kind.${transaction.kind}`) },
    {
      key: 'amount',
      value: t('billing.amount', { amount: formatNumber(transaction.amountVnd, locale) }),
    },
    { key: 'status', value: <StatusChip status={transaction.status} /> },
    { key: 'openedAt', value: dateTime(transaction.openedAt) },
    { key: 'paidAt', value: transaction.succeededAt ? dateTime(transaction.succeededAt) : none },
    { key: 'payType', value: transaction.providerPayType ?? none },
    { key: 'orderRef', value: transaction.orderRef },
    { key: 'providerTransactionId', value: transaction.providerTransactionId ?? none },
  ];

  return (
    <>
      {transaction.succeededAt && (
        <p className="billing-receipt-link">
          <Link to={`${HISTORY_PATH}/${transaction.id}/receipt`} className="action-link">
            {t('billing.receipt.view')}
          </Link>
        </p>
      )}
      <dl className="billing-facts" aria-label={t('billing.detail.factsLabel')}>
        {facts.map((fact) => (
          <div key={fact.key} className="billing-facts__item">
            <dt>{t(`billing.detail.fact.${fact.key}`)}</dt>
            <dd>{fact.value}</dd>
          </div>
        ))}
      </dl>

      <section className="billing-timeline" aria-labelledby="billing-timeline-title">
        <h2 id="billing-timeline-title" className="billing-timeline__title">
          {t('billing.detail.timeline.title')}
        </h2>
        {transaction.timeline.length === 0 ? (
          <p className="billing-timeline__empty">{t('billing.detail.timeline.empty')}</p>
        ) : (
          <ol className="billing-timeline__list">
            {transaction.timeline.map((event) => (
              <li key={`${event.at}-${event.kind}`} className="billing-timeline__item">
                <span className="billing-timeline__label">
                  {t(`billing.detail.timeline.${event.kind}`)}
                </span>
                {CODED_EVENTS.includes(event.kind) && event.code && (
                  <span className="billing-timeline__code">
                    {t('billing.detail.timeline.code', { code: event.code })}
                  </span>
                )}
                <span className="billing-timeline__time">{dateTime(event.at)}</span>
              </li>
            ))}
          </ol>
        )}
      </section>
    </>
  );
}
