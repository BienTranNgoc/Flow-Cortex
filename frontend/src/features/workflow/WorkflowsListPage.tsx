import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import type { WorkflowResponse } from '@flow-cortex/api-client';
import { useMeQuery } from '../auth/useAuth';
import { iconForWorkflow } from '../dashboard/workflowIconKey';
import { metaFor, type StatusTone } from '../dashboard/workflowMeta';
import { WorkflowIcon } from '../dashboard/WorkflowIcon';
import { useCapabilitiesQuery } from './useCapabilities';
import { useWorkflowsQuery } from './useWorkflows';
import { OwnerCell } from './OwnerCell';
import './WorkflowsListPage.css';

type PillTone = 'active' | 'failed' | 'attention';

interface RowStatus {
  readonly label: string;
  readonly tone: PillTone;
}

const PILL_TONE: Record<StatusTone, PillTone> = {
  ready: 'active',
  experimental: 'attention',
  attention: 'failed',
};

type ScopeFilter = 'all' | 'shared' | 'mine';
type LayoutMode = 'grid' | 'list';

const SCOPES: ReadonlyArray<{ id: ScopeFilter; label: string }> = [
  { id: 'all', label: 'All' },
  { id: 'shared', label: 'Shared' },
  { id: 'mine', label: 'Mine' },
];

function statusFromMeta(workflow: WorkflowResponse): RowStatus {
  const { statusLabel, statusTone } = metaFor(workflow);
  return { label: statusLabel, tone: PILL_TONE[statusTone] };
}

function safeName(workflow: WorkflowResponse): string {
  return workflow.name ?? 'Untitled workflow';
}

/** `/workflows` — search and scope the visible workflows, then open one to run it. */
export function WorkflowsListPage() {
  const workflowsQuery = useWorkflowsQuery();
  const meQuery = useMeQuery();
  const capabilitiesQuery = useCapabilitiesQuery();
  const capabilities = capabilitiesQuery.data;

  const [query, setQuery] = useState('');
  const [scope, setScope] = useState<ScopeFilter>('all');
  const [layout, setLayout] = useState<LayoutMode>('list');

  const visible = useMemo(() => {
    if (capabilitiesQuery.isPending) return undefined;
    return (workflowsQuery.data ?? []).filter(
      (workflow) =>
        capabilities?.productVideoEnabled !== false ||
        workflow.id !== capabilities.productVideoWorkflowId,
    );
  }, [capabilitiesQuery.isPending, workflowsQuery.data, capabilities]);

  const filtered = useMemo(() => {
    if (!visible) return undefined;
    const q = query.trim().toLowerCase();
    return visible.filter((workflow) => {
      if (scope === 'shared' && !workflow.isShared) return false;
      if (scope === 'mine' && workflow.isShared) return false;
      if (q === '') return true;
      return safeName(workflow).toLowerCase().includes(q);
    });
  }, [visible, query, scope]);

  const total = visible?.length ?? 0;
  const showLoading =
    workflowsQuery.isLoading || (capabilitiesQuery.isPending && !workflowsQuery.isError);

  const firstName = meQuery.data?.name?.split(' ')[0] ?? 'there';

  return (
    <main className="wf-list">
      <header className="wf-list__header">
        <div>
          <h1 className="wf-list__title">Workflows</h1>
          <p className="wf-list__welcome">
            Welcome back, <strong>{firstName}</strong>. Manage your pipelines.
          </p>
        </div>
      </header>

      <div className="wf-list__toolbar">
        <label className="wf-list__search" aria-label="Search workflows">
          <svg
            viewBox="0 0 24 24"
            width="16"
            height="16"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <circle cx="11" cy="11" r="7" />
            <path d="M20 20l-3.5-3.5" />
          </svg>
          <input
            type="search"
            placeholder="Search workflows..."
            value={query}
            onChange={(event) => setQuery(event.target.value)}
          />
        </label>

        <fieldset className="wf-list__scope">
          <legend className="visually-hidden">Scope</legend>
          <span aria-hidden="true">Scope:</span>
          {SCOPES.map((s) => (
            <button
              key={s.id}
              type="button"
              aria-pressed={scope === s.id}
              className={`wf-list__scope-btn${scope === s.id ? ' is-active' : ''}`}
              onClick={() => setScope(s.id)}
            >
              {s.label}
            </button>
          ))}
        </fieldset>

        <fieldset className="wf-list__layout">
          <legend className="visually-hidden">Layout</legend>
          <button
            type="button"
            aria-pressed={layout === 'grid'}
            className={`wf-list__layout-btn${layout === 'grid' ? ' is-active' : ''}`}
            onClick={() => setLayout('grid')}
            aria-label="Grid layout"
          >
            <svg
              viewBox="0 0 24 24"
              width="16"
              height="16"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.6"
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
            >
              <rect x="3" y="3" width="7" height="7" />
              <rect x="14" y="3" width="7" height="7" />
              <rect x="3" y="14" width="7" height="7" />
              <rect x="14" y="14" width="7" height="7" />
            </svg>
          </button>
          <button
            type="button"
            aria-pressed={layout === 'list'}
            className={`wf-list__layout-btn${layout === 'list' ? ' is-active' : ''}`}
            onClick={() => setLayout('list')}
            aria-label="List layout"
          >
            <svg
              viewBox="0 0 24 24"
              width="16"
              height="16"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.6"
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
            >
              <line x1="3" y1="6" x2="21" y2="6" />
              <line x1="3" y1="12" x2="21" y2="12" />
              <line x1="3" y1="18" x2="21" y2="18" />
            </svg>
          </button>
        </fieldset>
      </div>

      {showLoading && <output className="wf-list__status">Loading workflows…</output>}
      {workflowsQuery.isError && !showLoading && (
        <p role="alert" className="wf-list__status wf-list__status--error">
          Could not load workflows.
        </p>
      )}
      {filtered && filtered.length === 0 && !showLoading && (
        <p className="wf-list__status">No workflows match the current filter.</p>
      )}

      {filtered && filtered.length > 0 && (
        <>
          {layout === 'list' ? (
            <div className="wf-list__table-wrap" role="region" aria-label="Workflows table">
              <table className="wf-list__table">
                <thead>
                  <tr>
                    <th scope="col">Name</th>
                    <th scope="col">Status</th>
                    <th scope="col">Owner</th>
                    <th scope="col">Steps</th>
                    <th scope="col" className="wf-list__actions-col">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {filtered.map((workflow) => {
                    const status = statusFromMeta(workflow);
                    const steps = workflow.steps ?? [];
                    const label = safeName(workflow);
                    return (
                      <tr key={workflow.id}>
                        <td>
                          <div className="wf-list__name">
                            <span className="wf-list__icon" aria-hidden="true">
                              <WorkflowIcon icon={iconForWorkflow(label)} size={18} />
                            </span>
                            <div>
                              <Link to={`/workflows/${workflow.id}`} className="wf-list__name-link">
                                {label}
                              </Link>
                            </div>
                          </div>
                        </td>
                        <td>
                          <span className={`wf-list__status-pill wf-list__status-pill--${status.tone}`}>
                            <span className="wf-list__status-dot" aria-hidden="true" />
                            {status.label}
                          </span>
                        </td>
                        <td>
                          <OwnerCell workflow={workflow} />
                        </td>
                        <td className="wf-list__muted">
                          {steps.length} {steps.length === 1 ? 'step' : 'steps'}
                        </td>
                        <td>
                          <div className="wf-list__actions" aria-label="Row actions">
                            <Link
                              to={`/workflows/${workflow.id}`}
                              className="cortex-btn cortex-btn--icon wf-list__run-btn"
                              title="Run workflow"
                              aria-label={`Run ${label}`}
                            >
                              <svg
                                viewBox="0 0 24 24"
                                width="14"
                                height="14"
                                fill="currentColor"
                                aria-hidden="true"
                              >
                                <path d="M8 5v14l11-7z" />
                              </svg>
                            </Link>
                          </div>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          ) : (
            <ul className="wf-list__grid">
              {filtered.map((workflow) => {
                const status = statusFromMeta(workflow);
                const steps = workflow.steps ?? [];
                const label = safeName(workflow);
                return (
                  <li key={workflow.id} className="wf-list__grid-card">
                    <div className="wf-list__name">
                      <span className="wf-list__icon" aria-hidden="true">
                        <WorkflowIcon icon={iconForWorkflow(label)} size={18} />
                      </span>
                      <Link to={`/workflows/${workflow.id}`} className="wf-list__name-link">
                        {label}
                      </Link>
                    </div>
                    <p className={`wf-list__status-pill wf-list__status-pill--${status.tone}`}>
                      <span className="wf-list__status-dot" aria-hidden="true" />
                      {status.label}
                    </p>
                    <OwnerCell workflow={workflow} />
                    <dl className="wf-list__grid-meta">
                      <div>
                        <dt>Steps</dt>
                        <dd>
                          {steps.length} {steps.length === 1 ? 'step' : 'steps'}
                        </dd>
                      </div>
                    </dl>
                    <div className="wf-list__actions">
                      <Link
                        to={`/workflows/${workflow.id}`}
                        className="cortex-btn cortex-btn--icon wf-list__run-btn"
                        title="Run workflow"
                        aria-label={`Run ${label}`}
                      >
                        <svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor" aria-hidden="true">
                          <path d="M8 5v14l11-7z" />
                        </svg>
                      </Link>
                    </div>
                  </li>
                );
              })}
            </ul>
          )}

          <footer className="wf-list__footer">
            <span>Showing {filtered.length} of {total} workflows</span>
          </footer>
        </>
      )}
    </main>
  );
}
