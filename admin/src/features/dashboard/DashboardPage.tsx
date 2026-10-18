import { ListAdminRunsStatus } from '@flow-cortex/api-client';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { Timestamp } from '../../app/AdminList';
import { usePageTitle } from '../../app/usePageTitle';
import { formatNumber } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import { useAdminRuns } from '../runs/useAdminRuns';
import { useUsersQuery } from '../users/useUsers';

const RUN_STATES = [
  { labelKey: 'dashboard.runStatus.running', status: ListAdminRunsStatus.running, tone: 'running' },
  {
    labelKey: 'dashboard.runStatus.completed',
    status: ListAdminRunsStatus.completed,
    tone: 'completed',
  },
  { labelKey: 'dashboard.runStatus.failed', status: ListAdminRunsStatus.error, tone: 'failed' },
] as const;

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
    usersQuery.isFetching || allRuns.isFetching || statusQueries.some((query) => query.isFetching);
  const count = (value: number | undefined) =>
    value === undefined ? '—' : formatNumber(value, locale);

  return (
    <main className="dashboard-page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">{t('dashboard.eyebrow')}</p>
          <h1>{t('dashboard.title')}</h1>
          <p className="page-description">{t('dashboard.description')}</p>
        </div>
        <button
          type="button"
          onClick={() => {
            void Promise.all([
              usersQuery.refetch(),
              allRuns.refetch(),
              ...statusQueries.map((query) => query.refetch()),
            ]);
          }}
          disabled={isRefreshing}
        >
          {isRefreshing ? t('dashboard.refreshing') : t('dashboard.refresh')}
        </button>
      </div>

      {(usersQuery.isError || allRuns.isError) && (
        <div className="list-error" role="alert">
          <p>{t('dashboard.loadError')}</p>
          <button
            type="button"
            onClick={() => {
              void Promise.all([
                usersQuery.refetch(),
                allRuns.refetch(),
                ...statusQueries.map((query) => query.refetch()),
              ]);
            }}
          >
            {t('dashboard.retry')}
          </button>
        </div>
      )}

      <section className="metric-grid" aria-label={t('dashboard.metrics.label')}>
        <article className="metric-card">
          <span>{t('dashboard.metrics.totalUsers')}</span>
          <strong>{count(usersQuery.data ? users.length : undefined)}</strong>
          <small>{t('dashboard.metrics.totalUsersHint')}</small>
        </article>
        <article className="metric-card">
          <span>{t('dashboard.metrics.activeUsers')}</span>
          <strong>
            {count(
              usersQuery.data ? users.filter((user) => user.status === 'ACTIVE').length : undefined,
            )}
          </strong>
          <small>{t('dashboard.metrics.activeUsersHint')}</small>
        </article>
        <article className="metric-card">
          <span>{t('dashboard.metrics.newUsers')}</span>
          <strong>{count(usersQuery.data ? newUsers : undefined)}</strong>
          <small>{t('dashboard.metrics.newUsersHint')}</small>
        </article>
        <article className="metric-card">
          <span>{t('dashboard.metrics.totalRuns')}</span>
          <strong>{count(allRuns.data?.total)}</strong>
          <small>{t('dashboard.metrics.totalRunsHint')}</small>
        </article>
      </section>

      <div className="dashboard-grid">
        <section className="dashboard-panel">
          <div className="section-heading">
            <div>
              <h2>{t('dashboard.runStatus.title')}</h2>
              <p>{t('dashboard.runStatus.description')}</p>
            </div>
            <Link className="table-link" to="/runs">
              {t('dashboard.runStatus.viewRuns')}
            </Link>
          </div>
          <div className="run-status-list">
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
                <div className="run-status-row" key={state.status}>
                  <div className="run-status-label">
                    <span className={`status-dot status-dot-${state.tone}`} aria-hidden="true" />
                    <span>{label}</span>
                    <strong>{count(query?.data ? total : undefined)}</strong>
                  </div>
                  <div
                    className="status-track"
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
                      className={`status-fill status-fill-${state.tone}`}
                      style={{ width: `${percent}%` }}
                    />
                  </div>
                </div>
              );
            })}
          </div>
          {statusQueries.some((query) => query.isError) && (
            <p className="stale-note">{t('dashboard.runStatus.countsUnavailable')}</p>
          )}
        </section>

        <section className="dashboard-panel">
          <div className="section-heading">
            <div>
              <h2>{t('dashboard.failures.title')}</h2>
              <p>{t('dashboard.failures.description')}</p>
            </div>
            <Link className="table-link" to="/runs">
              {t('dashboard.failures.allRuns')}
            </Link>
          </div>
          {failedRuns.isLoading && <output>{t('dashboard.failures.loading')}</output>}
          {failedRuns.isError && (
            <div className="list-error">
              <p role="alert">{t('dashboard.failures.loadError')}</p>
              <button type="button" onClick={() => void failedRuns.refetch()}>
                {t('dashboard.failures.retry')}
              </button>
            </div>
          )}
          {failedRuns.data && failedItems.length === 0 && (
            <p className="list-empty">{t('dashboard.failures.empty')}</p>
          )}
          {failedItems.length > 0 && (
            <ul className="failure-list">
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
    </main>
  );
}
