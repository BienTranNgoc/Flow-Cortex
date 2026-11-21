import { getExportAdminRunsUrl, ListAdminRunsStatus } from '@flow-cortex/api-client';
import { X } from '@phosphor-icons/react';
import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router-dom';
import { AdminList, Timestamp } from '../../app/AdminList';
import { ExportCsvButton } from '../../app/ExportCsvButton';
import { can } from '../../app/permissions';
import { PageHeading } from '../../app/PageHeading';
import { StatusPill } from '../../app/StatusPill';
import { usePageTitle } from '../../app/usePageTitle';
import { useMeQuery } from '../auth/useAuth';
import { dayRangeToInstants, isRangeReversed } from './dayRange';
import { parseRunFilterParams, withRunFilterParams } from './runFilterParams';
import { runStatusLabel, runStatusTone } from './runStatus';
import { useAdminRuns } from './useAdminRuns';

const STATUSES = Object.values(ListAdminRunsStatus);

export function RunsPage() {
  const { t } = useTranslation();
  usePageTitle(t('runs.title'));
  const fromId = useId();
  const toId = useId();
  const [params, setParams] = useSearchParams();
  const [page, setPage] = useState(0);
  const { status, workflowId, from, to } = parseRunFilterParams(params);
  const reversed = isRangeReversed(from, to);
  const instants = reversed ? {} : dayRangeToInstants(from, to);
  const query = useAdminRuns(page, { status, workflowId, ...instants }, !reversed);
  const canExport = can(useMeQuery().data, 'workflow.run.export');
  const exportUrl = getExportAdminRunsUrl({
    ...(status ? { status } : {}),
    ...(workflowId ? { workflowId } : {}),
    ...instants,
  });
  const workflowName =
    query.data?.items.find((run) => run.workflowId === workflowId)?.workflowName ?? workflowId;
  const dateOrWorkflowFiltered = Boolean(workflowId ?? from ?? to);

  const change = (changes: Parameters<typeof withRunFilterParams>[1]) => {
    setParams(withRunFilterParams(params, changes), { replace: true });
    setPage(0);
  };

  const columns = {
    workflow: t('runs.columns.workflow'),
    owner: t('runs.columns.owner'),
    status: t('runs.columns.status'),
    started: t('runs.columns.started'),
    finished: t('runs.columns.finished'),
    error: t('runs.columns.error'),
  };

  return (
    <div>
      <PageHeading
        eyebrow={t('runs.eyebrow')}
        title={t('runs.title')}
        description={t('runs.description')}
      />
      <div className="cortex-admin-panel">
        <div className="cortex-admin-toolbar">
          <div className="cortex-admin-field">
            <label htmlFor={fromId}>{t('runs.filters.from')}</label>
            <input
              id={fromId}
              type="date"
              value={from ?? ''}
              max={to}
              onChange={(event) => change({ from: event.target.value })}
            />
          </div>
          <div className="cortex-admin-field">
            <label htmlFor={toId}>{t('runs.filters.to')}</label>
            <input
              id={toId}
              type="date"
              value={to ?? ''}
              min={from}
              onChange={(event) => change({ to: event.target.value })}
            />
          </div>
          {workflowId && (
            <button
              type="button"
              className="cortex-admin-btn cortex-admin-btn--sm"
              onClick={() => change({ workflowId: '' })}
            >
              {t('runs.filters.workflowChip', { name: workflowName })}
              <span className="cortex-admin-visually-hidden"> {t('runs.filters.removeFilter')}</span>
              <X size={14} aria-hidden="true" />
            </button>
          )}
          {dateOrWorkflowFiltered && (
            <button
              type="button"
              className="cortex-admin-btn cortex-admin-btn--ghost"
              onClick={() => change({ workflowId: '', from: '', to: '' })}
            >
              {t('runs.filters.clear')}
            </button>
          )}
          {canExport && <ExportCsvButton url={exportUrl} nounKey="runs.noun" disabled={reversed} />}
        </div>
        {reversed && (
          <p className="cortex-admin-form-error" role="alert">
            {t('runs.filters.rangeReversed')}
          </p>
        )}
        {!reversed && (
          <AdminList
            query={query}
            nounKey="runs.noun"
            tableLabelKey="runs.tableLabel"
            statuses={STATUSES}
            statusLabel={(code) => runStatusLabel(t, code)}
            status={status ?? ''}
            filtered={dateOrWorkflowFiltered}
            onStatusChange={(next) => change({ status: next })}
            onPageChange={setPage}
          >
            {(runs) => (
              <table className="cortex-admin-table">
                <thead>
                  <tr>
                    <th scope="col">{columns.workflow}</th>
                    <th scope="col">{columns.owner}</th>
                    <th scope="col">{columns.status}</th>
                    <th scope="col">{columns.started}</th>
                    <th scope="col">{columns.finished}</th>
                    <th scope="col">{columns.error}</th>
                  </tr>
                </thead>
                <tbody>
                  {runs.map((run) => (
                    <tr key={run.id}>
                      <td data-label={columns.workflow}>
                        <Link className="cortex-admin-link" to={`/runs/${run.id}`}>
                          {run.workflowName}
                        </Link>
                      </td>
                      <td data-label={columns.owner}>{run.ownerEmail ?? t('runs.deletedUser')}</td>
                      <td data-label={columns.status}>
                        <StatusPill
                          tone={runStatusTone(run.status)}
                          label={runStatusLabel(t, run.status)}
                        />
                      </td>
                      <td data-label={columns.started}>
                        <Timestamp value={run.startedAt} />
                      </td>
                      <td data-label={columns.finished}>
                        <Timestamp value={run.finishedAt} />
                      </td>
                      <td data-label={columns.error}>{run.errorMessage ?? '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </AdminList>
        )}
      </div>
    </div>
  );
}
