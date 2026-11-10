import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { GetBuilderRunsChartPeriod } from '@flow-cortex/api-client';
import { PageHeading } from '../../app/PageHeading';
import { StatusBlock } from '../../app/StatusBlock';
import { formatNumber } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import { problemDetailOf } from '../../lib/problemMessage';
import { RunsBars } from './RunsBars';
import { useBuilderChartQuery, useBuilderSummaryQuery } from './useBuilderDashboard';
import './BuilderDashboardPage.css';

const PERIODS = [
  GetBuilderRunsChartPeriod['7d'],
  GetBuilderRunsChartPeriod['30d'],
  GetBuilderRunsChartPeriod['90d'],
] as const;

/** `/builder/dashboard` — how many workflows the member owns and how often they run. */
export function BuilderDashboardPage() {
  const { t } = useTranslation();
  const locale = activeLocale();
  const [period, setPeriod] = useState<GetBuilderRunsChartPeriod>(GetBuilderRunsChartPeriod['30d']);
  const summaryQuery = useBuilderSummaryQuery();
  const summary = summaryQuery.data;
  const hasWorkflows = summary !== undefined && summary.totalWorkflows > 0;
  const chartQuery = useBuilderChartQuery(period);

  return (
    <div className="builder-dash">
      <PageHeading
        title={t('workflow.builderDashboard.heading')}
        lede={t('workflow.builderDashboard.lede')}
      />

      <div className="builder-dash__body" aria-busy={summaryQuery.isLoading}>
        {summaryQuery.isLoading && (
          <output className="visually-hidden">{t('workflow.builderDashboard.loading')}</output>
        )}
        {summaryQuery.isError && (
          <StatusBlock
            kind="error"
            message={problemDetailOf(summaryQuery.error) ?? t('workflow.builderDashboard.loadError')}
            action={{ label: t('common.state.retry'), onClick: () => void summaryQuery.refetch() }}
          />
        )}
        {summary !== undefined && !hasWorkflows && (
          <p className="builder-dash__empty">{t('workflow.builderDashboard.noWorkflows')}</p>
        )}

        {hasWorkflows && (
          <>
            <dl className="builder-dash__totals">
              <div>
                <dt>{t('workflow.builderDashboard.totals.workflows')}</dt>
                <dd>{formatNumber(summary.totalWorkflows, locale)}</dd>
              </div>
              <div>
                <dt>{t('workflow.builderDashboard.totals.published')}</dt>
                <dd>{formatNumber(summary.publishedWorkflows, locale)}</dd>
              </div>
              <div>
                <dt>{t('workflow.builderDashboard.totals.runs')}</dt>
                <dd>{formatNumber(summary.totalRuns, locale)}</dd>
              </div>
            </dl>

            <section className="builder-dash__chart" aria-busy={chartQuery.isLoading}>
              <div className="builder-dash__chart-head">
                <h2>{t('workflow.builderDashboard.chart.heading')}</h2>
                <fieldset className="builder-dash__period">
                  <legend className="visually-hidden">
                    {t('workflow.builderDashboard.period.legend')}
                  </legend>
                  {PERIODS.map((value) => (
                    <button
                      key={value}
                      type="button"
                      aria-pressed={period === value}
                      className={`builder-dash__period-btn${period === value ? ' is-active' : ''}`}
                      onClick={() => setPeriod(value)}
                    >
                      {t(`workflow.builderDashboard.period.${value}`)}
                    </button>
                  ))}
                </fieldset>
              </div>

              {chartQuery.isLoading && (
                <output className="visually-hidden">{t('workflow.builderDashboard.loading')}</output>
              )}
              {chartQuery.isError && (
                <StatusBlock
                  kind="error"
                  message={
                    problemDetailOf(chartQuery.error) ?? t('workflow.builderDashboard.loadError')
                  }
                  action={{ label: t('common.state.retry'), onClick: () => void chartQuery.refetch() }}
                />
              )}
              {chartQuery.data && <RunsBars series={chartQuery.data.series} />}
            </section>
          </>
        )}
      </div>
    </div>
  );
}
