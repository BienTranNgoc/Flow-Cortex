import type { TFunction } from 'i18next';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { DEMO_RUNS, type DemoRun } from './runsDemo';
import './RunsPage.css';

type StatusFilter = 'all' | 'success' | 'failure' | 'waiting' | 'running';

const STATUS_LABEL_KEY: Record<StatusFilter, string> = {
  all: 'common.runs.status.all',
  success: 'common.runs.status.success',
  failure: 'common.runs.status.failure',
  waiting: 'common.runs.status.waiting',
  running: 'common.runs.status.running',
};

const STATUS_TONE: Record<DemoRun['status'], string> = {
  success: 'runs-page__pill--success',
  failure: 'runs-page__pill--failure',
  waiting: 'runs-page__pill--waiting',
  running: 'runs-page__pill--running',
};

const TRIGGER_LABEL_KEY: Record<DemoRun['trigger'], string> = {
  manual: 'common.runs.trigger.manual',
  webhook: 'common.runs.trigger.webhook',
  schedule: 'common.runs.trigger.schedule',
};

const FILTERS: readonly StatusFilter[] = ['all', 'success', 'failure', 'waiting', 'running'];

function formatDuration(seconds: number, t: TFunction): string {
  if (seconds < 60) return t('common.units.secondsShort', { value: Math.round(seconds) });
  const mins = Math.floor(seconds / 60);
  const secs = Math.round(seconds % 60);
  if (mins < 60) {
    return secs > 0
      ? t('common.units.minutesSeconds', { minutes: mins, seconds: secs })
      : t('common.units.minutes', { value: mins });
  }
  const hours = Math.floor(mins / 60);
  const remMins = mins % 60;
  return remMins > 0
    ? t('common.units.hoursMinutes', { hours, minutes: remMins })
    : t('common.units.hours', { value: hours });
}

function formatRelative(startedAt: string, finishedAt: string | undefined, t: TFunction): string {
  const end = finishedAt ? new Date(finishedAt) : new Date();
  const diffMs = end.getTime() - new Date(startedAt).getTime();
  const mins = Math.max(0, Math.round(diffMs / 60_000));
  if (mins < 1) return t('common.runs.justNow');
  if (mins < 60) return t('common.units.minutes', { value: mins });
  const hours = Math.round(mins / 60);
  if (hours < 24) return t('common.units.hours', { value: hours });
  return t('common.units.days', { value: Math.round(hours / 24) });
}

function CopyButton({ value }: { readonly value: string }) {
  const { t } = useTranslation();
  const [copied, setCopied] = useState(false);
  const label = t('common.runs.copy', { value });
  return (
    <button
      type="button"
      className="runs-page__copy"
      aria-label={label}
      title={label}
      onClick={() => {
        void navigator.clipboard.writeText(value).then(() => {
          setCopied(true);
          window.setTimeout(() => setCopied(false), 1500);
        });
      }}
    >
      {copied ? (
        <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="M5 12l5 5 9-11" />
        </svg>
      ) : (
        <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <rect x="9" y="9" width="11" height="11" rx="2" />
          <path d="M5 15V5a2 2 0 0 1 2-2h10" />
        </svg>
      )}
    </button>
  );
}

export function RunsPage() {
  const { t } = useTranslation();
  const [filter, setFilter] = useState<StatusFilter>('all');
  const [query, setQuery] = useState('');

  const visible = useMemo(() => {
    return DEMO_RUNS.filter((run) => filter === 'all' || run.status === filter).filter((run) =>
      query.trim() === '' ? true : run.workflowName.toLowerCase().includes(query.trim().toLowerCase()),
    );
  }, [filter, query]);

  return (
    <main className="runs-page">
      <header className="runs-page__header">
        <p className="eyebrow">{t('common.runs.eyebrow')}</p>
        <h1>{t('common.runs.title')}</h1>
        <p className="runs-page__subtitle">{t('common.runs.subtitle')}</p>
      </header>

      <div className="runs-page__controls">
        <input
          className="runs-page__search"
          type="search"
          placeholder={t('common.runs.searchPlaceholder')}
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          aria-label={t('common.runs.searchLabel')}
        />
        <fieldset className="runs-page__filters">
          <legend className="visually-hidden">{t('common.runs.filterLegend')}</legend>
          {FILTERS.map((value) => (
            <button
              key={value}
              type="button"
              aria-pressed={filter === value}
              className={`runs-page__filter${filter === value ? ' is-active' : ''}`}
              onClick={() => setFilter(value)}
            >
              {t(STATUS_LABEL_KEY[value])}
            </button>
          ))}
        </fieldset>
      </div>

      {visible.length === 0 ? (
        <p className="runs-page__empty">{t('common.runs.empty')}</p>
      ) : (
        <ul className="runs-page__list">
          {visible.map((run) => {
            const durationSec = run.finishedAt
              ? Math.max(0, (new Date(run.finishedAt).getTime() - new Date(run.startedAt).getTime()) / 1000)
              : run.expectedDurationSec;
            return (
              <li key={run.id} className="runs-page__row">
                <div className="runs-page__row-main">
                  <Link to={`/runs/${run.id}`} className="runs-page__row-name">
                    {run.workflowName}
                  </Link>
                  <span
                    className={`runs-page__pill ${STATUS_TONE[run.status]}`}
                    data-status={run.status}
                  >
                    {run.status === 'running' && (
                      <span className="runs-page__pill-spinner" aria-hidden="true" />
                    )}
                    {t(STATUS_LABEL_KEY[run.status])}
                  </span>
                </div>

                <dl className="runs-page__row-meta-grid">
                  <div>
                    <dt>{t('common.runs.runId')}</dt>
                    <dd>
                      <code className="runs-page__run-id">#{run.runHash}</code>
                      <CopyButton value={run.id} />
                    </dd>
                  </div>
                  <div>
                    <dt>{t('common.runs.trigger.label')}</dt>
                    <dd className="runs-page__trigger">
                      <span className="runs-page__trigger-dot" data-trigger={run.trigger} aria-hidden="true" />
                      {t(TRIGGER_LABEL_KEY[run.trigger])}
                    </dd>
                  </div>
                  <div>
                    <dt>{t('common.runs.started')}</dt>
                    <dd>{t('common.runs.ago', { time: formatRelative(run.startedAt, undefined, t) })}</dd>
                  </div>
                  <div>
                    <dt>{t('common.runs.duration')}</dt>
                    <dd>
                      {run.finishedAt
                        ? formatDuration(durationSec, t)
                        : t('common.runs.durationRunning', { duration: formatDuration(durationSec, t) })}
                    </dd>
                  </div>
                </dl>

                {run.errorMessageKey && (
                  <output className="runs-page__row-error">
                    {t(run.errorMessageKey)}
                  </output>
                )}

                <div className="runs-page__row-actions">
                  {run.status === 'failure' && (
                    <button
                      type="button"
                      className="cortex-btn cortex-btn--ghost runs-page__retry"
                      aria-label={t('common.runs.retryNamed', { name: run.workflowName })}
                    >
                      <svg
                        viewBox="0 0 24 24"
                        width="14"
                        height="14"
                        fill="none"
                        stroke="currentColor"
                        strokeWidth="1.8"
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        aria-hidden="true"
                      >
                        <path d="M3 12a9 9 0 0 1 15.5-6.3L21 8" />
                        <path d="M21 3v5h-5" />
                        <path d="M21 12a9 9 0 0 1-15.5 6.3L3 16" />
                        <path d="M3 21v-5h5" />
                      </svg>
                      {t('common.runs.retry')}
                    </button>
                  )}
                  <Link to={`/runs/${run.id}`} className="runs-page__row-link">
                    {t('common.runs.viewDetail')}
                  </Link>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </main>
  );
}
