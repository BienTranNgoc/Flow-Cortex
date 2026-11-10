import { GetRunLogLevel, type Entry, type RunStepResponse } from '@flow-cortex/api-client';
import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { activeLocale } from '../../lib/i18n';
import { formatTime, stepDuration, stepLabel } from './stepDisplay';
import { useRunLogQuery } from './useWorkflows';

const FILTERS = [
  { level: GetRunLogLevel.all, key: 'workflow.runLog.filter.all' },
  { level: GetRunLogLevel.error, key: 'workflow.runLog.filter.error' },
  { level: GetRunLogLevel.warn, key: 'workflow.runLog.filter.warn' },
] as const;

const LEVEL_KEYS: Record<Entry['level'], string> = {
  error: 'workflow.runLog.level.error',
  warn: 'workflow.runLog.level.warn',
  info: 'workflow.runLog.level.info',
};

type CopyState = 'idle' | 'copied' | 'failed';

/** The step view the shared step helpers read, rebuilt from a log entry. */
function asStep(entry: Entry): RunStepResponse {
  return {
    stepOrder: entry.stepOrder,
    type: entry.type,
    attempt: entry.attempt,
    deliveryCount: entry.deliveryCount,
    outputs: [],
    mode: 'inline',
    ...(entry.startedAt ? { startedAt: entry.startedAt } : {}),
    ...(entry.finishedAt ? { finishedAt: entry.finishedAt } : {}),
  };
}

function LogEntry({ entry }: Readonly<{ entry: Entry }>) {
  const { t } = useTranslation();
  const locale = activeLocale();
  const step = asStep(entry);
  const started = formatTime(entry.startedAt ?? undefined, locale);
  const duration = stepDuration(step, t, locale);

  return (
    <li className={`run-log-entry run-log-entry-${entry.level}`}>
      <div className="run-log-head">
        <span className={`run-log-level run-log-level-${entry.level}`}>
          {t(LEVEL_KEYS[entry.level])}
        </span>
        <span className="run-log-step">
          <span className="run-log-order">{entry.stepOrder}</span> {stepLabel(step, t)}
        </span>
        <span className="run-log-status">{entry.status}</span>
      </div>
      <dl className="run-log-facts">
        {started && (
          <div>
            <dt>{t('workflow.runLog.started')}</dt>
            <dd>{started}</dd>
          </div>
        )}
        {duration && (
          <div>
            <dt>{t('workflow.runLog.duration')}</dt>
            <dd>{duration}</dd>
          </div>
        )}
        <div>
          <dt>{t('workflow.runLog.attempt')}</dt>
          <dd>{entry.attempt}</dd>
        </div>
        {entry.errorCode && (
          <div>
            <dt>{t('workflow.runLog.errorCode')}</dt>
            <dd className="run-log-code">{entry.errorCode}</dd>
          </div>
        )}
      </dl>
      {entry.message && <p className="run-log-message">{entry.message}</p>}
    </li>
  );
}

function TraceId({ traceId }: Readonly<{ traceId: string }>) {
  const { t } = useTranslation();
  const [copy, setCopy] = useState<CopyState>('idle');

  const onCopy = async () => {
    try {
      await navigator.clipboard.writeText(traceId);
      setCopy('copied');
    } catch {
      // No clipboard permission or an insecure origin: the id stays selectable beside the button.
      setCopy('failed');
    }
  };

  return (
    <div className="run-log-trace">
      <span>{t('workflow.runLog.traceId')}</span>
      <code className="run-log-trace-id">{traceId}</code>
      <button type="button" className="run-log-copy" onClick={() => void onCopy()}>
        {t('workflow.runLog.copyTrace')}
      </button>
      <span role="status" className="run-log-copied">
        {copy === 'copied' && t('workflow.runLog.copied')}
        {copy === 'failed' && t('workflow.runLog.copyFailed')}
      </span>
    </div>
  );
}

/**
 * "Run log" disclosure on the run page: nothing is requested until it is opened, then the
 * server's per-step log for the chosen level (errors first) and the run's trace id.
 */
export function RunLogPanel({ runId }: Readonly<{ runId: string }>) {
  const { t } = useTranslation();
  const bodyId = useId();
  const [open, setOpen] = useState(false);
  const [level, setLevel] = useState<GetRunLogLevel>(GetRunLogLevel.all);
  const query = useRunLogQuery(runId, level, open);

  return (
    <section className="run-log" aria-labelledby={`${bodyId}-title`}>
      <h2 id={`${bodyId}-title`} className="run-log-title">
        <button
          type="button"
          className="run-log-toggle"
          aria-expanded={open}
          aria-controls={bodyId}
          onClick={() => setOpen((value) => !value)}
        >
          {t('workflow.runLog.title')}
        </button>
      </h2>
      <div id={bodyId} className="run-log-body" hidden={!open}>
        {open && (
          <>
            <div role="group" aria-label={t('workflow.runLog.filter.label')} className="run-log-filters">
              {FILTERS.map((filter) => (
                <button
                  key={filter.level}
                  type="button"
                  className="run-log-filter"
                  aria-pressed={level === filter.level}
                  onClick={() => setLevel(filter.level)}
                >
                  {t(filter.key)}
                </button>
              ))}
            </div>
            {query.isPending && <output>{t('workflow.runLog.loading')}</output>}
            {query.isError && (
              <div role="alert" className="run-log-error">
                <p>{t('workflow.runLog.loadError')}</p>
                <button type="button" className="run-log-retry" onClick={() => void query.refetch()}>
                  {t('workflow.runLog.retry')}
                </button>
              </div>
            )}
            {query.data && (
              <>
                {query.data.traceId && <TraceId traceId={query.data.traceId} />}
                {query.data.entries.length === 0 ? (
                  <p className="run-log-empty">{t('workflow.runLog.empty')}</p>
                ) : (
                  <ol className="run-log-entries">
                    {query.data.entries.map((entry) => (
                      <LogEntry key={`${entry.stepOrder}`} entry={entry} />
                    ))}
                  </ol>
                )}
              </>
            )}
          </>
        )}
      </div>
    </section>
  );
}
