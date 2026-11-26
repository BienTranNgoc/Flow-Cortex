import type { BillingReceiptResponse } from '@flow-cortex/api-client';
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';
import { PageHeading } from '../../app/PageHeading';
import { Skeleton } from '../../app/Skeleton';
import { StatusBlock } from '../../app/StatusBlock';
import { formatDateTime, formatNumber } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import { useDownloadReceipt, useReceipt } from './useReceipt';
import './billing.css';

const HISTORY_PATH = '/billing/transactions';
const PAID_AT_FORMAT: Intl.DateTimeFormatOptions = { dateStyle: 'medium', timeStyle: 'medium' };

/** The receipt of one collected payment, read from the stored tax split, with its PDF to download. */
export function ReceiptPage() {
  const { t } = useTranslation();
  const { transactionId = '' } = useParams();
  const lookup = useReceipt(transactionId);
  const detailPath = `${HISTORY_PATH}/${transactionId}`;
  const notFound = lookup.data?.kind === 'notFound';

  return (
    <div className="billing-page">
      <PageHeading
        title={t('billing.receipt.title')}
        actions={
          notFound ? undefined : (
            <Link to={detailPath} className="action-link">
              {t('billing.receipt.back')}
            </Link>
          )
        }
      />

      <section aria-busy={lookup.isPending} aria-label={t('billing.receipt.loading')}>
        {lookup.isPending && <Skeleton shape="card" count={1} />}
        {lookup.isError && (
          <StatusBlock
            kind="error"
            message={t('billing.receipt.loadError')}
            action={{ label: t('billing.receipt.retry'), onClick: () => void lookup.refetch() }}
          />
        )}
        {notFound && (
          <StatusBlock
            kind="empty"
            message={t('billing.receipt.notFound')}
            action={{ label: t('billing.detail.back'), to: HISTORY_PATH }}
          />
        )}
        {lookup.data?.kind === 'notCollected' && (
          <StatusBlock
            kind="empty"
            message={t('billing.receipt.notCollected')}
            action={{ label: t('billing.receipt.notCollectedAction'), to: detailPath }}
          />
        )}
        {lookup.data?.kind === 'found' && (
          <ReceiptDocument receipt={lookup.data.receipt} transactionId={transactionId} />
        )}
      </section>
    </div>
  );
}

function ReceiptDocument({
  receipt,
  transactionId,
}: Readonly<{ receipt: BillingReceiptResponse; transactionId: string }>) {
  const { t } = useTranslation();
  const locale = activeLocale();
  const download = useDownloadReceipt(transactionId);
  const money = (amount: number) => t('billing.amount', { amount: formatNumber(amount, locale) });
  const rate = `${formatNumber(receipt.vatRateBps / 100, locale)}%`;
  const provider = t(`billing.receipt.providers.${receipt.provider}`, {
    defaultValue: receipt.provider,
  });
  const method = t(
    `billing.receipt.methods.${receipt.provider}${receipt.instrument ? `_${receipt.instrument}` : ''}`,
    { defaultValue: provider },
  );
  const seller = receipt.seller;

  const facts: readonly { key: string; label: string; value: ReactNode }[] = [
    {
      key: 'paidAt',
      label: t('billing.receipt.paidAt'),
      value: (
        <time dateTime={receipt.paidAt}>
          {formatDateTime(receipt.paidAt, locale, PAID_AT_FORMAT)}
        </time>
      ),
    },
    { key: 'method', label: t('billing.receipt.method'), value: method },
    {
      key: 'transactionId',
      label: t('billing.receipt.transactionId', { provider }),
      value: receipt.providerTransactionId ?? t('billing.none'),
    },
  ];

  return (
    <article className="receipt-card">
      <header className="receipt-card__head">
        <div className="receipt-card__heading">
          <h2 className="receipt-card__title">{t('billing.receipt.heading')}</h2>
          <p className="receipt-card__order">
            {t('billing.receipt.orderRef')} <strong>{receipt.orderRef}</strong>
          </p>
        </div>
        <button
          type="button"
          className="receipt-card__download"
          disabled={download.isPending}
          onClick={() => download.mutate()}
        >
          {download.isPending ? t('billing.receipt.downloading') : t('billing.receipt.download')}
        </button>
      </header>
      {download.isError && (
        <p className="receipt-card__error" role="alert">
          {t('billing.receipt.downloadFailed')}
        </p>
      )}

      <dl className="receipt-facts">
        {facts.map((fact) => (
          <div key={fact.key} className="receipt-facts__item">
            <dt>{fact.label}</dt>
            <dd>{fact.value}</dd>
          </div>
        ))}
      </dl>

      <section className="receipt-seller" aria-labelledby="receipt-seller-title">
        <h3 id="receipt-seller-title" className="receipt-seller__title">
          {t('billing.receipt.provider')}
        </h3>
        {seller.legalName ? (
          <div className="receipt-seller__body">
            <p className="receipt-seller__name">{seller.legalName}</p>
            {seller.taxCode && (
              <p>
                {t('billing.receipt.taxCode')}: {seller.taxCode}
              </p>
            )}
            {seller.address && <p>{seller.address}</p>}
            {seller.email && <p>{seller.email}</p>}
          </div>
        ) : (
          <p className="receipt-seller__unset">{t('billing.receipt.providerUnset')}</p>
        )}
      </section>

      <table className="receipt-lines">
        <thead>
          <tr>
            <th scope="col">{t('billing.receipt.product')}</th>
            <th scope="col">{t('billing.receipt.quantity')}</th>
            <th scope="col">{t('billing.receipt.unitPrice')}</th>
            <th scope="col">{t('billing.receipt.amount')}</th>
          </tr>
        </thead>
        <tbody>
          {receipt.lines.map((line) => (
            <tr key={line.description} className="receipt-lines__row">
              <td data-column="product">{line.description}</td>
              <td data-column="quantity" data-label={t('billing.receipt.quantity')}>
                {line.quantity}
              </td>
              <td data-column="unitPrice" data-label={t('billing.receipt.unitPrice')}>
                {money(line.unitPriceVnd)}
              </td>
              <td data-column="amount" data-label={t('billing.receipt.amount')}>
                {money(line.amountVnd)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <dl className="receipt-summary">
        <div className="receipt-summary__row">
          <dt>{t('billing.receipt.subtotal')}</dt>
          <dd>{money(receipt.subtotalVnd)}</dd>
        </div>
        <div className="receipt-summary__row">
          <dt>{t('billing.receipt.vat', { rate })}</dt>
          <dd>{money(receipt.vatAmountVnd)}</dd>
        </div>
        <div className="receipt-summary__row receipt-summary__row--total">
          <dt>{t('billing.receipt.total')}</dt>
          <dd>{money(receipt.totalVnd)}</dd>
        </div>
      </dl>
      <p className="receipt-card__words">
        {t('billing.receipt.inWords')}: {receipt.totalInWords}
      </p>
      <p className="receipt-card__note">{t('billing.receipt.note')}</p>
    </article>
  );
}
