import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { formatNumber } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import { formatCredits } from '../credits/formatCredits';
import { UsageBars } from './UsageBars';
import { useUsageSummaryQuery } from './useRunHistory';

const RANGES = [7, 30, 90] as const;
type Range = (typeof RANGES)[number];

const RANGE_KEY: Record<Range, string> = {
  7: 'runHistory.usage.days7',
  30: 'runHistory.usage.days30',
  90: 'runHistory.usage.days90',
};

/** Totals, daily bars and top workflows over a 7, 30 or 90 day window; credit figures show only while credits are on. */
export function UsageCard() {
  const { t } = useTranslation();
  const [days, setDays] = useState<Range>(30);
  const query = useUsageSummaryQuery(days);
  const summary = query.data;
  const locale = activeLocale();
  const showCredits = summary?.creditsSpent !== undefined && summary.balance !== undefined;

  return (
    <section className="usage-card" aria-labelledby="usage-card-title" aria-busy={query.isFetching}>
      <div className="usage-card__head">
        <h2 id="usage-card-title" className="usage-card__title">
          {t('runHistory.usage.title')}
        </h2>
        <div className="usage-card__range" role="group" aria-label={t('runHistory.usage.range')}>
          {RANGES.map((range) => (
            <button
              key={range}
              type="button"
              className="usage-card__range-button"
              aria-pressed={days === range}
              onClick={() => setDays(range)}
            >
              {t(RANGE_KEY[range])}
            </button>
          ))}
        </div>
      </div>

      {query.isError && !summary ? (
        <div className="usage-card__error" role="alert">
          <p>{t('runHistory.usage.loadError')}</p>
          <button type="button" onClick={() => void query.refetch()}>
            {t('runHistory.page.retry')}
          </button>
        </div>
      ) : !summary ? (
        <div className="skeleton usage-card__skeleton" aria-hidden="true" />
      ) : (
        <>
          <dl className="usage-card__totals">
            <div>
              <dt>{t('runHistory.usage.runs')}</dt>
              <dd>{formatNumber(summary.runs, locale)}</dd>
            </div>
            <div>
              <dt>{t('runHistory.usage.failed')}</dt>
              <dd>
                {formatNumber(summary.failed, locale)}
                <span className="usage-card__rate">
                  {t('runHistory.usage.failureRate', {
                    rate: formatNumber(summary.failureRate, locale, { maximumFractionDigits: 1 }),
                  })}
                </span>
              </dd>
            </div>
            {showCredits && (
              <>
                <div>
                  <dt>{t('runHistory.usage.creditsSpent')}</dt>
                  <dd>{formatCredits(summary.creditsSpent ?? 0, locale)}</dd>
                </div>
                <div>
                  <dt>{t('runHistory.usage.balance')}</dt>
                  <dd>{formatCredits(summary.balance ?? 0, locale)}</dd>
                </div>
              </>
            )}
          </dl>

          <UsageBars series={summary.series} showCredits={showCredits} />

          <div className="usage-card__top">
            <h3 className="usage-card__subtitle">{t('runHistory.usage.topWorkflows')}</h3>
            {summary.topWorkflows.length === 0 ? (
              <p className="usage-card__muted">{t('runHistory.usage.topNone')}</p>
            ) : (
              <ol className="usage-card__top-list">
                {summary.topWorkflows.map((workflow) => (
                  <li key={workflow.workflowId}>
                    <span className="usage-card__top-name">{workflow.name}</span>
                    <span className="usage-card__muted">
                      {t('runHistory.usage.topLine', { runs: formatNumber(workflow.runs, locale) })}
                      {workflow.credits !== undefined &&
                        ` · ${t('runHistory.row.credits', { amount: formatCredits(workflow.credits, locale) })}`}
                      {workflow.averageCredits !== undefined &&
                        ` · ${t('runHistory.usage.topAverage', {
                          amount: formatNumber(workflow.averageCredits, locale, {
                            maximumFractionDigits: 1,
                          }),
                        })}`}
                    </span>
                  </li>
                ))}
              </ol>
            )}
          </div>
        </>
      )}
    </section>
  );
}
