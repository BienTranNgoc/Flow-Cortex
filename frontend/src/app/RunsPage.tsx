import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { DEMO_RUNS, type DemoRun } from './runsDemo';
import './RunsPage.css';

type StatusFilter = 'all' | 'success' | 'failure' | 'waiting' | 'running';

const STATUS_LABEL: Record<StatusFilter, string> = {
  all: 'All',
  success: 'Succeeded',
  failure: 'Failed',
  waiting: 'Waiting for review',
  running: 'Running',
};

const STATUS_TONE: Record<DemoRun['status'], string> = {
  success: 'runs-page__pill--success',
  failure: 'runs-page__pill--failure',
  waiting: 'runs-page__pill--waiting',
  running: 'runs-page__pill--running',
};

const TRIGGER_LABEL: Record<DemoRun['trigger'], string> = {
  manual: 'Manual',
  webhook: 'Webhook',
  schedule: 'Scheduled',
};

const FILTERS: readonly StatusFilter[] = ['all', 'success', 'failure', 'waiting', 'running'];

function formatDuration(seconds: number): string {
  if (seconds < 60) return `${Math.round(seconds)}s`;
  const mins = Math.floor(seconds / 60);
  const secs = Math.round(seconds % 60);
  if (mins < 60) return secs > 0 ? `${mins}m ${secs}s` : `${mins}m`;
  const hours = Math.floor(mins / 60);
  const remMins = mins % 60;
  return remMins > 0 ? `${hours}h ${remMins}m` : `${hours}h`;
}

function formatRelative(startedAt: string, finishedAt: string | undefined): string {
  const end = finishedAt ? new Date(finishedAt) : new Date();
  const diffMs = end.getTime() - new Date(startedAt).getTime();
  const mins = Math.max(0, Math.round(diffMs / 60_000));
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m`;
  const hours = Math.round(mins / 60);
  if (hours < 24) return `${hours}h`;
  return `${Math.round(hours / 24)}d`;
}

function CopyButton({ value }: { readonly value: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <button
      type="button"
      className="runs-page__copy"
      aria-label={`Copy ${value}`}
      title={`Copy ${value}`}
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
        <p className="eyebrow">RUN HISTORY</p>
        <h1>Recent runs</h1>
        <p className="runs-page__subtitle">
          Track every pipeline you started: what is running, what needs your review, and what failed.
        </p>
      </header>

      <div className="runs-page__controls">
        <input
          className="runs-page__search"
          type="search"
          placeholder="Search by workflow name…"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          aria-label="Search runs"
        />
        <div className="runs-page__filters" role="tablist" aria-label="Filter by status">
          {FILTERS.map((value) => (
            <button
              key={value}
              type="button"
              role="tab"
              aria-selected={filter === value}
              className={`runs-page__filter${filter === value ? ' is-active' : ''}`}
              onClick={() => setFilter(value)}
            >
              {STATUS_LABEL[value]}
            </button>
          ))}
        </div>
      </div>

      {visible.length === 0 ? (
        <p className="runs-page__empty">No runs match the current filter.</p>
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
                    {STATUS_LABEL[run.status]}
                  </span>
                </div>

                <dl className="runs-page__row-meta-grid">
                  <div>
                    <dt>Run ID</dt>
                    <dd>
                      <code className="runs-page__run-id">#{run.runHash}</code>
                      <CopyButton value={run.id} />
                    </dd>
                  </div>
                  <div>
                    <dt>Trigger</dt>
                    <dd className="runs-page__trigger">
                      <span className="runs-page__trigger-dot" data-trigger={run.trigger} aria-hidden="true" />
                      {TRIGGER_LABEL[run.trigger]}
                    </dd>
                  </div>
                  <div>
                    <dt>Started</dt>
                    <dd>{formatRelative(run.startedAt, undefined)} ago</dd>
                  </div>
                  <div>
                    <dt>Duration</dt>
                    <dd>
                      {run.finishedAt
                        ? formatDuration(durationSec)
                        : `${formatDuration(durationSec)} · running`}
                    </dd>
                  </div>
                </dl>

                {run.errorMessage && (
                  <output className="runs-page__row-error">
                    {run.errorMessage}
                  </output>
                )}

                <div className="runs-page__row-actions">
                  {run.status === 'failure' && (
                    <button
                      type="button"
                      className="cortex-btn cortex-btn--ghost runs-page__retry"
                      aria-label={`Retry ${run.workflowName}`}
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
                      Retry
                    </button>
                  )}
                  <Link to={`/runs/${run.id}`} className="runs-page__row-link">
                    View run detail →
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
