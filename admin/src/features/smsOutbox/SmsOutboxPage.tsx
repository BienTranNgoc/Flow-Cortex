import { ApiError } from '@flow-cortex/api-client';
import { Timestamp } from '../../app/AdminList';
import { PageHeading } from '../../app/PageHeading';
import { usePageTitle } from '../../app/usePageTitle';
import { useSmsOutbox } from './useSmsOutbox';

/** Messages the development SMS sender wrote instead of sending, so a code can be read back. */
export function SmsOutboxPage() {
  usePageTitle('SMS outbox');
  const query = useSmsOutbox();
  const { data, error } = query;
  const disabled = error instanceof ApiError && error.status === 503;

  return (
    <div>
      <PageHeading
        eyebrow="Administration"
        title="SMS outbox"
        description="One-time codes the development SMS sender stored instead of sending."
      />
      <div className="cortex-admin-panel">
        <div className="cortex-admin-toolbar">
          <button type="button" className="cortex-admin-btn" onClick={() => query.refetch()}>
            Refresh
          </button>
        </div>

        {disabled && (
          <output className="cortex-admin-disabled-banner">Admin console is disabled</output>
        )}
        {error && !disabled && (
          <div className="cortex-admin-error">
            <p role="alert">Could not load SMS messages.</p>
            <button type="button" className="cortex-admin-btn" onClick={() => query.refetch()}>
              Retry
            </button>
          </div>
        )}
        {query.isPending && <output className="cortex-admin-loading">Loading SMS messages…</output>}
        {data && error && (
          <p className="cortex-admin-stale">Showing the results loaded before this error.</p>
        )}
        {data?.length === 0 && <div className="cortex-admin-empty">No SMS messages yet.</div>}
        {data && data.length > 0 && (
          <div className="cortex-admin-table-wrapper" aria-busy={query.isFetching}>
            <table className="cortex-admin-table">
              <thead>
                <tr>
                  <th scope="col">Number</th>
                  <th scope="col">Text</th>
                  <th scope="col">Sent</th>
                </tr>
              </thead>
              <tbody>
                {data.map((message) => (
                  <tr key={message.id}>
                    <td data-label="Number">{message.phone}</td>
                    <td data-label="Text">{message.body}</td>
                    <td data-label="Sent">
                      <Timestamp value={message.createdAt} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
