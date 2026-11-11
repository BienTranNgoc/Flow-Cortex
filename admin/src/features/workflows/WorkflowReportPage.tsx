import { ApiError, ListAdminRunsStatus } from '@flow-cortex/api-client';
import { ArrowLeft } from '@phosphor-icons/react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';
import { z } from 'zod';
import { Timestamp } from '../../app/AdminList';
import { BarSeries } from '../../app/BarSeries';
import { MetricCard } from '../../app/MetricCard';
import { PageHeading } from '../../app/PageHeading';
import { can } from '../../app/permissions';
import { StatusPill } from '../../app/StatusPill';
import { usePageTitle } from '../../app/usePageTitle';
import { formatDateTime, formatNumber } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import { useMeQuery } from '../auth/useAuth';
import { runStatusLabel } from '../runs/runStatus';
import { useAdminWorkflowReport } from './useAdminWorkflows';
import { workflowStatusLabel } from './workflowStatus';

const workflowIdSchema = z.string().uuid();
const RUN_STATUSES: readonly string[] = Object.values(ListAdminRunsStatus);

export function WorkflowReportPage() {
  const { t } = useTranslation();
  usePageTitle(t('workflows.report.title'));
  const { workflowId = '' } = useParams();
  const parsedId = workflowIdSchema.safeParse(workflowId);
  const id = parsedId.success ? parsedId.data : '';
  const me = useMeQuery().data;
  const query = useAdminWorkflowReport(id);

  const back = (
    <Link className="cortex-admin-back-link" to="/workflows">
      <ArrowLeft size={16} aria-hidden="true" /> {t('workflows.report.allWorkflows')}
    </Link>
  );

  const failure = query.error instanceof ApiError ? query.error.status : undefined;
  if (!parsedId.success || failure === 404 || failure === 400) {
    return (
      <div>
        {back}
        <h1>{t('workflows.report.notFound')}</h1>
        <p role="alert">{t('workflows.report.notFoundHint')}</p>
      </div>
    );
  }
  if (failure === 503) {
    return (
      <div>
        {back}
        <h1>{t('workflows.report.title')}</h1>
        <output className="cortex-admin-disabled-banner">{t('common.shell.list.disabled')}</output>
      </div>
    );
  }
  if (query.isPending) {
    return (
      <div>
        {back}
        <h1>{t('workflows.report.title')}</h1>
        <output className="cortex-admin-loading">{t('workflows.report.loading')}</output>
      </div>
    );
  }
  if (query.isError || !query.data) {
    return (
      <div>
        {back}
        <h1>{t('workflows.report.title')}</h1>
        <div className="cortex-admin-error">
          <p role="alert">
            {failure === 403 ? t('workflows.report.forbidden') : t('workflows.report.loadError')}
          </p>
          <button type="button" className="cortex-admin-btn" onClick={() => query.refetch()}>
            {t('common.shell.list.retry')}
          </button>
        </div>
      </div>
    );
  }

  const { workflow, countsByStatus, lastRunAt, series } = query.data;
  const locale = activeLocale();
  const total = series.reduce((sum, point) => sum + point.runs, 0);
  const failed = series.reduce((sum, point) => sum + point.failed, 0);
  const lifetimeTotal = Object.values(countsByStatus).reduce((sum, count) => sum + count, 0);
  const statuses = [
    ...RUN_STATUSES,
    ...Object.keys(countsByStatus).filter((status) => !RUN_STATUSES.includes(status)),
  ];
  const formatDay = (day: string) =>
    formatDateTime(`${day}T00:00:00Z`, locale, { month: 'short', day: 'numeric', timeZone: 'UTC' });

  return (
    <div>
      {back}
      <PageHeading
        eyebrow={t('workflows.eyebrow')}
        title={workflow.name}
        description={workflow.ownerEmail ?? t('workflows.deletedUser')}
        actions={
          <>
            <StatusPill
              tone={workflow.status === 'published' ? 'success' : 'muted'}
              label={workflowStatusLabel(t, workflow.status)}
            />
            {can(me, 'workflow.run.view') && (
              <Link
                className="cortex-admin-btn cortex-admin-btn--primary"
                to={`/runs?workflowId=${workflow.id}`}
              >
                {t('workflows.report.viewRuns')}
              </Link>
            )}
          </>
        }
      />

      <section aria-labelledby="report-counts">
        <h2 id="report-counts" className="cortex-admin-visually-hidden">
          {t('workflows.report.counts')}
        </h2>
        <div className="cortex-admin-metric-grid">
          <MetricCard label={t('workflows.report.totalRuns')} value={formatNumber(lifetimeTotal, locale)} />
          {statuses.map((status) => (
            <MetricCard
              key={status}
              label={runStatusLabel(t, status)}
              value={formatNumber(countsByStatus[status] ?? 0, locale)}
            />
          ))}
          <MetricCard
            label={t('workflows.report.lastRun')}
            value={lastRunAt ? <Timestamp value={lastRunAt} /> : t('workflows.report.neverRun')}
          />
        </div>
      </section>

      <section className="cortex-admin-panel" aria-labelledby="report-chart">
        <header className="cortex-admin-section-heading">
          <h2 id="report-chart">{t('workflows.report.chartTitle')}</h2>
        </header>
        <BarSeries
          points={series}
          label={t('workflows.report.chartLabel', {
            total: formatNumber(total, locale),
            failed: formatNumber(failed, locale),
          })}
          headers={{
            day: t('workflows.report.day'),
            runs: t('workflows.report.runs'),
            failed: t('workflows.report.failed'),
          }}
          formatDay={formatDay}
        />
        <p className="cortex-admin-stale">
          {t('workflows.report.chartLegend', {
            total: formatNumber(total, locale),
            failed: formatNumber(failed, locale),
          })}
        </p>
      </section>
    </div>
  );
}
