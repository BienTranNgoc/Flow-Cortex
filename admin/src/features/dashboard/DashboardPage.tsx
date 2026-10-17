import { ListAdminRunsStatus } from '@flow-cortex/api-client';
import { Link } from 'react-router-dom';
import { Timestamp } from '../../app/AdminList';
import { usePageTitle } from '../../app/usePageTitle';
import { useAdminRuns } from '../runs/useAdminRuns';
import { useUsersQuery } from '../users/useUsers';

const RUN_STATES = [
  { label: 'Running', status: ListAdminRunsStatus.running, tone: 'running' },
  { label: 'Completed', status: ListAdminRunsStatus.completed, tone: 'completed' },
  { label: 'Failed', status: ListAdminRunsStatus.error, tone: 'failed' },
] as const;

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
    usersQuery.isFetching || allRuns.isFetching || statusQueries.some((query) => query.isFetching);

  return (
    <main className="dashboard-page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">Flow-Cortex operations</p>
          <h1>Dashboard</h1>
          <p className="page-description">A live snapshot of accounts and workflow activity.</p>
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
          {isRefreshing ? 'Refreshing…' : 'Refresh'}
        </button>
      </div>

      {(usersQuery.isError || allRuns.isError) && (
        <div className="list-error" role="alert">
          <p>Some dashboard data could not be loaded.</p>
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
            Retry
          </button>
        </div>
      )}

      <section className="metric-grid" aria-label="Account statistics">
        <article className="metric-card">
          <span>Total users</span>
          <strong>{usersQuery.data ? users.length : '—'}</strong>
          <small>Across all account statuses</small>
        </article>
        <article className="metric-card">
          <span>Active users</span>
          <strong>
            {usersQuery.data ? users.filter((user) => user.status === 'ACTIVE').length : '—'}
          </strong>
          <small>Accounts currently enabled</small>
        </article>
        <article className="metric-card">
          <span>New in 7 days</span>
          <strong>{usersQuery.data ? newUsers : '—'}</strong>
          <small>Based on account registration date</small>
        </article>
        <article className="metric-card">
          <span>Total workflow runs</span>
          <strong>{allRuns.data?.total ?? '—'}</strong>
          <small>All workflows, all time</small>
        </article>
      </section>

      <div className="dashboard-grid">
        <section className="dashboard-panel">
          <div className="section-heading">
            <div>
              <h2>Run status</h2>
              <p>
                Totals reported by the admin run filters; approval-waiting runs are not included
                here.
              </p>
            </div>
            <Link className="table-link" to="/runs">
              View runs
            </Link>
          </div>
          <div className="run-status-list">
            {RUN_STATES.map((state, index) => {
              const query = statusQueries[index];
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
                    <span>{state.label}</span>
                    <strong>{query?.data ? total : '—'}</strong>
                  </div>
                  <div
                    className="status-track"
                    role="img"
                    aria-label={
                      query?.data && allRuns.data
                        ? `${state.label}: ${percent}% of all workflow runs`
                        : `${state.label} run count unavailable`
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
            <p className="stale-note">Run status counts are temporarily unavailable.</p>
          )}
        </section>

        <section className="dashboard-panel">
          <div className="section-heading">
            <div>
              <h2>Recent failures</h2>
              <p>Most recent runs reported with an error</p>
            </div>
            <Link className="table-link" to="/runs">
              All runs
            </Link>
          </div>
          {failedRuns.isLoading && <output>Loading failed runs…</output>}
          {failedRuns.isError && (
            <div className="list-error">
              <p role="alert">Could not load recent failures.</p>
              <button type="button" onClick={() => void failedRuns.refetch()}>
                Retry
              </button>
            </div>
          )}
          {failedRuns.data && failedItems.length === 0 && (
            <p className="list-empty">No failed runs.</p>
          )}
          {failedItems.length > 0 && (
            <ul className="failure-list">
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
    </main>
  );
}
