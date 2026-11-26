import { ListWorkflowReportsStatus } from '@flow-cortex/api-client';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { AdminList, Timestamp } from '../../app/AdminList';
import { ListSearchToolbar } from '../../app/ListSearchToolbar';
import { useListFilters } from '../../app/useListFilters';
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

export function WorkflowReportsPage() {
  const { t } = useTranslation();
  usePageTitle(t('workflowReports.title'));
  const filters = useListFilters<ListWorkflowReportsStatus>();
  const { status, q } = filters;
  const query = useWorkflowReports(filters.page, status || undefined, q);
  const columns = {
    workflow: t('workflowReports.columns.workflow'),
    reason: t('workflowReports.columns.reason'),
    reporter: t('workflowReports.columns.reporter'),
    status: t('workflowReports.columns.status'),
    workflowState: t('workflowReports.columns.workflowState'),
    reported: t('workflowReports.columns.reported'),
  };

  return (
    <div>
      <PageHeading
        eyebrow={t('workflowReports.eyebrow')}
        title={t('workflowReports.title')}
        description={t('workflowReports.description')}
      />
      <div className="cortex-admin-panel">
        <ListSearchToolbar filters={filters} keyPrefix="workflowReports" />
        <AdminList
          query={query}
          nounKey="workflowReports.noun"
          tableLabelKey="workflowReports.tableLabel"
          statuses={STATUSES}
          statusLabel={(code) => reportStatusLabel(t, code)}
          status={status}
          filtered={q !== ''}
          onStatusChange={filters.changeStatus}
          onPageChange={filters.setPage}
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
