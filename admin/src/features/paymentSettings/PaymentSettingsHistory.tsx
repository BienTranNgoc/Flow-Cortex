import type { PaymentSettingView } from '@flow-cortex/api-client';
import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { EmptyState } from '../../app/EmptyState';
import { TableRegion } from '../../app/TableRegion';
import { formatVietnamTime } from '../support/vietnamTime';
import { settingLabel, valueLabel } from './paymentSettingCopy';
import { HISTORY_PAGE_SIZE, usePaymentSettingHistory } from './usePaymentSettings';

interface PaymentSettingsHistoryProps {
  readonly settings: readonly PaymentSettingView[];
}

export function PaymentSettingsHistory({ settings }: Readonly<PaymentSettingsHistoryProps>) {
  const { t } = useTranslation();
  const filterId = useId();
  const [key, setKey] = useState('');
  const [page, setPage] = useState(0);
  const query = usePaymentSettingHistory({ key, page }, true);
  const data = query.data;
  const pages = data ? Math.max(1, Math.ceil(data.total / HISTORY_PAGE_SIZE)) : 1;

  const show = (setting: string, value: string | null | undefined) =>
    value === null || value === undefined
      ? t('paymentSettings.history.unset')
      : valueLabel(t, setting, value);

  return (
    <div className="payment-history">
      <div className="cortex-admin-toolbar">
        <div className="cortex-admin-field">
          <label htmlFor={filterId}>{t('paymentSettings.history.filterLabel')}</label>
          <select
            id={filterId}
            value={key}
            onChange={(event) => {
              setKey(event.target.value);
              setPage(0);
            }}
          >
            <option value="">{t('paymentSettings.history.filterAll')}</option>
            {settings.map((setting) => (
              <option key={setting.key} value={setting.key}>
                {settingLabel(t, setting.key)}
              </option>
            ))}
          </select>
        </div>
      </div>

      {query.isPending && (
        <output className="cortex-admin-loading">{t('paymentSettings.history.loading')}</output>
      )}
      {query.isError && (
        <div className="cortex-admin-error">
          <p>{t('paymentSettings.history.loadFailed')}</p>
          <button type="button" className="cortex-admin-btn" onClick={() => query.refetch()}>
            {t('paymentSettings.history.retry')}
          </button>
        </div>
      )}
      {data && data.items.length === 0 && (
        <EmptyState
          title={t('paymentSettings.history.emptyTitle')}
          description={t(
            key
              ? 'paymentSettings.history.emptyFilteredDescription'
              : 'paymentSettings.history.emptyDescription',
          )}
        />
      )}
      {data && data.items.length > 0 && (
        <>
          <TableRegion label={t('paymentSettings.history.tableLabel')} busy={query.isFetching}>
            <table className="cortex-admin-table">
              <thead>
                <tr>
                  <th scope="col">{t('paymentSettings.history.columns.time')}</th>
                  <th scope="col">{t('paymentSettings.history.columns.setting')}</th>
                  <th scope="col">{t('paymentSettings.history.columns.change')}</th>
                  <th scope="col">{t('paymentSettings.history.columns.actor')}</th>
                  <th scope="col">{t('paymentSettings.history.columns.reason')}</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((change) => (
                  <tr key={change.id}>
                    <td>{formatVietnamTime(change.changedAt)}</td>
                    <td>{settingLabel(t, change.key)}</td>
                    <td>
                      {t('paymentSettings.history.transition', {
                        from: show(change.key, change.previousValue),
                        to: show(change.key, change.newValue),
                      })}
                    </td>
                    <td>{change.actorEmail ?? t('paymentSettings.history.deletedAccount')}</td>
                    <td className="payment-history__reason">
                      {change.reason ?? t('paymentSettings.history.noReason')}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </TableRegion>
          <div className="payment-history__pager">
            <button
              type="button"
              className="cortex-admin-btn cortex-admin-btn--ghost"
              aria-label={t('paymentSettings.history.previousLabel')}
              disabled={page === 0}
              onClick={() => setPage((value) => value - 1)}
            >
              {t('paymentSettings.history.previous')}
            </button>
            <span className="payment-muted">
              {t('paymentSettings.history.pageOf', { page: page + 1, pages })}
            </span>
            <button
              type="button"
              className="cortex-admin-btn cortex-admin-btn--ghost"
              aria-label={t('paymentSettings.history.nextLabel')}
              disabled={page + 1 >= pages}
              onClick={() => setPage((value) => value + 1)}
            >
              {t('paymentSettings.history.next')}
            </button>
          </div>
        </>
      )}
    </div>
  );
}
