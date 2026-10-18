import { ApiError } from '@flow-cortex/api-client';
import { useTranslation } from 'react-i18next';
import { Timestamp } from '../../app/AdminList';
import { usePageTitle } from '../../app/usePageTitle';
import { useSmsOutbox } from './useSmsOutbox';

/** Messages the development SMS sender wrote instead of sending, so a code can be read back. */
export function SmsOutboxPage() {
  const { t } = useTranslation();
  usePageTitle(t('smsOutbox.title'));
  const query = useSmsOutbox();
  const { data, error } = query;
  const disabled = error instanceof ApiError && error.status === 503;
  const columns = {
    number: t('smsOutbox.columns.number'),
    text: t('smsOutbox.columns.text'),
    sent: t('smsOutbox.columns.sent'),
  };

  return (
    <main>
      <h1>{t('smsOutbox.title')}</h1>
      <div className="list-toolbar">
        <button type="button" onClick={() => query.refetch()}>
          {t('common.shell.list.refresh')}
        </button>
      </div>

      {disabled && <output className="disabled-banner">{t('common.shell.list.disabled')}</output>}
      {error && !disabled && (
        <div className="list-error">
          <p role="alert">{t('smsOutbox.loadError')}</p>
          <button type="button" onClick={() => query.refetch()}>
            {t('common.shell.list.retry')}
          </button>
        </div>
      )}
      {query.isPending && <output>{t('smsOutbox.loading')}</output>}
      {data && error && <p className="stale-note">{t('common.shell.list.stale')}</p>}
      {data?.length === 0 && (
        <div className="list-empty">
          <p>{t('smsOutbox.empty')}</p>
        </div>
      )}
      {data && data.length > 0 && (
        <div className="table-wrapper" aria-busy={query.isFetching}>
          <table className="admin-table">
            <thead>
              <tr>
                <th scope="col">{columns.number}</th>
                <th scope="col">{columns.text}</th>
                <th scope="col">{columns.sent}</th>
              </tr>
            </thead>
            <tbody>
              {data.map((message) => (
                <tr key={message.id}>
                  <td data-label={columns.number}>{message.phone}</td>
                  <td data-label={columns.text}>{message.body}</td>
                  <td data-label={columns.sent}>
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
