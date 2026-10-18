import { ListAdminRunsStatus } from '@flow-cortex/api-client';
import { useState } from 'react';
import { AdminList, Timestamp } from '../../app/AdminList';
import { PageHeading } from '../../app/PageHeading';
import { StatusPill } from '../../app/StatusPill';
import { usePageTitle } from '../../app/usePageTitle';
import { useAdminRuns } from './useAdminRuns';

const STATUSES = Object.values(ListAdminRunsStatus);

const STATUS_TONE: Record<string, 'info' | 'success' | 'error' | 'warning' | 'muted'> = {
  [ListAdminRunsStatus.running]: 'info',
  [ListAdminRunsStatus.completed]: 'success',
  [ListAdminRunsStatus.error]: 'error',
};

export function RunsPage() {
  usePageTitle('Runs');
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<ListAdminRunsStatus | ''>('');
  const query = useAdminRuns(page, status || undefined);

  return (
    <div>
      <PageHeading
        eyebrow="Workflow Ops"
        title="Runs"
        description="Every workflow execution across the platform. Use filters to narrow by status."
      />
      <div className="cortex-admin-panel">
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
            <table className="cortex-admin-table">
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
                    <td data-label="Status">
                      <StatusPill tone={STATUS_TONE[run.status] ?? 'info'} label={run.status} />
                    </td>
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
      </div>
    </div>
  );
}
