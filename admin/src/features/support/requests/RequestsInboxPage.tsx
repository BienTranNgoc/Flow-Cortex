import {
  ListAdminSupportRequestsStatus,
  ListAdminSupportRequestsType,
} from '@flow-cortex/api-client';
import { useId, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { AdminList } from '../../../app/AdminList';
import { usePageTitle } from '../../../app/usePageTitle';
import { supportCopy } from '../supportCopy';
import './requests.css';
import { RequestTime } from './RequestTime';
import { RequesterLabel, StatusBadge } from './StatusBadge';
import { useRequestsQuery } from './useAdminRequests';

const copy = supportCopy.requests;
const STATUSES = Object.values(ListAdminSupportRequestsStatus);
const TYPES = Object.values(ListAdminSupportRequestsType);
const statusLabel = (status: string) =>
  status in copy.status ? copy.status[status as ListAdminSupportRequestsStatus] : status;

export function RequestsInboxPage() {
  usePageTitle(copy.inboxTitle);
  const typeId = useId();
  const typeRef = useRef<HTMLSelectElement>(null);
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<ListAdminSupportRequestsStatus | ''>('');
  const [type, setType] = useState<ListAdminSupportRequestsType | ''>('');
  const query = useRequestsQuery(page, status || undefined, type || undefined);

  return (
    <main>
      <div className="cortex-admin-page-heading">
        <div>
          <p className="cortex-admin-eyebrow">{copy.eyebrow}</p>
          <h1>{copy.inboxTitle}</h1>
          <p className="cortex-admin-page-description">{copy.description}</p>
        </div>
      </div>

      <div className="cortex-admin-toolbar request-type-filter">
        <div className="cortex-admin-field">
          <label htmlFor={typeId}>{copy.typeFilter}</label>
          <select
            ref={typeRef}
            id={typeId}
            value={type}
            onChange={(event) => {
              setType(event.target.value as ListAdminSupportRequestsType | '');
              setPage(0);
            }}
          >
            <option value="">{copy.allTypes}</option>
            {TYPES.map((value) => (
              <option key={value} value={value}>
                {copy.type[value]}
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
            {copy.clearTypeFilter}
          </button>
        )}
      </div>

      <AdminList
        query={query}
        noun={copy.noun}
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
                <th scope="col">{copy.columns.number}</th>
                <th scope="col">{copy.columns.subject}</th>
                <th scope="col">{copy.columns.type}</th>
                <th scope="col">{copy.columns.status}</th>
                <th scope="col">{copy.columns.requester}</th>
                <th scope="col">{copy.columns.activity}</th>
              </tr>
            </thead>
            <tbody>
              {requests.map((request) => (
                <tr key={request.number}>
                  <td data-label={copy.columns.number}>{copy.number(request.number)}</td>
                  <td data-label={copy.columns.subject}>
                    <Link to={`/support/requests/${request.number}`}>{request.subject}</Link>
                  </td>
                  <td data-label={copy.columns.type}>{copy.type[request.type]}</td>
                  <td data-label={copy.columns.status}>
                    <StatusBadge status={request.status} />
                  </td>
                  <td data-label={copy.columns.requester}>
                    <RequesterLabel requester={request.requester} />
                  </td>
                  <td data-label={copy.columns.activity}>
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
