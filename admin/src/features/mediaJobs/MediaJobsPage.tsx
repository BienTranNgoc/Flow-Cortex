import { ListAdminMediaJobsStatus } from '@flow-cortex/api-client';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { AdminList, Timestamp } from '../../app/AdminList';
import { jobDetail } from './jobDetail';
import { jobStatusLabel } from './jobStatus';
import { usePageTitle } from '../../app/usePageTitle';
import { useMediaJobs } from './useMediaJobs';

const STATUSES = Object.values(ListAdminMediaJobsStatus);

export function MediaJobsPage() {
  const { t } = useTranslation();
  usePageTitle(t('mediaJobs.title'));
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<ListAdminMediaJobsStatus | ''>('');
  const query = useMediaJobs(page, status || undefined);
  const now = Date.now();
  const columns = {
    jobId: t('mediaJobs.columns.jobId'),
    run: t('mediaJobs.columns.run'),
    step: t('mediaJobs.columns.step'),
    capability: t('mediaJobs.columns.capability'),
    status: t('mediaJobs.columns.status'),
    detail: t('mediaJobs.columns.detail'),
    attempt: t('mediaJobs.columns.attempt'),
    deliveryCount: t('mediaJobs.columns.deliveryCount'),
    pickedUp: t('mediaJobs.columns.pickedUp'),
    queueDeadline: t('mediaJobs.columns.queueDeadline'),
    leaseUntil: t('mediaJobs.columns.leaseUntil'),
    runDeadline: t('mediaJobs.columns.runDeadline'),
  };

  return (
    <main>
      <h1>{t('mediaJobs.title')}</h1>
      <AdminList
        query={query}
        noun={t('mediaJobs.noun')}
        statuses={STATUSES}
        statusLabel={(code) => jobStatusLabel(t, code)}
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
                <th scope="col">{columns.jobId}</th>
                <th scope="col">{columns.run}</th>
                <th scope="col">{columns.step}</th>
                <th scope="col">{columns.capability}</th>
                <th scope="col">{columns.status}</th>
                <th scope="col">{columns.detail}</th>
                <th scope="col">{columns.attempt}</th>
                <th scope="col">{columns.deliveryCount}</th>
                <th scope="col">{columns.pickedUp}</th>
                <th scope="col">{columns.queueDeadline}</th>
                <th scope="col">{columns.leaseUntil}</th>
                <th scope="col">{columns.runDeadline}</th>
              </tr>
            </thead>
            <tbody>
              {jobs.map((job) => {
                const detail = jobDetail(job, now);
                const detailText = 'labelKey' in detail ? t(detail.labelKey) : detail.text;
                return (
                  <tr key={job.jobId}>
                    <td data-label={columns.jobId}>
                      <code className="cortex-admin-table-job-id">{job.jobId}</code>
                    </td>
                    <td data-label={columns.run}>
                      <code className="cortex-admin-table-job-id">{job.runId}</code>
                    </td>
                    <td data-label={columns.step}>{job.stepOrder}</td>
                    <td data-label={columns.capability}>{job.capability}</td>
                    <td data-label={columns.status}>{jobStatusLabel(t, job.status)}</td>
                    <td data-label={columns.detail} data-testid="job-detail">
                      {detail.marker ? (
                        <strong className="cortex-admin-table-job-marker">{detailText}</strong>
                      ) : (
                        <span>{detailText}</span>
                      )}
                      {'message' in detail && detail.message && (
                        <span className="cortex-admin-table-job-message">{detail.message}</span>
                      )}
                    </td>
                    <td data-label={columns.attempt} data-testid="job-attempt">
                      {job.attempt ?? '—'}
                    </td>
                    <td data-label={columns.deliveryCount} data-testid="job-deliveries">
                      {job.deliveryCount ?? '—'}
                    </td>
                    <td data-label={columns.pickedUp}>
                      <Timestamp value={job.pickedUpAt} />
                    </td>
                    <td data-label={columns.queueDeadline}>
                      <Timestamp value={job.queueDeadline} />
                    </td>
                    <td data-label={columns.leaseUntil}>
                      <Timestamp value={job.leaseUntil} />
                    </td>
                    <td data-label={columns.runDeadline}>
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
