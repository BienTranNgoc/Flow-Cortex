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
          <table className="admin-table" role="table">
            <thead role="rowgroup">
              <tr role="row">
                <th scope="col" role="columnheader">Workflow</th>
                <th scope="col" role="columnheader">Owner</th>
                <th scope="col" role="columnheader">Status</th>
                <th scope="col" role="columnheader">Started</th>
                <th scope="col" role="columnheader">Finished</th>
                <th scope="col" role="columnheader">Error</th>
              </tr>
            </thead>
            <tbody role="rowgroup">
              {runs.map((run) => (
                <tr role="row" key={run.id}>
                  <td role="cell" data-label="Workflow">{run.workflowName}</td>
                  <td role="cell" data-label="Owner">{run.ownerEmail ?? '(deleted user)'}</td>
                  <td role="cell" data-label="Status">{run.status}</td>
                  <td role="cell" data-label="Started">
                    <Timestamp value={run.startedAt} />
                  </td>
                  <td role="cell" data-label="Finished">
                    <Timestamp value={run.finishedAt} />
                  </td>
                  <td role="cell" data-label="Error">{run.errorMessage ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </AdminList>
    </main>
  );
}
