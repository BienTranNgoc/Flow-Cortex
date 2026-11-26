import { ListWorkflowReportsStatus } from '@flow-cortex/api-client';
import { useId, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { AdminList, Timestamp } from '../../app/AdminList';
import { PageHeading } from '../../app/PageHeading';
import { StatusPill } from '../../app/StatusPill';
import { usePageTitle } from '../../app/usePageTitle';
import {
  moderationStatusLabel,
  moderationStatusTone,
  reasonLabel,
  reportStatusLabel,
  reportStatusTone,
} from './reportStatus';
import { useWorkflowReports } from './useWorkflowReports';

const STATUSES = Object.values(ListWorkflowReportsStatus);
const NAME_MAX = 255;

export function WorkflowReportsPage() {
  const { t } = useTranslation();
  usePageTitle(t('workflowReports.title'));
  const searchId = useId();
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<ListWorkflowReportsStatus | ''>('');
  const [q, setQ] = useState('');
  const [text, setText] = useState('');
  const query = useWorkflowReports(page, status || undefined, q);
  const columns = {
    workflow: t('workflowReports.columns.workflow'),
    reason: t('workflowReports.columns.reason'),
    reporter: t('workflowReports.columns.reporter'),
    status: t('workflowReports.columns.status'),
    workflowState: t('workflowReports.columns.workflowState'),
    reported: t('workflowReports.columns.reported'),
  };

  const search = (event: FormEvent) => {
    event.preventDefault();
    setQ(text.trim());
    setPage(0);
  };
  const clearFilters = () => {
    setStatus('');
    setQ('');
    setText('');
    setPage(0);
  };

  return (
    <div>
      <PageHeading
        eyebrow={t('workflowReports.eyebrow')}
        title={t('workflowReports.title')}
        description={t('workflowReports.description')}
      />
      <div className="cortex-admin-panel">
        <form className="cortex-admin-toolbar" role="search" onSubmit={search}>
          <div className="cortex-admin-field">
            <label htmlFor={searchId}>{t('workflowReports.search.label')}</label>
            <input
              id={searchId}
              type="search"
              value={text}
              maxLength={NAME_MAX}
              placeholder={t('workflowReports.search.placeholder')}
              onChange={(event) => setText(event.target.value)}
            />
          </div>
          <button type="submit" className="cortex-admin-btn">
            {t('workflowReports.search.submit')}
          </button>
          {(q || status) && (
            <button type="button" className="cortex-admin-btn cortex-admin-btn--ghost" onClick={clearFilters}>
              {t('workflowReports.search.clear')}
            </button>
          )}
        </form>
        <AdminList
          query={query}
          nounKey="workflowReports.noun"
          tableLabelKey="workflowReports.tableLabel"
          statuses={STATUSES}
          statusLabel={(code) => reportStatusLabel(t, code)}
          status={status}
          filtered={q !== ''}
          onStatusChange={(next) => {
            setStatus(next as ListWorkflowReportsStatus | '');
            setPage(0);
          }}
          onPageChange={setPage}
        >
          {(reports) => (
            <table className="cortex-admin-table">
              <thead>
                <tr>
                  <th scope="col">{columns.workflow}</th>
                  <th scope="col">{columns.reason}</th>
                  <th scope="col">{columns.reporter}</th>
                  <th scope="col">{columns.status}</th>
                  <th scope="col">{columns.workflowState}</th>
                  <th scope="col">{columns.reported}</th>
                </tr>
              </thead>
              <tbody>
                {reports.map((report) => (
                  <tr key={report.id}>
                    <td data-label={columns.workflow}>
                      <Link className="cortex-admin-link" to={`/workflow-reports/${report.id}`}>
                        {report.workflow.name}
                      </Link>
                    </td>
                    <td data-label={columns.reason}>{reasonLabel(t, report.reason)}</td>
                    <td data-label={columns.reporter}>
                      {report.reporter.email ?? t('workflowReports.deletedUser')}
                    </td>
                    <td data-label={columns.status}>
                      <StatusPill
                        tone={reportStatusTone(report.status)}
                        label={reportStatusLabel(t, report.status)}
                      />
                    </td>
                    <td data-label={columns.workflowState}>
                      <StatusPill
                        tone={report.workflow.deleted ? 'muted' : moderationStatusTone(report.workflow.moderationStatus)}
                        label={moderationStatusLabel(t, report.workflow.moderationStatus, report.workflow.deleted)}
                      />
                    </td>
                    <td data-label={columns.reported}>
                      <Timestamp value={report.createdAt} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </AdminList>
      </div>
    </div>
  );
}
