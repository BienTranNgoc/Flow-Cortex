import { ApiError } from '@flow-cortex/api-client';
import { useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { TableRegion } from '../../app/TableRegion';
import { useVatRates, usePublishVatRate } from './useVatRates';
import { VatRateDialog } from './VatRateDialog';
import { bpsToPercent, currentRateOf, vatToday } from './vatRates';

interface VatRatesTabProps {
  /** The caller holds `payment.setting.update`; without it the tab only reads. */
  readonly canUpdate: boolean;
}

/** The VAT rate in force today, how it was set, the whole timeline, and the control that sets a new rate. */
export function VatRatesTab({ canUpdate }: Readonly<VatRatesTabProps>) {
  const { t } = useTranslation();
  const query = useVatRates();
  const publish = usePublishVatRate();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [lastSet, setLastSet] = useState<string | null>(null);
  const openerRef = useRef<HTMLButtonElement>(null);

  const rates = query.data?.rates ?? [];
  const today = vatToday();
  const current = currentRateOf(rates, today);
  const newestFirst = [...rates].sort((a, b) => b.validFrom.localeCompare(a.validFrom));
  const forbidden = query.error instanceof ApiError && query.error.status === 403;

  const closeDialog = () => {
    setDialogOpen(false);
    publish.reset();
    openerRef.current?.focus();
  };

  const refusal = publish.error instanceof ApiError ? publish.error : undefined;
  const fieldError = refusal?.status === 400 ? refusal.problem?.errors?.rateBps : undefined;
  let formError: string | undefined;
  if (publish.isError && fieldError === undefined) {
    formError = refusal?.status === 409 ? refusal.problem?.detail : undefined;
    formError ??= t('paymentSettings.vat.errors.save');
  }

  return (
    <div className="vat-tab">
      {query.isPending && (
        <output className="cortex-admin-loading">{t('paymentSettings.vat.loading')}</output>
      )}
      {forbidden && (
        <div className="cortex-admin-error" role="alert">
          <p>{t('paymentSettings.vat.forbidden')}</p>
        </div>
      )}
      {query.isError && !forbidden && (
        <div className="cortex-admin-error">
          <p>{t('paymentSettings.vat.loadFailed')}</p>
          <button type="button" className="cortex-admin-btn" onClick={() => query.refetch()}>
            {t('paymentSettings.retry')}
          </button>
        </div>
      )}

      {query.isSuccess && (
        <>
          <section className="cortex-admin-panel vat-current" aria-labelledby="vat-current-title">
            <h2 id="vat-current-title">{t('paymentSettings.vat.currentTitle')}</h2>
            {current ? (
              <>
                <p className="vat-current__rate">{bpsToPercent(current.rateBps)}</p>
                <p className="payment-muted">
                  {t('paymentSettings.vat.since', { date: current.validFrom })}
                </p>
                <p className="vat-current__note">
                  {current.sourceNote ?? t('paymentSettings.vat.noNote')}
                </p>
              </>
            ) : (
              <p className="payment-muted">{t('paymentSettings.vat.notSet')}</p>
            )}
            {canUpdate ? (
              <button
                ref={openerRef}
                type="button"
                className="cortex-admin-btn cortex-admin-btn--primary"
                onClick={() => setDialogOpen(true)}
              >
                {t('paymentSettings.vat.setRate')}
              </button>
            ) : (
              <p className="payment-muted">{t('paymentSettings.vat.readOnly')}</p>
            )}
            {lastSet && (
              <output className="payment-muted" aria-live="polite">
                {lastSet}
              </output>
            )}
          </section>

          <section aria-labelledby="vat-timeline-title" className="vat-timeline">
            <h2 id="vat-timeline-title">{t('paymentSettings.vat.timelineTitle')}</h2>
            <TableRegion label={t('paymentSettings.vat.tableLabel')} busy={query.isFetching}>
              <table className="cortex-admin-table">
                <thead>
                  <tr>
                    <th scope="col">{t('paymentSettings.vat.columns.rate')}</th>
                    <th scope="col">{t('paymentSettings.vat.columns.from')}</th>
                    <th scope="col">{t('paymentSettings.vat.columns.to')}</th>
                    <th scope="col">{t('paymentSettings.vat.columns.note')}</th>
                  </tr>
                </thead>
                <tbody>
                  {newestFirst.map((rate) => (
                    <tr key={rate.id} aria-current={rate.id === current?.id ? 'true' : undefined}>
                      <td>{bpsToPercent(rate.rateBps)}</td>
                      <td>{rate.validFrom}</td>
                      <td>{rate.validTo ?? t('paymentSettings.vat.openEnded')}</td>
                      <td className="payment-history__reason">
                        {rate.sourceNote ?? t('paymentSettings.history.noReason')}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </TableRegion>
          </section>
        </>
      )}

      {dialogOpen && (
        <VatRateDialog
          pending={publish.isPending}
          fieldError={fieldError}
          formError={formError}
          onCancel={closeDialog}
          onConfirm={(rateBps, sourceNote) =>
            publish.mutate(
              { rateBps, ...(sourceNote === undefined ? {} : { sourceNote }) },
              {
                onSuccess: () => {
                  setLastSet(
                    t('paymentSettings.vat.rateSet', {
                      rate: bpsToPercent(rateBps),
                      date: vatToday(),
                    }),
                  );
                  closeDialog();
                },
              },
            )
          }
        />
      )}
    </div>
  );
}
