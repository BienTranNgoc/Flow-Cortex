import { ListAdminRunsStatus } from '@flow-cortex/api-client';
import { Link } from 'react-router-dom';
import { RefreshCw } from 'lucide-react';
import { EmptyState } from '../../app/EmptyState';
import { MetricCard } from '../../app/MetricCard';
import { PageHeading } from '../../app/PageHeading';
import { StatusPill } from '../../app/StatusPill';
import { Timestamp } from '../../app/AdminList';
import { usePageTitle } from '../../app/usePageTitle';
import { useAdminRuns } from '../runs/useAdminRuns';
import { useUsersQuery } from '../users/useUsers';

const RUN_STATES = [
  { label: 'Running', status: ListAdminRunsStatus.running, tone: 'info' as const },
  { label: 'Completed', status: ListAdminRunsStatus.completed, tone: 'success' as const },
  { label: 'Failed', status: ListAdminRunsStatus.error, tone: 'error' as const },
];

export function DashboardPage() {
  usePageTitle('Dashboard');
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
        eyebrow="Flow-Cortex operations"
        title="Dashboard"
        description="A live snapshot of accounts and workflow activity."
        actions={
          <button
            type="button"
            className="cortex-admin-btn cortex-admin-btn--ghost"
            onClick={refresh}
            disabled={isRefreshing}
          >
            <RefreshCw size={16} aria-hidden="true" />
            <span>{isRefreshing ? 'Refreshing…' : 'Refresh'}</span>
          </button>
        }
      />

      {(usersQuery.isError || allRuns.isError) && (
        <div className="cortex-admin-error" role="alert">
          <p>Some dashboard data could not be loaded.</p>
          <button type="button" className="cortex-admin-btn" onClick={refresh}>
            Retry
          </button>
        </div>
      )}

      <section className="cortex-admin-metric-grid" aria-label="Account statistics">
        <MetricCard
          label="Total users"
          value={usersQuery.data ? users.length : '—'}
          hint="Across all account statuses"
        />
        <MetricCard
          label="Active users"
          value={usersQuery.data ? users.filter((user) => user.status === 'ACTIVE').length : '—'}
          hint="Accounts currently enabled"
        />
        <MetricCard
          label="New in 7 days"
          value={usersQuery.data ? newUsers : '—'}
          hint="Based on registration date"
        />
        <MetricCard
          label="Total workflow runs"
          value={allRuns.data?.total ?? '—'}
          hint="All workflows, all time"
        />
      </section>

      <div className="cortex-admin-panel-grid">
        <section className="cortex-admin-panel">
          <header className="cortex-admin-section-heading">
            <div>
              <h2>Run status</h2>
              <p>Totals reported by the admin run filters.</p>
            </div>
            <Link className="cortex-admin-link" to="/runs">
              View runs →
            </Link>
          </header>
          <div className="cortex-admin-status-list">
            {RUN_STATES.map((state, index) => {
              const query = statusQueries[index];
              const total = query?.data?.total ?? 0;
              const allCount = allRuns.data?.total ?? 0;
              const percent =
                allRuns.data && query?.data && allCount > 0
                  ? Math.min(100, Math.round((total / allCount) * 100))
                  : 0;
              return (
                <div key={state.status} className="cortex-admin-status-list__row">
                  <div className="cortex-admin-status-list__label">
                    <StatusPill tone={state.tone} label={state.label} dot />
                    <strong>{query?.data ? total : '—'}</strong>
                  </div>
                  <div
                    className="cortex-admin-status-track"
                    role="img"
                    aria-label={
                      query?.data && allRuns.data
                        ? `${state.label}: ${percent}% of all workflow runs`
                        : `${state.label} run count unavailable`
                    }
                  >
                    <span
                      className={`cortex-admin-status-fill cortex-admin-status-fill--${state.tone === 'success' ? 'completed' : state.tone === 'error' ? 'failed' : 'running'}`}
                      style={{ width: `${percent}%` }}
                    />
                  </div>
                </div>
              );
            })}
          </div>
          {statusQueries.some((query) => query.isError) && (
            <p className="cortex-admin-stale">Run status counts are temporarily unavailable.</p>
          )}
        </section>

        <section className="cortex-admin-panel">
          <header className="cortex-admin-section-heading">
            <div>
              <h2>Recent failures</h2>
              <p>Most recent runs reported with an error.</p>
            </div>
            <Link className="cortex-admin-link" to="/runs">
              All runs →
            </Link>
          </header>
          {failedRuns.isLoading && <output className="cortex-admin-loading">Loading failed runs…</output>}
          {failedRuns.isError && (
            <div className="cortex-admin-error">
              <p role="alert">Could not load recent failures.</p>
              <button type="button" className="cortex-admin-btn" onClick={() => void failedRuns.refetch()}>
                Retry
              </button>
            </div>
          )}
          {failedRuns.data && failedItems.length === 0 && (
            <EmptyState title="No failed runs." description="Nothing to investigate right now." />
          )}
          {failedItems.length > 0 && (
            <ul className="cortex-admin-failure-list">
              {failedItems.map((run) => (
                <li key={run.id}>
                  <div>
                    <strong>{run.workflowName}</strong>
                    <span>{run.ownerEmail ?? '(deleted user)'}</span>
                    <small>Run failed; open the runs list for details.</small>
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
