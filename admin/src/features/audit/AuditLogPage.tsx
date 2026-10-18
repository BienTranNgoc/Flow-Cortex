import { useQuery } from '@tanstack/react-query';
import { Search } from 'lucide-react';
import { useState } from 'react';
import { PageHeading } from '../../app/PageHeading';
import { StatusPill } from '../../app/StatusPill';
import { Timestamp } from '../../app/AdminList';
import { usePageTitle } from '../../app/usePageTitle';
import { fetchAuditLog, type AuditEntry } from '../../app/mockAdminApi';

const ALL_ACTIONS: AuditEntry['action'][] = [
  'FORCE_STOP_RUN',
  'MANUAL_REFUND_CREDITS',
  'TOGGLE_FEATURE_FLAG',
  'DISABLE_USER',
  'ENABLE_USER',
  'CHANGE_ROLE',
  'OVERRIDE_APPROVAL',
  'GRANT_CREDITS',
];

export function AuditLogPage() {
  usePageTitle('Audit Log');
  const query = useQuery({ queryKey: ['admin', 'audit'], queryFn: fetchAuditLog });
  const [query2, setQuery] = useState('');
  const [action, setAction] = useState<AuditEntry['action'] | ''>('');

  const entries = (query.data ?? []).filter((entry) => {
    const needle = query2.trim().toLocaleLowerCase();
    const matchesQuery =
      !needle ||
      entry.actor.toLocaleLowerCase().includes(needle) ||
      entry.target.toLocaleLowerCase().includes(needle) ||
      entry.action.toLocaleLowerCase().includes(needle);
    return matchesQuery && (!action || entry.action === action);
  });

  return (
    <div>
      <PageHeading
        eyebrow="Administration"
        title="Audit Log"
        description="Every privileged action taken in the console. Search by actor or target and filter by action type."
      />

      <section className="cortex-admin-panel">
        <div className="cortex-admin-filters">
          <div className="cortex-admin-field">
            <label htmlFor="audit-search">Search</label>
            <div className="cortex-admin-search">
              <Search size={16} aria-hidden="true" />
              <input
                id="audit-search"
                type="search"
                value={query2}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="Actor, target, or action"
              />
            </div>
          </div>
          <div className="cortex-admin-field">
            <label htmlFor="audit-action">Action</label>
            <select
              id="audit-action"
              value={action}
              onChange={(event) => setAction(event.target.value as AuditEntry['action'] | '')}
            >
              <option value="">All actions</option>
              {ALL_ACTIONS.map((a) => (
                <option key={a} value={a}>
                  {a}
                </option>
              ))}
            </select>
          </div>
          <div className="cortex-admin-field">
            <label htmlFor="audit-date">Date</label>
            <input id="audit-date" type="date" defaultValue="2026-10-18" />
          </div>
        </div>

        {query.isLoading && <output className="cortex-admin-loading">Loading audit log…</output>}
        {query.isError && (
          <div className="cortex-admin-error">
            <p role="alert">Could not load the audit log.</p>
            <button type="button" className="cortex-admin-btn" onClick={() => query.refetch()}>
              Retry
            </button>
          </div>
        )}

        {entries.length > 0 && (
          <div className="cortex-admin-table-wrapper">
            <table className="cortex-admin-table">
              <thead>
                <tr>
                  <th scope="col">Time</th>
                  <th scope="col">Actor</th>
                  <th scope="col">Action</th>
                  <th scope="col">Target</th>
                  <th scope="col">IP</th>
                  <th scope="col">Status</th>
                </tr>
              </thead>
              <tbody>
                {entries.map((e) => (
                  <tr key={e.id}>
                    <td data-label="Time">
                      <Timestamp value={e.timestamp} />
                    </td>
                    <td data-label="Actor">{e.actor}</td>
                    <td data-label="Action">
                      <code className="cortex-admin-table-job-id">{e.action}</code>
                    </td>
                    <td data-label="Target">{e.target}</td>
                    <td data-label="IP">
                      <code className="cortex-admin-table-job-id">{e.ip}</code>
                    </td>
                    <td data-label="Status">
                      <StatusPill tone={e.status === 'Success' ? 'success' : 'error'} label={e.status} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}
