import type { RunResponse } from '@flow-cortex/api-client';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { stateWord, stepKey, stepLabel, stepState } from '../../workflow/stepDisplay';
import { runQueryOptions } from './runQuery';

const STATUS_KEYS: Record<string, string> = {
  pending: 'chat.run.status.pending',
  running: 'chat.run.status.running',
  waiting_approval: 'chat.run.status.waitingApproval',
  cancelling: 'chat.run.status.cancelling',
  completed: 'chat.run.status.completed',
  error: 'chat.run.status.error',
  cancelled: 'chat.run.status.cancelled',
};

function outputNames(run: RunResponse): string[] {
  return (run.steps ?? []).flatMap((step) => step.outputs.map((output) => output.name));
}

/** The run a confirmed card started: status and steps from the run itself, kept fresh by the thread's stream. */
export function RunCard({ runId }: Readonly<{ runId: string }>) {
  const { t } = useTranslation();
  const query = useQuery(runQueryOptions(runId));
  const run = query.data;

  if (query.isPending) {
    return (
      <p className="chat-card-note" role="status">
        {t('chat.run.loading')}
      </p>
    );
  }
  if (!run) {
    return (
      <div className="chat-card chat-card--run" role="alert">
        <p>{t('chat.run.loadFailed')}</p>
        <button type="button" className="chat-button" onClick={() => void query.refetch()}>
          {t('chat.run.retry')}
        </button>
      </div>
    );
  }

  const steps = run.steps ?? [];
  const done = steps.filter((step) => step.status === 'completed').length;
  const statusKey = STATUS_KEYS[run.status ?? ''];
  const outputs = run.status === 'completed' ? outputNames(run) : [];

  return (
    <section className="chat-card chat-card--run" aria-label={t('chat.run.label')}>
      <p className="chat-card__status">
        <span className="chat-card__caption">{t('chat.run.statusLabel')}</span>{' '}
        <strong>{statusKey ? t(statusKey) : t('chat.run.status.other', { status: run.status })}</strong>
      </p>
      {steps.length > 0 && (
        <>
          <p className="chat-card__progress">{t('chat.run.progress', { done, total: steps.length })}</p>
          <ol className="chat-card__steps">
            {steps.map((step) => (
              <li key={stepKey(step)}>
                {stepLabel(step, t)}: {stateWord(stepState(step, steps, run.status), t)}
              </li>
            ))}
          </ol>
        </>
      )}
      {run.status === 'waiting_approval' && (
        <Link to={`/runs/${runId}`} className="chat-link chat-card__review">
          {t('chat.run.review')}
        </Link>
      )}
      {outputs.length > 0 && (
        <div className="chat-card__outputs">
          <span className="chat-card__caption">{t('chat.run.outputs')}</span>
          <ul>
            {outputs.map((name) => (
              <li key={name}>{name}</li>
            ))}
          </ul>
        </div>
      )}
      <Link to={`/runs/${runId}`} className="chat-link">
        {run.status === 'completed' ? t('chat.run.results') : t('chat.run.open')}
      </Link>
    </section>
  );
}
