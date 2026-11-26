import {
  ApiError,
  type ModerationHistoryEntryResponse,
  type ModerationPersonResponse,
} from '@flow-cortex/api-client';
import { ArrowLeft } from '@phosphor-icons/react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';
import { z } from 'zod';
import { Timestamp } from '../../app/AdminList';
import { PageHeading } from '../../app/PageHeading';
import { can } from '../../app/permissions';
import { StatusPill } from '../../app/StatusPill';
import { TableRegion } from '../../app/TableRegion';
import { usePageTitle } from '../../app/usePageTitle';
import { useMeQuery } from '../auth/useAuth';
import { ModerationDialog } from './ModerationDialog';
import { allowedActions, type ModerationAction } from './moderationActions';
import {
  moderationStatusLabel,
  moderationStatusTone,
  reasonLabel,
  reportStatusLabel,
  reportStatusTone,
} from './reportStatus';
import { useModerateReport, useWorkflowReport } from './useWorkflowReports';
import './workflowReports.css';

const reportIdSchema = z.string().uuid();

function personLabel(person: ModerationPersonResponse | undefined, fallback: string): string {
  if (!person) return fallback;
  if (person.name && person.email) return `${person.name} (${person.email})`;
  return person.name ?? person.email ?? fallback;
}

function HistoryTable({ entries }: Readonly<{ entries: ModerationHistoryEntryResponse[] }>) {
  const { t } = useTranslation();
  const columns = {
    when: t('workflowReports.history.when'),
    action: t('workflowReports.history.action'),
    source: t('workflowReports.history.source'),
    moderator: t('workflowReports.history.moderator'),
    result: t('workflowReports.history.result'),
    reason: t('workflowReports.history.reason'),
    note: t('workflowReports.history.note'),
  };
  return (
    <TableRegion label={t('workflowReports.history.tableLabel')}>
      <table className="cortex-admin-table">
        <thead>
          <tr>
            <th scope="col">{columns.when}</th>
            <th scope="col">{columns.action}</th>
            <th scope="col">{columns.source}</th>
            <th scope="col">{columns.moderator}</th>
            <th scope="col">{columns.result}</th>
            <th scope="col">{columns.reason}</th>
            <th scope="col">{columns.note}</th>
          </tr>
        </thead>
        <tbody>
          {entries.map((entry) => (
            <tr key={entry.id}>
              <td data-label={columns.when}>
                <Timestamp value={entry.createdAt} />
              </td>
              <td data-label={columns.action}>{t(`workflowReports.actions.${entry.action}`)}</td>
              <td data-label={columns.source}>{t(`workflowReports.history.sources.${entry.source}`)}</td>
              <td data-label={columns.moderator}>
                {entry.moderatorName ?? t('workflowReports.deletedUser')}
              </td>
              <td data-label={columns.result}>
                {moderationStatusLabel(t, entry.moderationStatus, false)}
              </td>
              <td data-label={columns.reason}>{entry.reason ?? '—'}</td>
              <td data-label={columns.note}>{entry.internalNote ?? '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </TableRegion>
  );
}

export function WorkflowReportDetailPage() {
  const { t } = useTranslation();
  usePageTitle(t('workflowReports.detail.title'));
  const { reportId = '' } = useParams();
  const parsedId = reportIdSchema.safeParse(reportId);
  const id = parsedId.success ? parsedId.data : '';
  const me = useMeQuery().data;
  const query = useWorkflowReport(id);
  const moderate = useModerateReport(id);
  const [acting, setActing] = useState<ModerationAction | undefined>();

  const back = (
    <Link className="cortex-admin-back-link" to="/workflow-reports">
      <ArrowLeft size={16} aria-hidden="true" /> {t('workflowReports.detail.allReports')}
    </Link>
  );

  const failure = query.error instanceof ApiError ? query.error.status : undefined;
  if (!parsedId.success || failure === 404 || failure === 400) {
    return (
      <div>
        {back}
        <h1>{t('workflowReports.detail.notFound')}</h1>
        <p role="alert">{t('workflowReports.detail.notFoundHint')}</p>
      </div>
    );
  }
  if (failure === 503) {
    return (
      <div>
        {back}
        <h1>{t('workflowReports.detail.title')}</h1>
        <output className="cortex-admin-disabled-banner">{t('common.shell.list.disabled')}</output>
      </div>
    );
  }
  if (query.isPending) {
    return (
      <div>
        {back}
        <h1>{t('workflowReports.detail.title')}</h1>
        <output className="cortex-admin-loading">{t('workflowReports.detail.loading')}</output>
      </div>
    );
  }
  if (query.isError || !query.data) {
    return (
      <div>
        {back}
        <h1>{t('workflowReports.detail.title')}</h1>
        <div className="cortex-admin-error">
          <p role="alert">
            {failure === 403 ? t('workflowReports.detail.forbidden') : t('workflowReports.detail.loadError')}
          </p>
          <button type="button" className="cortex-admin-btn" onClick={() => query.refetch()}>
            {t('common.shell.list.retry')}
          </button>
        </div>
      </div>
    );
  }

  const report = query.data;
  const { workflow } = report;
  const actions = allowedActions(workflow.moderationStatus, workflow.deleted, me);
  const canOpenWorkflow = !workflow.deleted && can(me, 'workflow.workflow.view');
  const actionError = moderate.error
    ? ((moderate.error instanceof ApiError ? moderate.error.problem?.detail : undefined) ??
      t('workflowReports.dialog.failed'))
    : undefined;
  const closeDialog = () => {
    setActing(undefined);
    moderate.reset();
  };
  const deletedUser = t('workflowReports.deletedUser');

  return (
    <div className="report-detail">
      {back}
      <PageHeading
        eyebrow={t('workflowReports.eyebrow')}
        title={workflow.name}
        description={report.id}
        actions={
          <div className="report-detail__actions">
            <StatusPill tone={reportStatusTone(report.status)} label={reportStatusLabel(t, report.status)} />
            {actions.map((action) => (
              <button
                key={action}
                type="button"
                className={`cortex-admin-btn${action === 'hide' || action === 'remove' ? ' cortex-admin-btn--danger' : ''}`}
                onClick={() => setActing(action)}
              >
                {t(`workflowReports.actions.${action}`)}
              </button>
            ))}
          </div>
        }
      />

      <section className="cortex-admin-panel" aria-labelledby="report-overview">
        <header className="cortex-admin-section-heading">
          <h2 id="report-overview">{t('workflowReports.detail.report')}</h2>
        </header>
        <dl className="cortex-admin-meta-list">
          <div>
            <dt>{t('workflowReports.columns.reason')}</dt>
            <dd>{reasonLabel(t, report.reason)}</dd>
          </div>
          <div>
            <dt>{t('workflowReports.detail.details')}</dt>
            <dd className="report-detail__text">{report.details ?? '—'}</dd>
          </div>
          <div>
            <dt>{t('workflowReports.columns.reporter')}</dt>
            <dd>{personLabel(report.reporter, deletedUser)}</dd>
          </div>
          <div>
            <dt>{t('workflowReports.columns.reported')}</dt>
            <dd>
              <Timestamp value={report.createdAt} />
            </dd>
          </div>
          <div>
            <dt>{t('workflowReports.detail.reviewedBy')}</dt>
            <dd>{report.reviewer ? (report.reviewer.name ?? deletedUser) : '—'}</dd>
          </div>
          <div>
            <dt>{t('workflowReports.detail.reviewedAt')}</dt>
            <dd>
              <Timestamp value={report.reviewedAt} />
            </dd>
          </div>
          <div>
            <dt>{t('workflowReports.detail.resolution')}</dt>
            <dd>{report.resolution ? t(`workflowReports.actions.${report.resolution}`) : '—'}</dd>
          </div>
          <div>
            <dt>{t('workflowReports.detail.internalNote')}</dt>
            <dd className="report-detail__text">{report.internalNote ?? '—'}</dd>
          </div>
        </dl>
      </section>

      <section className="cortex-admin-panel" aria-labelledby="report-workflow">
        <header className="cortex-admin-section-heading">
          <h2 id="report-workflow">{t('workflowReports.detail.workflow')}</h2>
        </header>
        <dl className="cortex-admin-meta-list">
          <div>
            <dt>{t('workflowReports.columns.workflow')}</dt>
            <dd>
              {canOpenWorkflow ? (
                <Link className="cortex-admin-link" to={`/workflows/${workflow.id}`}>
                  {workflow.name}
                </Link>
              ) : (
                workflow.name
              )}
            </dd>
          </div>
          <div>
            <dt>{t('workflowReports.detail.owner')}</dt>
            <dd>{personLabel(workflow.owner, deletedUser)}</dd>
          </div>
          <div>
            <dt>{t('workflowReports.detail.visibility')}</dt>
            <dd>{t(`workflowReports.visibility.${workflow.visibility}`)}</dd>
          </div>
          <div>
            <dt>{t('workflowReports.columns.workflowState')}</dt>
            <dd>
              <StatusPill
                tone={workflow.deleted ? 'muted' : moderationStatusTone(workflow.moderationStatus)}
                label={moderationStatusLabel(t, workflow.moderationStatus, workflow.deleted)}
              />
            </dd>
          </div>
        </dl>
      </section>

      <section className="cortex-admin-panel" aria-labelledby="report-history">
        <header className="cortex-admin-section-heading">
          <h2 id="report-history">{t('workflowReports.history.title')}</h2>
        </header>
        {report.history.length === 0 ? (
          <p>{t('workflowReports.history.empty')}</p>
        ) : (
          <HistoryTable entries={report.history} />
        )}
      </section>

      {acting && (
        <ModerationDialog
          action={acting}
          pending={moderate.isPending}
          error={actionError}
          onConfirm={({ reason, internalNote }) =>
            moderate.mutate({ action: acting, reason, internalNote }, { onSuccess: closeDialog })
          }
          onClose={closeDialog}
        />
      )}
    </div>
  );
}
