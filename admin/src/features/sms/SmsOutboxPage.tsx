import { useQuery } from '@tanstack/react-query';
import { Copy, Trash2 } from 'lucide-react';
import { PageHeading } from '../../app/PageHeading';
import { StatusPill, type StatusTone } from '../../app/StatusPill';
import { Timestamp } from '../../app/AdminList';
import { usePageTitle } from '../../app/usePageTitle';
import { fetchSmsOutbox, type SmsOutboxEntry } from '../../app/mockAdminApi';

const SMS_TONE: Record<SmsOutboxEntry['status'], StatusTone> = {
  SENT: 'success',
  PENDING: 'info',
  EXPIRED: 'warning',
  FAILED: 'error',
};

export function SmsOutboxPage() {
  usePageTitle('SMS Outbox');
  const query = useQuery({ queryKey: ['admin', 'sms-outbox'], queryFn: fetchSmsOutbox });
  const items = query.data ?? [];

  return (
    <div>
      <PageHeading
        eyebrow="Administration"
        title="SMS Outbox"
        description="Outbound OTP messages for the local dev environment. Copy codes into the verification screen to complete the flow."
        actions={
          <button type="button" className="cortex-admin-btn cortex-admin-btn--danger">
            <Trash2 size={16} aria-hidden="true" />
            <span>Clear outbox</span>
          </button>
        }
      />

      {query.isLoading && <output className="cortex-admin-loading">Loading outbox…</output>}
      {query.isError && (
        <div className="cortex-admin-error">
          <p role="alert">Could not load the outbox.</p>
          <button type="button" className="cortex-admin-btn" onClick={() => query.refetch()}>
            Retry
          </button>
        </div>
      )}

      {items.length > 0 && (
        <div className="cortex-admin-table-wrapper">
          <table className="cortex-admin-table">
            <thead>
              <tr>
                <th scope="col">ID</th>
                <th scope="col">Phone</th>
                <th scope="col">OTP</th>
                <th scope="col">Status</th>
                <th scope="col">Attempts</th>
                <th scope="col">Cooldown</th>
                <th scope="col">Hourly / Daily</th>
                <th scope="col">Created</th>
                <th scope="col">Actions</th>
              </tr>
            </thead>
            <tbody>
              {items.map((s) => (
                <tr key={s.id}>
                  <td data-label="ID">
                    <code className="cortex-admin-table-job-id">{s.id}</code>
                  </td>
                  <td data-label="Phone">
                    <code className="cortex-admin-table-job-id">{s.phone}</code>
                  </td>
                  <td data-label="OTP">
                    <strong style={{ fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace' }}>
                      {s.code}
                    </strong>
                  </td>
                  <td data-label="Status">
                    <StatusPill tone={SMS_TONE[s.status]} label={s.status} />
                  </td>
                  <td data-label="Attempts">
                    {s.attempts} / 5
                  </td>
                  <td data-label="Cooldown">
                    {s.resendCooldownSeconds}s
                  </td>
                  <td data-label="Hourly / Daily">
                    {s.hourlyCount} / {s.dailyCount}
                  </td>
                  <td data-label="Created">
                    <Timestamp value={s.createdAt} />
                  </td>
                  <td data-label="Actions">
                    <button type="button" className="cortex-admin-btn cortex-admin-btn--sm">
                      <Copy size={14} aria-hidden="true" />
                      <span>Copy</span>
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
