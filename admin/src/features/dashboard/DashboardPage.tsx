import { ListAdminRunsStatus } from '@flow-cortex/api-client';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { RefreshCw } from 'lucide-react';
import { EmptyState } from '../../app/EmptyState';
import { MetricCard } from '../../app/MetricCard';
import { PageHeading } from '../../app/PageHeading';
import { StatusPill } from '../../app/StatusPill';
import { Timestamp } from '../../app/AdminList';
import { usePageTitle } from '../../app/usePageTitle';
import { formatNumber } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import { useAdminRuns } from '../runs/useAdminRuns';
import { useUsersQuery } from '../users/useUsers';

const RUN_STATES = [
  { labelKey: 'dashboard.runStatus.running', status: ListAdminRunsStatus.running, tone: 'info' },
  {
    labelKey: 'dashboard.runStatus.completed',
    status: ListAdminRunsStatus.completed,
    tone: 'success',
  },
  { labelKey: 'dashboard.runStatus.failed', status: ListAdminRunsStatus.error, tone: 'error' },
] as const;

const RUN_FILL_CLASS = {
  info: 'running',
  success: 'completed',
  error: 'failed',
} as const;

function fillClass(tone: keyof typeof RUN_FILL_CLASS): string {
  return `cortex-admin-status-fill--${RUN_FILL_CLASS[tone]}`;
}

export function DashboardPage() {
  const { t } = useTranslation();
  const locale = activeLocale();
  usePageTitle(t('dashboard.title'));
  const usersQuery = useUsersQuery();
  const allRuns = useAdminRuns(0, undefined);
  const runningRuns = useAdminRuns(0, ListAdminRunsStatus.running);
  const completedRuns = useAdminRuns(0, ListAdminRunsStatus.completed);
  const failedRuns = useAdminRuns(0, ListAdminRunsStatus.error);
  const users = usersQuery.data ?? [];
  const cutoff = Date.now() - 7 * 24 * 60 * 60 * 1000;
  const newUsers = users.filter(
    (user) => user.createdAt && Date.parse(user.createdAt) >= cutoff,
  ).length;
  const statusQueries = [runningRuns, completedRuns, failedRuns];
  const failedItems = failedRuns.data?.items.slice(0, 5) ?? [];
  const isRefreshing =
    usersQuery.isFetching ||
    allRuns.isFetching ||
    statusQueries.some((query) => query.isFetching);
  const count = (value: number | undefined) =>
    value === undefined ? '—' : formatNumber(value, locale);

  const refresh = () => {
    void Promise.all([
      usersQuery.refetch(),
      allRuns.refetch(),
      ...statusQueries.map((query) => query.refetch()),
    ]);
  };

  return (
    <div className="cortex-admin-dashboard">
      <PageHeading
        eyebrow={t('dashboard.eyebrow')}
        title={t('dashboard.title')}
        description={t('dashboard.description')}
        actions={
          <button
            type="button"
            className="cortex-admin-btn cortex-admin-btn--ghost"
            onClick={refresh}
            disabled={isRefreshing}
          >
            <RefreshCw size={16} aria-hidden="true" />
            <span>{isRefreshing ? t('dashboard.refreshing') : t('dashboard.refresh')}</span>
          </button>
        }
      />

      {(usersQuery.isError || allRuns.isError) && (
        <div className="cortex-admin-error" role="alert">
          <p>{t('dashboard.loadError')}</p>
          <button type="button" className="cortex-admin-btn" onClick={refresh}>
            {t('dashboard.retry')}
          </button>
        </div>
      )}

      <section className="cortex-admin-metric-grid" aria-label={t('dashboard.metrics.label')}>
        <MetricCard
          label={t('dashboard.metrics.totalUsers')}
          value={count(usersQuery.data ? users.length : undefined)}
          hint={t('dashboard.metrics.totalUsersHint')}
        />
        <MetricCard
          label={t('dashboard.metrics.activeUsers')}
          value={count(
            usersQuery.data ? users.filter((user) => user.status === 'ACTIVE').length : undefined,
          )}
          hint={t('dashboard.metrics.activeUsersHint')}
        />
        <MetricCard
          label={t('dashboard.metrics.newUsers')}
          value={count(usersQuery.data ? newUsers : undefined)}
          hint={t('dashboard.metrics.newUsersHint')}
        />
        <MetricCard
          label={t('dashboard.metrics.totalRuns')}
          value={count(allRuns.data?.total)}
          hint={t('dashboard.metrics.totalRunsHint')}
        />
      </section>

      <div className="cortex-admin-panel-grid">
        <section className="cortex-admin-panel">
          <header className="cortex-admin-section-heading">
            <div>
              <h2>{t('dashboard.runStatus.title')}</h2>
              <p>{t('dashboard.runStatus.description')}</p>
            </div>
            <Link className="cortex-admin-link" to="/runs">
              {t('dashboard.runStatus.viewRuns')}
            </Link>
          </header>
          <div className="cortex-admin-status-list">
            {RUN_STATES.map((state, index) => {
              const query = statusQueries[index];
              const label = t(state.labelKey);
              const total = query?.data?.total ?? 0;
              const allCount = allRuns.data?.total ?? 0;
              const percent =
                allRuns.data && query?.data && allCount > 0
                  ? Math.min(100, Math.round((total / allCount) * 100))
                  : 0;
              return (
                <div key={state.status} className="cortex-admin-status-list__row">
                  <div className="cortex-admin-status-list__label">
                    <StatusPill tone={state.tone} label={label} dot />
                    <strong>{count(query?.data ? total : undefined)}</strong>
                  </div>
                  <div
                    className="cortex-admin-status-track"
                    role="img"
                    aria-label={
                      query?.data && allRuns.data
                        ? t('dashboard.runStatus.share', {
                            label,
                            percent: formatNumber(percent, locale),
                          })
                        : t('dashboard.runStatus.unavailable', { label })
                    }
                  >
                    <span
                      className={`cortex-admin-status-fill ${fillClass(state.tone)}`}
                      style={{ width: `${percent}%` }}
                    />
                  </div>
                </div>
              );
            })}
          </div>
          {statusQueries.some((query) => query.isError) && (
            <p className="cortex-admin-stale">{t('dashboard.runStatus.countsUnavailable')}</p>
          )}
        </section>

        <section className="cortex-admin-panel">
          <header className="cortex-admin-section-heading">
            <div>
              <h2>{t('dashboard.failures.title')}</h2>
              <p>{t('dashboard.failures.description')}</p>
            </div>
            <Link className="cortex-admin-link" to="/runs">
              {t('dashboard.failures.allRuns')}
            </Link>
          </header>
          {failedRuns.isLoading && (
            <output className="cortex-admin-loading">{t('dashboard.failures.loading')}</output>
          )}
          {failedRuns.isError && (
            <div className="cortex-admin-error">
              <p role="alert">{t('dashboard.failures.loadError')}</p>
              <button
                type="button"
                className="cortex-admin-btn"
                onClick={() => void failedRuns.refetch()}
              >
                {t('dashboard.failures.retry')}
              </button>
            </div>
          )}
          {failedRuns.data && failedItems.length === 0 && (
            <EmptyState
              title={t('dashboard.failures.empty')}
              description={t('dashboard.failures.emptyHint')}
            />
          )}
          {failedItems.length > 0 && (
            <ul className="cortex-admin-failure-list">
              {failedItems.map((run) => (
                <li key={run.id}>
                  <div>
                    <strong>{run.workflowName}</strong>
                    <span>{run.ownerEmail ?? t('runs.deletedUser')}</span>
                    <small>{t('dashboard.failures.note')}</small>
                  </div>
                  <Timestamp value={run.startedAt} />
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>
    </div>
  );
}
