import { getExportAdminWorkflowsUrl, ListAdminWorkflowsStatus } from '@flow-cortex/api-client';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { AdminList, Timestamp } from '../../app/AdminList';
import { ExportCsvButton } from '../../app/ExportCsvButton';
import { ListSearchToolbar } from '../../app/ListSearchToolbar';
import { useListFilters } from '../../app/useListFilters';
import { PageHeading } from '../../app/PageHeading';
import { can } from '../../app/permissions';
import { StatusPill } from '../../app/StatusPill';
import { usePageTitle } from '../../app/usePageTitle';
import { formatNumber } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import { useMeQuery } from '../auth/useAuth';
import { workflowStatusLabel } from './workflowStatus';
import { useAdminWorkflows } from './useAdminWorkflows';

const STATUSES = Object.values(ListAdminWorkflowsStatus);

export function WorkflowsPage() {
  const { t } = useTranslation();
  usePageTitle(t('workflows.title'));
  const locale = activeLocale();
  const filters = useListFilters<ListAdminWorkflowsStatus>();
  const { status, q } = filters;
  const query = useAdminWorkflows(filters.page, status || undefined, q);
  const canExport = can(useMeQuery().data, 'workflow.workflow.export');
  const exportUrl = getExportAdminWorkflowsUrl({
    ...(status ? { status } : {}),
    ...(q ? { q } : {}),
  });
  const columns = {
    name: t('workflows.columns.name'),
    owner: t('workflows.columns.owner'),
    status: t('workflows.columns.status'),
    shared: t('workflows.columns.shared'),
    steps: t('workflows.columns.steps'),
    runs: t('workflows.columns.runs'),
    runs7d: t('workflows.columns.runs7d'),
    lastRun: t('workflows.columns.lastRun'),
    updated: t('workflows.columns.updated'),
  };

  return (
    <div>
      <PageHeading
        eyebrow={t('workflows.eyebrow')}
        title={t('workflows.title')}
        description={t('workflows.description')}
      />
      <div className="cortex-admin-panel">
        <ListSearchToolbar filters={filters} keyPrefix="workflows">
          {canExport && <ExportCsvButton url={exportUrl} nounKey="workflows.noun" />}
        </ListSearchToolbar>
        <AdminList
          query={query}
          nounKey="workflows.noun"
          tableLabelKey="workflows.tableLabel"
          statuses={STATUSES}
          statusLabel={(code) => workflowStatusLabel(t, code)}
          status={status}
          filtered={q !== ''}
          onStatusChange={filters.changeStatus}
          onPageChange={filters.setPage}
        >
          {(workflows) => (
            <table className="cortex-admin-table">
              <thead>
                <tr>
                  <th scope="col">{columns.name}</th>
                  <th scope="col">{columns.owner}</th>
                  <th scope="col">{columns.status}</th>
                  <th scope="col">{columns.shared}</th>
                  <th scope="col">{columns.steps}</th>
                  <th scope="col">{columns.runs}</th>
                  <th scope="col">{columns.runs7d}</th>
                  <th scope="col">{columns.lastRun}</th>
                  <th scope="col">{columns.updated}</th>
                </tr>
              </thead>
              <tbody>
                {workflows.map((workflow) => (
                  <tr key={workflow.id}>
                    <td data-label={columns.name}>
                      <Link className="cortex-admin-link" to={`/workflows/${workflow.id}`}>
                        {workflow.name}
                      </Link>
                    </td>
                    <td data-label={columns.owner}>{workflow.ownerEmail ?? t('workflows.deletedUser')}</td>
                    <td data-label={columns.status}>
                      <StatusPill
                        tone={workflow.status === ListAdminWorkflowsStatus.published ? 'success' : 'muted'}
                        label={workflowStatusLabel(t, workflow.status)}
                      />
                    </td>
                    <td data-label={columns.shared}>
                      {workflow.shared ? t('workflows.shared.yes') : t('workflows.shared.no')}
                    </td>
                    <td data-label={columns.steps}>{formatNumber(workflow.steps, locale)}</td>
                    <td data-label={columns.runs}>{formatNumber(workflow.runs, locale)}</td>
                    <td data-label={columns.runs7d}>{formatNumber(workflow.runsLast7Days, locale)}</td>
                    <td data-label={columns.lastRun}>
                      <Timestamp value={workflow.lastRunAt} />
                    </td>
                    <td data-label={columns.updated}>
                      <Timestamp value={workflow.updatedAt} />
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
