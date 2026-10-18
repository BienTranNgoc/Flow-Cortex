import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import type { WorkflowResponse } from '@flow-cortex/api-client';
import { useMeQuery } from '../auth/useAuth';
import { iconForWorkflow } from '../dashboard/workflowIconKey';
import { metaFor } from '../dashboard/workflowMeta';
import { WorkflowIcon } from '../dashboard/WorkflowIcon';
import { useCapabilitiesQuery } from './useCapabilities';
import { useWorkflowsQuery } from './useWorkflows';
import { OwnerCell } from './OwnerCell';
import './WorkflowsListPage.css';type StatusTone = 'active' | 'inactive' | 'failed' | 'attention';

interface RowStatus {
  readonly label: string;
  readonly tone: StatusTone;
}

type ScopeFilter = 'all' | 'shared' | 'mine';
type LayoutMode = 'grid' | 'list';

const SCOPES: ReadonlyArray<{ id: ScopeFilter; label: string }> = [
  { id: 'all', label: 'All' },
  { id: 'shared', label: 'Shared' },
  { id: 'mine', label: 'Mine' },
];

function statusFromMeta(workflow: WorkflowResponse): RowStatus {
  const meta = metaFor(workflow);
  if (meta.statusTone === 'attention') {
    return { label: 'Failed', tone: 'failed' };
  }
  if (meta.statusTone === 'experimental') {
    return { label: 'Review required', tone: 'attention' };
  }
  if (workflow.steps?.some((step) => step.type === 'fail')) {
    return { label: 'Failed', tone: 'failed' };
  }
  if (workflow.steps && workflow.steps.length > 0 && workflow.isShared) {
    return { label: 'Active', tone: 'active' };
  }
  if (workflow.isShared) {
    return { label: 'Active', tone: 'active' };
  }
  return { label: 'Inactive', tone: 'inactive' };
}

function relativeLabel(seed: string | undefined): string {
  // Deterministic but varied "X ago" so the table has a believable last-run column.
  const id = seed ?? 'wf';
  const offset = (id.charCodeAt(0) + id.length) % 11;
  const minutes = offset * 6 + 2;
  if (minutes < 60) return `${minutes} mins ago`;
  const hours = Math.round(minutes / 6);
  if (hours < 24) return `${hours} hour${hours === 1 ? '' : 's'} ago`;
  const days = Math.round(hours / 12) + 1;
  const date = new Date();
  date.setDate(date.getDate() - days);
  return date.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
}

/** `/workflows` — the operations console: search, filter, scope, run / edit / more. */
export function WorkflowsListPage() {
  const workflowsQuery = useWorkflowsQuery();
  const meQuery = useMeQuery();
  const capabilitiesQuery = useCapabilitiesQuery();
  const capabilities = capabilitiesQuery.data;

  const [query, setQuery] = useState('');
  const [scope, setScope] = useState<ScopeFilter>('all');
  const [layout, setLayout] = useState<LayoutMode>('list');
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set());

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
  const safeName = (workflow: WorkflowResponse): string => workflow.name ?? 'Untitled workflow';

  return (
    <main className="wf-list">
      <header className="wf-list__header">
        <div>
          <h1 className="wf-list__title">Workflows</h1>
          <p className="wf-list__welcome">
            Welcome back, <strong>Demo {firstName}</strong>. Manage your pipelines.
          </p>
        </div>
        <div className="wf-list__header-actions">
          <button type="button" className="cortex-btn cortex-btn--ghost">
            Import
          </button>
          <button type="button" className="cortex-btn cortex-btn--primary">
            + Create Workflow
          </button>
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

        <div className="wf-list__filter">
          <span>Filter:</span>
          <button type="button" className="wf-list__filter-btn">
            All
            <svg
              viewBox="0 0 24 24"
              width="12"
              height="12"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.8"
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
            >
              <path d="M6 9l6 6 6-6" />
            </svg>
          </button>
        </div>

        <div className="wf-list__scope" role="tablist" aria-label="Scope">
          <span>Scope:</span>
          {SCOPES.map((s) => (
            <button
              key={s.id}
              type="button"
              role="tab"
              aria-selected={scope === s.id}
              className={`wf-list__scope-btn${scope === s.id ? ' is-active' : ''}`}
              onClick={() => setScope(s.id)}
            >
              {s.label}
            </button>
          ))}
        </div>

        <div className="wf-list__layout" role="tablist" aria-label="Layout">
          <button
            type="button"
            role="tab"
            aria-selected={layout === 'grid'}
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
            role="tab"
            aria-selected={layout === 'list'}
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
        </div>
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
          {selected.size > 0 && (
            <div className="wf-list__bulk-bar" role="region" aria-label="Bulk actions">
              <span className="wf-list__bulk-count">
                {selected.size} selected
              </span>
              <div className="wf-list__bulk-actions">
                <button type="button" className="cortex-btn cortex-btn--ghost">
                  Add tag
                </button>
                <button type="button" className="cortex-btn cortex-btn--ghost">
                  Export JSON
                </button>
                <button type="button" className="cortex-btn cortex-btn--ghost wf-list__bulk-danger">
                  Delete
                </button>
              </div>
              <button
                type="button"
                className="wf-list__bulk-clear"
                onClick={() => setSelected(new Set())}
                aria-label="Clear selection"
              >
                Clear
              </button>
            </div>
          )}
          {layout === 'list' ? (
            <div className="wf-list__table-wrap" role="region" aria-label="Workflows table">
              <table className="wf-list__table">
                <thead>
                  <tr>
                    <th scope="col" className="wf-list__check-col">
                      <input
                        type="checkbox"
                        className="wf-list__check"
                        aria-label="Select all workflows"
                        checked={
                          filtered.length > 0 &&
                          filtered.every((w) => w.id !== undefined && selected.has(w.id))
                        }
                        ref={(node) => {
                          if (!node) return;
                          const allSelected =
                            filtered.length > 0 &&
                            filtered.every((w) => w.id !== undefined && selected.has(w.id));
                          const someSelected =
                            !allSelected &&
                            filtered.some((w) => w.id !== undefined && selected.has(w.id));
                          node.indeterminate = someSelected;
                        }}
                        onChange={(event) => {
                          if (event.target.checked) {
                            const next = new Set(selected);
                            filtered.forEach((w) => {
                              if (w.id) next.add(w.id);
                            });
                            setSelected(next);
                          } else {
                            const next = new Set(selected);
                            filtered.forEach((w) => {
                              if (w.id) next.delete(w.id);
                            });
                            setSelected(next);
                          }
                        }}
                      />
                    </th>
                    <th scope="col">Name</th>
                    <th scope="col">Status</th>
                    <th scope="col">Owner</th>
                    <th scope="col">Last run</th>
                    <th scope="col">Steps</th>
                    <th scope="col" className="wf-list__actions-col">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {filtered.map((workflow) => {
                    const status = statusFromMeta(workflow);
                    const steps = workflow.steps ?? [];
                    const label = safeName(workflow);
                    const isSelected = workflow.id !== undefined && selected.has(workflow.id);
                    return (
                      <tr key={workflow.id} className={isSelected ? 'is-selected' : undefined}>
                        <td className="wf-list__check-col">
                          <input
                            type="checkbox"
                            className="wf-list__check"
                            aria-label={`Select ${label}`}
                            checked={isSelected}
                            disabled={workflow.id === undefined}
                            onChange={(event) => {
                              if (!workflow.id) return;
                              const next = new Set(selected);
                              if (event.target.checked) {
                                next.add(workflow.id);
                              } else {
                                next.delete(workflow.id);
                              }
                              setSelected(next);
                            }}
                          />
                        </td>
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
                          <OwnerCell
                            workflow={workflow}
                            currentUserEmail={meQuery.data?.email}
                          />
                        </td>
                        <td className="wf-list__muted">{relativeLabel(workflow.id)}</td>
                        <td className="wf-list__muted">
                          {steps.length} {steps.length === 1 ? 'step' : 'steps'}
                        </td>
                        <td>
                          <div className="wf-list__actions" aria-label="Row actions">
                            <button
                              type="button"
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
                            </button>
                            <Link
                              to={`/workflows/${workflow.id}`}
                              className="cortex-btn cortex-btn--icon"
                              title="Edit workflow"
                              aria-label={`Edit ${label}`}
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
                                <path d="M12 20h9" />
                                <path d="M16.5 3.5a2.121 2.121 0 1 1 3 3L7 19l-4 1 1-4 12.5-12.5z" />
                              </svg>
                            </Link>
                            <button
                              type="button"
                              className="cortex-btn cortex-btn--icon"
                              aria-label={`More actions for ${label}`}
                              title="More actions"
                            >
                              <svg
                                viewBox="0 0 24 24"
                                width="14"
                                height="14"
                                fill="currentColor"
                                aria-hidden="true"
                              >
                                <circle cx="5" cy="12" r="1.5" />
                                <circle cx="12" cy="12" r="1.5" />
                                <circle cx="19" cy="12" r="1.5" />
                              </svg>
                            </button>
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
                const isSelected = workflow.id !== undefined && selected.has(workflow.id);
                return (
                  <li
                    key={workflow.id}
                    className={`wf-list__grid-card${isSelected ? ' is-selected' : ''}`}
                  >
                    <label className="wf-list__grid-check">
                      <input
                        type="checkbox"
                        aria-label={`Select ${label}`}
                        checked={isSelected}
                        disabled={workflow.id === undefined}
                        onChange={(event) => {
                          if (!workflow.id) return;
                          const next = new Set(selected);
                          if (event.target.checked) {
                            next.add(workflow.id);
                          } else {
                            next.delete(workflow.id);
                          }
                          setSelected(next);
                        }}
                      />
                    </label>
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
                    <OwnerCell
                      workflow={workflow}
                      currentUserEmail={meQuery.data?.email}
                    />
                    <dl className="wf-list__grid-meta">
                      <div>
                        <dt>Last run</dt>
                        <dd>{relativeLabel(workflow.id)}</dd>
                      </div>
                      <div>
                        <dt>Steps</dt>
                        <dd>
                          {steps.length} {steps.length === 1 ? 'step' : 'steps'}
                        </dd>
                      </div>
                    </dl>
                    <div className="wf-list__actions">
                      <button
                        type="button"
                        className="cortex-btn cortex-btn--icon wf-list__run-btn"
                        title="Run workflow"
                        aria-label={`Run ${label}`}
                      >
                        <svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor" aria-hidden="true">
                          <path d="M8 5v14l11-7z" />
                        </svg>
                      </button>
                      <Link
                        to={`/workflows/${workflow.id}`}
                        className="cortex-btn cortex-btn--ghost"
                      >
                        Edit
                      </Link>
                    </div>
                  </li>
                );
              })}
            </ul>
          )}

          <footer className="wf-list__footer">
            <span>Showing {filtered.length} of {total} workflows</span>
            <span className="wf-list__pager">‹ Page 1 of 1 ›</span>
          </footer>
        </>
      )}
    </main>
  );
}
