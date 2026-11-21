import { ApiError } from '@flow-cortex/api-client';
import { ArrowLeft } from '@phosphor-icons/react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';
import { z } from 'zod';
import { Timestamp } from '../../app/AdminList';
import { can } from '../../app/permissions';
import { PageHeading } from '../../app/PageHeading';
import { StatusPill } from '../../app/StatusPill';
import { TableRegion } from '../../app/TableRegion';
import { usePageTitle } from '../../app/usePageTitle';
import { formatNumber } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import { useMeQuery } from '../auth/useAuth';
import { canStopRun } from './canStopRun';
import { runStatusLabel, runStatusTone } from './runStatus';
import { StopRunDialog } from './StopRunDialog';
import { useAdminRun, useCancelAdminRun } from './useAdminRun';
import './runs.css';

const runIdSchema = z.string().uuid();

export function RunDetailPage() {
  const { t } = useTranslation();
  usePageTitle(t('runs.detail.title'));
  const { runId = '' } = useParams();
  const parsedId = runIdSchema.safeParse(runId);
  const id = parsedId.success ? parsedId.data : '';
  const me = useMeQuery().data;
  const query = useAdminRun(id);
  const cancel = useCancelAdminRun(id);
  const [confirming, setConfirming] = useState(false);

  const back = (
    <Link className="cortex-admin-back-link" to="/runs">
      <ArrowLeft size={16} aria-hidden="true" /> {t('runs.detail.allRuns')}
    </Link>
  );

  const failure = query.error instanceof ApiError ? query.error.status : undefined;
  if (!parsedId.success || failure === 404 || failure === 400) {
    return (
      <div>
        {back}
        <h1>{t('runs.detail.notFound')}</h1>
        <p role="alert">{t('runs.detail.notFoundHint')}</p>
      </div>
    );
  }
  if (failure === 503) {
    return (
      <div>
        {back}
        <h1>{t('runs.detail.title')}</h1>
        <output className="cortex-admin-disabled-banner">{t('common.shell.list.disabled')}</output>
      </div>
    );
  }
  if (query.isPending) {
    return (
      <div>
        {back}
        <h1>{t('runs.detail.title')}</h1>
        <output className="cortex-admin-loading">{t('runs.detail.loading')}</output>
      </div>
    );
  }
  if (query.isError || !query.data) {
    return (
      <div>
        {back}
        <h1>{t('runs.detail.title')}</h1>
        <div className="cortex-admin-error">
          <p role="alert">
            {failure === 403 ? t('runs.detail.forbidden') : t('runs.detail.loadError')}
          </p>
          <button type="button" className="cortex-admin-btn" onClick={() => query.refetch()}>
            {t('common.shell.list.retry')}
          </button>
        </div>
      </div>
    );
  }

  const run = query.data;
  const locale = activeLocale();
  const canOpenWorkflow = can(me, 'workflow.workflow.view');
  const stopError = cancel.error
    ? ((cancel.error instanceof ApiError ? cancel.error.problem?.detail : undefined) ??
      t('runs.stop.failed'))
    : undefined;
  const closeDialog = () => {
    setConfirming(false);
    cancel.reset();
  };
  const columns = {
    order: t('runs.steps.order'),
    type: t('runs.steps.type'),
    mode: t('runs.steps.mode'),
    status: t('runs.steps.status'),
    attempt: t('runs.steps.attempt'),
    error: t('runs.steps.error'),
    started: t('runs.steps.started'),
    finished: t('runs.steps.finished'),
  };
  const stepStatus = (status: string) => t(`runs.steps.statuses.${status}`, { defaultValue: status });

  return (
    <div className="run-detail">
      {back}
      <PageHeading
        eyebrow={t('runs.eyebrow')}
        title={run.workflowName}
        description={run.id}
        actions={
          <div className="run-detail__actions">
            <StatusPill tone={runStatusTone(run.status)} label={runStatusLabel(t, run.status)} />
            {canStopRun(run.status, me) && (
              <button
                type="button"
                className="cortex-admin-btn cortex-admin-btn--danger"
                onClick={() => setConfirming(true)}
              >
                {t('runs.stop.action')}
              </button>
            )}
          </div>
        }
      />

      <section className="cortex-admin-panel" aria-labelledby="run-overview">
        <header className="cortex-admin-section-heading">
          <h2 id="run-overview">{t('runs.detail.overview')}</h2>
        </header>
        <dl className="cortex-admin-meta-list">
          <div>
            <dt>{t('runs.columns.workflow')}</dt>
            <dd>
              {canOpenWorkflow ? (
                <Link className="cortex-admin-link" to={`/workflows/${run.workflowId}`}>
                  {run.workflowName}
                </Link>
              ) : (
                run.workflowName
              )}
            </dd>
          </div>
          <div>
            <dt>{t('runs.columns.owner')}</dt>
            <dd>{run.ownerEmail ?? t('runs.deletedUser')}</dd>
          </div>
          <div>
            <dt>{t('runs.columns.started')}</dt>
            <dd>
              <Timestamp value={run.startedAt} />
            </dd>
          </div>
          <div>
            <dt>{t('runs.columns.finished')}</dt>
            <dd>
              <Timestamp value={run.finishedAt} />
            </dd>
          </div>
          <div>
            <dt>{t('runs.detail.credits')}</dt>
            <dd>{run.creditsCharged == null ? '—' : formatNumber(run.creditsCharged, locale)}</dd>
          </div>
          <div>
            <dt>{t('runs.columns.error')}</dt>
            <dd>{run.errorMessage ?? '—'}</dd>
          </div>
        </dl>
      </section>

      <section className="cortex-admin-panel" aria-labelledby="run-steps">
        <header className="cortex-admin-section-heading">
          <h2 id="run-steps">{t('runs.steps.title')}</h2>
        </header>
        {run.steps.length === 0 ? (
          <p>{t('runs.steps.empty')}</p>
        ) : (
          <TableRegion label={t('runs.steps.tableLabel')}>
            <table className="cortex-admin-table">
              <thead>
                <tr>
                  <th scope="col">{columns.order}</th>
                  <th scope="col">{columns.type}</th>
                  <th scope="col">{columns.mode}</th>
                  <th scope="col">{columns.status}</th>
                  <th scope="col">{columns.attempt}</th>
                  <th scope="col">{columns.error}</th>
                  <th scope="col">{columns.started}</th>
                  <th scope="col">{columns.finished}</th>
                </tr>
              </thead>
              <tbody>
                {run.steps.map((step) => (
                  <tr key={step.stepOrder}>
                    <td data-label={columns.order}>{step.stepOrder}</td>
                    <td data-label={columns.type}>{step.type}</td>
                    <td data-label={columns.mode}>{step.mode}</td>
                    <td data-label={columns.status}>{stepStatus(step.status)}</td>
                    <td data-label={columns.attempt}>{step.attempt}</td>
                    <td data-label={columns.error}>
                      {step.errorCode || step.errorMessage ? (
                        <>
                          {step.errorMessage}
                          {step.errorCode && (
                            <code className="run-detail__error-code">{step.errorCode}</code>
                          )}
                        </>
                      ) : (
                        '—'
                      )}
                    </td>
                    <td data-label={columns.started}>
                      <Timestamp value={step.startedAt} />
                    </td>
                    <td data-label={columns.finished}>
                      <Timestamp value={step.finishedAt} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </TableRegion>
        )}
      </section>

      {confirming && (
        <StopRunDialog
          pending={cancel.isPending}
          error={stopError}
          onConfirm={() => cancel.mutate(undefined, { onSuccess: closeDialog })}
          onClose={closeDialog}
        />
      )}
    </div>
  );
}
