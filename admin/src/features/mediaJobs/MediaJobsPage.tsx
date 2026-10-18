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
          <table className="cortex-admin-table">
            <thead>
              <tr>
                <th scope="col">Job id</th>
                <th scope="col">Run</th>
                <th scope="col">Step</th>
                <th scope="col">Capability</th>
                <th scope="col">Status</th>
                <th scope="col">Detail</th>
                <th scope="col">Attempt</th>
                <th scope="col">Delivery count</th>
                <th scope="col">Picked up</th>
                <th scope="col">Queue deadline</th>
                <th scope="col">Lease until</th>
                <th scope="col">Run deadline</th>
              </tr>
            </thead>
            <tbody>
              {jobs.map((job) => {
                const detail = jobDetail(job, now);
                return (
                  <tr key={job.jobId}>
                    <td data-label="Job id">
                      <code className="cortex-admin-table-job-id">{job.jobId}</code>
                    </td>
                    <td data-label="Run">
                      <code className="cortex-admin-table-job-id">{job.runId}</code>
                    </td>
                    <td data-label="Step">{job.stepOrder}</td>
                    <td data-label="Capability">{job.capability}</td>
                    <td data-label="Status">{job.status}</td>
                    <td data-label="Detail" data-testid="job-detail">
                      {detail.marker ? (
                        <strong className="cortex-admin-table-job-marker">{detail.text}</strong>
                      ) : (
                        <span>{detail.text}</span>
                      )}
                      {detail.message && (
                        <span className="cortex-admin-table-job-message">{detail.message}</span>
                      )}
                    </td>
                    <td data-label="Attempt" data-testid="job-attempt">
                      {job.attempt ?? '—'}
                    </td>
                    <td data-label="Delivery count" data-testid="job-deliveries">
                      {job.deliveryCount ?? '—'}
                    </td>
                    <td data-label="Picked up">
                      <Timestamp value={job.pickedUpAt} />
                    </td>
                    <td data-label="Queue deadline">
                      <Timestamp value={job.queueDeadline} />
                    </td>
                    <td data-label="Lease until">
                      <Timestamp value={job.leaseUntil} />
                    </td>
                    <td data-label="Run deadline">
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
