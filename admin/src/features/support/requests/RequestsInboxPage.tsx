import {
  ListAdminSupportRequestsStatus,
  ListAdminSupportRequestsType,
} from '@flow-cortex/api-client';
import { useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { AdminList } from '../../../app/AdminList';
import { usePageTitle } from '../../../app/usePageTitle';
import './requests.css';
import { RequestTime } from './RequestTime';
import { RequesterLabel, StatusBadge } from './StatusBadge';
import { useRequestsQuery } from './useAdminRequests';

const STATUSES = Object.values(ListAdminSupportRequestsStatus);
const TYPES = Object.values(ListAdminSupportRequestsType);

export function RequestsInboxPage() {
  const { t } = useTranslation();
  usePageTitle(t('support.requests.inboxTitle'));
  const typeId = useId();
  const typeRef = useRef<HTMLSelectElement>(null);
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<ListAdminSupportRequestsStatus | ''>('');
  const [type, setType] = useState<ListAdminSupportRequestsType | ''>('');
  const query = useRequestsQuery(page, status || undefined, type || undefined);
  const statusLabel = (value: string) =>
    STATUSES.includes(value as ListAdminSupportRequestsStatus)
      ? t(`support.requests.status.${value}`)
      : value;

  return (
    <main>
      <div className="cortex-admin-page-heading">
        <div>
          <p className="cortex-admin-eyebrow">{t('support.requests.eyebrow')}</p>
          <h1>{t('support.requests.inboxTitle')}</h1>
          <p className="cortex-admin-page-description">{t('support.requests.description')}</p>
        </div>
      </div>

      <div className="cortex-admin-toolbar request-type-filter">
        <div className="cortex-admin-field">
          <label htmlFor={typeId}>{t('support.requests.typeFilter')}</label>
          <select
            ref={typeRef}
            id={typeId}
            value={type}
            onChange={(event) => {
              setType(event.target.value as ListAdminSupportRequestsType | '');
              setPage(0);
            }}
          >
            <option value="">{t('support.requests.allTypes')}</option>
            {TYPES.map((value) => (
              <option key={value} value={value}>
                {t(`support.requests.type.${value}`)}
              </option>
            ))}
          </select>
        </div>
        {type && (
          <button
            type="button"
            className="cortex-admin-btn"
            onClick={() => {
              setType('');
              setPage(0);
              // The button disappears with the filter; focus stays on the control it cleared.
              typeRef.current?.focus();
            }}
          >
            {t('support.requests.clearTypeFilter')}
          </button>
        )}
      </div>

      <AdminList
        query={query}
        nounKey="support.requests.noun"
        tableLabelKey="support.requests.tableLabel"
        statuses={STATUSES}
        statusLabel={statusLabel}
        status={status}
        filtered={type !== ''}
        onStatusChange={(next) => {
          setStatus(next as ListAdminSupportRequestsStatus | '');
          setPage(0);
        }}
        onPageChange={setPage}
      >
        {(requests) => (
          <table className="cortex-admin-table">
            <thead>
              <tr>
                <th scope="col">{t('support.requests.columns.number')}</th>
                <th scope="col">{t('support.requests.columns.subject')}</th>
                <th scope="col">{t('support.requests.columns.type')}</th>
                <th scope="col">{t('support.requests.columns.status')}</th>
                <th scope="col">{t('support.requests.columns.requester')}</th>
                <th scope="col">{t('support.requests.columns.activity')}</th>
              </tr>
            </thead>
            <tbody>
              {requests.map((request) => (
                <tr key={request.number}>
                  <td data-label={t('support.requests.columns.number')}>
                    {t('support.requests.number', { number: request.number })}
                  </td>
                  <td data-label={t('support.requests.columns.subject')}>
                    <Link to={`/support/requests/${request.number}`}>{request.subject}</Link>
                  </td>
                  <td data-label={t('support.requests.columns.type')}>
                    {t(`support.requests.type.${request.type}`)}
                  </td>
                  <td data-label={t('support.requests.columns.status')}>
                    <StatusBadge status={request.status} />
                  </td>
                  <td data-label={t('support.requests.columns.requester')}>
                    <RequesterLabel requester={request.requester} />
                  </td>
                  <td data-label={t('support.requests.columns.activity')}>
                    <RequestTime value={request.lastActivityAt} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </AdminList>
    </main>
  );
}
