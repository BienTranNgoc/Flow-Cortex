import { ApiError } from '@flow-cortex/api-client';
import { Timestamp } from '../../app/AdminList';
import { usePageTitle } from '../../app/usePageTitle';
import { useSmsOutbox } from './useSmsOutbox';

/** Messages the development SMS sender wrote instead of sending, so a code can be read back. */
export function SmsOutboxPage() {
  usePageTitle('SMS outbox');
  const query = useSmsOutbox();
  const { data, error } = query;
  const disabled = error instanceof ApiError && error.status === 503;

  return (
    <main>
      <h1>SMS outbox</h1>
      <div className="list-toolbar">
        <button type="button" onClick={() => query.refetch()}>
          Refresh
        </button>
      </div>

      {disabled && <output className="disabled-banner">Admin console is disabled</output>}
      {error && !disabled && (
        <div className="list-error">
          <p role="alert">Could not load SMS messages.</p>
          <button type="button" onClick={() => query.refetch()}>
            Retry
          </button>
        </div>
      )}
      {query.isPending && <output>Loading SMS messages…</output>}
      {data && error && <p className="stale-note">Showing the results loaded before this error.</p>}
      {data?.length === 0 && (
        <div className="list-empty">
          <p>No SMS messages yet.</p>
        </div>
      )}
      {data && data.length > 0 && (
        <div className="table-wrapper" aria-busy={query.isFetching}>
          <table className="admin-table">
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
    </main>
  );
}
