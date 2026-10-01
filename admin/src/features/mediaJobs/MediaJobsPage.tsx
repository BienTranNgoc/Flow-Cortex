import { ListAdminMediaJobsStatus } from '@flow-cortex/api-client';
import { useState } from 'react';
import { AdminList, Timestamp } from '../../app/AdminList';
import { jobDetail } from './jobDetail';
import { usePageTitle } from '../../app/usePageTitle';
import { useMediaJobs } from './useMediaJobs';

const STATUSES = Object.values(ListAdminMediaJobsStatus);

export function MediaJobsPage() {
  usePageTitle('Media jobs');
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<ListAdminMediaJobsStatus | ''>('');
  const query = useMediaJobs(page, status || undefined);
  const now = Date.now();

  return (
    <main>
      <h1>Media jobs</h1>
      <AdminList
        query={query}
        noun="media jobs"
        statuses={STATUSES}
        status={status}
        onStatusChange={(next) => {
          setStatus(next as ListAdminMediaJobsStatus | '');
          setPage(0);
        }}
        onPageChange={setPage}
      >
        {(jobs) => (
          <table className="admin-table" role="table">
            <thead role="rowgroup">
              <tr role="row">
                <th scope="col" role="columnheader">Job id</th>
                <th scope="col" role="columnheader">Run</th>
                <th scope="col" role="columnheader">Step</th>
                <th scope="col" role="columnheader">Capability</th>
                <th scope="col" role="columnheader">Status</th>
                <th scope="col" role="columnheader">Detail</th>
                <th scope="col" role="columnheader">Attempt</th>
                <th scope="col" role="columnheader">Delivery count</th>
                <th scope="col" role="columnheader">Picked up</th>
                <th scope="col" role="columnheader">Queue deadline</th>
                <th scope="col" role="columnheader">Lease until</th>
                <th scope="col" role="columnheader">Run deadline</th>
              </tr>
            </thead>
            <tbody role="rowgroup">
              {jobs.map((job) => {
                const detail = jobDetail(job, now);
                return (
                  <tr role="row" key={job.jobId}>
                    <td role="cell" data-label="Job id">
                      <code className="job-id">{job.jobId}</code>
                    </td>
                    <td role="cell" data-label="Run">
                      <code className="job-id">{job.runId}</code>
                    </td>
                    <td role="cell" data-label="Step">{job.stepOrder}</td>
                    <td role="cell" data-label="Capability">{job.capability}</td>
                    <td role="cell" data-label="Status">{job.status}</td>
                    <td role="cell" data-label="Detail" data-testid="job-detail">
                      {detail.marker ? <strong className="job-marker">{detail.text}</strong> : <span>{detail.text}</span>}
                      {detail.message && <span className="job-message">{detail.message}</span>}
                    </td>
                    <td role="cell" data-label="Attempt" data-testid="job-attempt">
                      {job.attempt ?? '—'}
                    </td>
                    <td role="cell" data-label="Delivery count" data-testid="job-deliveries">
                      {job.deliveryCount ?? '—'}
                    </td>
                    <td role="cell" data-label="Picked up">
                      <Timestamp value={job.pickedUpAt} />
                    </td>
                    <td role="cell" data-label="Queue deadline">
                      <Timestamp value={job.queueDeadline} />
                    </td>
                    <td role="cell" data-label="Lease until">
                      <Timestamp value={job.leaseUntil} />
                    </td>
                    <td role="cell" data-label="Run deadline">
                      <Timestamp value={job.runDeadline} />
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </AdminList>
    </main>
  );
}
