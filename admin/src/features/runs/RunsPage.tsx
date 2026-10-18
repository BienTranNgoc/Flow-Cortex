import { ListAdminRunsStatus } from '@flow-cortex/api-client';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { AdminList, Timestamp } from '../../app/AdminList';
import { usePageTitle } from '../../app/usePageTitle';
import { runStatusLabel } from './runStatus';
import { useAdminRuns } from './useAdminRuns';

const STATUSES = Object.values(ListAdminRunsStatus);

export function RunsPage() {
  const { t } = useTranslation();
  usePageTitle(t('runs.title'));
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<ListAdminRunsStatus | ''>('');
  const query = useAdminRuns(page, status || undefined);
  const columns = {
    workflow: t('runs.columns.workflow'),
    owner: t('runs.columns.owner'),
    status: t('runs.columns.status'),
    started: t('runs.columns.started'),
    finished: t('runs.columns.finished'),
    error: t('runs.columns.error'),
  };

  return (
    <main>
      <h1>{t('runs.title')}</h1>
      <AdminList
        query={query}
        noun={t('runs.noun')}
        statuses={STATUSES}
        statusLabel={(code) => runStatusLabel(t, code)}
        status={status}
        onStatusChange={(next) => {
          setStatus(next as ListAdminRunsStatus | '');
          setPage(0);
        }}
        onPageChange={setPage}
      >
        {(runs) => (
          <table className="admin-table">
            <thead>
              <tr>
                <th scope="col">{columns.workflow}</th>
                <th scope="col">{columns.owner}</th>
                <th scope="col">{columns.status}</th>
                <th scope="col">{columns.started}</th>
                <th scope="col">{columns.finished}</th>
                <th scope="col">{columns.error}</th>
              </tr>
            </thead>
            <tbody>
              {runs.map((run) => (
                <tr key={run.id}>
                  <td data-label={columns.workflow}>{run.workflowName}</td>
                  <td data-label={columns.owner}>{run.ownerEmail ?? t('runs.deletedUser')}</td>
                  <td data-label={columns.status}>{runStatusLabel(t, run.status)}</td>
                  <td data-label={columns.started}>
                    <Timestamp value={run.startedAt} />
                  </td>
                  <td data-label={columns.finished}>
                    <Timestamp value={run.finishedAt} />
                  </td>
                  <td data-label={columns.error}>{run.errorMessage ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </AdminList>
    </main>
  );
}
