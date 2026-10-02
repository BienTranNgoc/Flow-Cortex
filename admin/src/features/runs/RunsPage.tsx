import { ListAdminRunsStatus } from '@flow-cortex/api-client';
import { useState } from 'react';
import { AdminList, Timestamp } from '../../app/AdminList';
import { usePageTitle } from '../../app/usePageTitle';
import { useAdminRuns } from './useAdminRuns';

const STATUSES = Object.values(ListAdminRunsStatus);

export function RunsPage() {
  usePageTitle('Runs');
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<ListAdminRunsStatus | ''>('');
  const query = useAdminRuns(page, status || undefined);

  return (
    <main>
      <h1>Runs</h1>
      <AdminList
        query={query}
        noun="runs"
        statuses={STATUSES}
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
                <th scope="col">Workflow</th>
                <th scope="col">Owner</th>
                <th scope="col">Status</th>
                <th scope="col">Started</th>
                <th scope="col">Finished</th>
                <th scope="col">Error</th>
              </tr>
            </thead>
            <tbody>
              {runs.map((run) => (
                <tr key={run.id}>
                  <td data-label="Workflow">{run.workflowName}</td>
                  <td data-label="Owner">{run.ownerEmail ?? '(deleted user)'}</td>
                  <td data-label="Status">{run.status}</td>
                  <td data-label="Started">
                    <Timestamp value={run.startedAt} />
                  </td>
                  <td data-label="Finished">
                    <Timestamp value={run.finishedAt} />
                  </td>
                  <td data-label="Error">{run.errorMessage ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </AdminList>
    </main>
  );
}
