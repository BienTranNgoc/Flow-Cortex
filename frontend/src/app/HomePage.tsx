import { Link } from 'react-router-dom';
import { useMeQuery } from '../features/auth/useAuth';
import { useWorkflowsQuery } from '../features/workflow/useWorkflows';
import { FilterBar } from '../features/dashboard/FilterBar';
import { SearchBar } from '../features/dashboard/SearchBar';
import { useDashboardWorkflows } from '../features/dashboard/useDashboardWorkflows';
import { WorkflowCard } from '../features/dashboard/WorkflowCard';
import './HomePage.css';

export function HomePage() {
  const meQuery = useMeQuery();
  const workflowsQuery = useWorkflowsQuery();
  const workflows = workflowsQuery.data ?? [];
  const dashboard = useDashboardWorkflows(workflows);
  const { filtered, counts, query, setQuery, category, setCategory, reset } = dashboard;
  const hasWorkflows = workflows.length > 0;

  if (meQuery.isLoading) return <output className="dashboard-loading">Loading…</output>;
  if (meQuery.isError) {
    return (
      <main className="launchpad">
        <p role="alert" className="form-error">
          Could not load your profile.{' '}
          <button type="button" onClick={() => void meQuery.refetch()}>
            Retry
          </button>
        </p>
      </main>
    );
  }
  if (!meQuery.data) {
    return <output className="dashboard-loading">Loading…</output>;
  }

  return (
    <main className="launchpad">
      <section className="launchpad-hero" aria-labelledby="launchpad-hero-title">
        <div className="launchpad-hero__glow" aria-hidden="true">
          <span className="launchpad-hero__glow-a" />
          <span className="launchpad-hero__glow-b" />
        </div>
        <div className="launchpad-hero__badge">
          <span className="launchpad-hero__pulse" aria-hidden="true" />
          <span className="launchpad-hero__bolt" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="12" height="12" fill="currentColor" aria-hidden="true">
              <path d="M13 2L4 14h6l-1 8 9-12h-6z" />
            </svg>
          </span>
          <span>CREATOR LAUNCHPAD · READY TO CREATE</span>
        </div>
        <h1 id="launchpad-hero-title" className="launchpad-hero__title">
          <span className="launchpad-hero__title-strong">Your AI media</span>{' '}
          <span className="launchpad-hero__title-accent">studio</span>
        </h1>
        <p className="launchpad-hero__lede">
          One click launches a production-ready pipeline: source, process, review, export.
        </p>
      </section>

      <section className="launchpad-cards" aria-labelledby="launchpad-cards-title">
        <header className="launchpad-cards__head">
          <h2 id="launchpad-cards-title" className="launchpad-section-title">
            Pick a pipeline
          </h2>
          {hasWorkflows && (
            <Link to="/workflows" className="launchpad-cards__see-all">
              View all workflows
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <path d="M5 12h14M13 6l6 6-6 6" />
              </svg>
            </Link>
          )}
        </header>

        {hasWorkflows && (
          <div className="launchpad-cards__controls">
            <div className="launchpad-cards__search">
              <SearchBar value={query} onChange={setQuery} />
            </div>
            <FilterBar value={category} onChange={setCategory} counts={counts} />
          </div>
        )}

        {workflowsQuery.isLoading && <output>Loading workflows…</output>}
        {workflowsQuery.isError && (
          <p role="alert" className="form-error">
            Could not load workflows.{' '}
            <button type="button" onClick={() => void workflowsQuery.refetch()}>
              Retry
            </button>
          </p>
        )}
        {hasWorkflows && !workflowsQuery.isLoading && !workflowsQuery.isError && filtered.length === 0 && (
          <div className="launchpad-cards__empty" role="status">
            <p>No workflows match your filter.</p>
            <button type="button" onClick={reset}>
              Clear filters
            </button>
          </div>
        )}
        {!hasWorkflows && !workflowsQuery.isLoading && !workflowsQuery.isError && (
          <p>No workflows available yet.</p>
        )}

        {filtered.length > 0 && (
          <div className="workflow-grid">
            {filtered.map((workflow) => (
              <WorkflowCard key={workflow.id ?? workflow.name} workflow={workflow} />
            ))}
          </div>
        )}
      </section>

      <section className="launchpad-stages" aria-labelledby="launchpad-stages-title">
        <h2 id="launchpad-stages-title">How Flow-Cortex Handles Your Video Runs</h2>
        <ol className="launchpad-stages-list">
          <li className="launchpad-stage">
            <span className="launchpad-stage-order" aria-hidden="true">
              1
            </span>
            <h3>Source</h3>
            <p>Submit a link or upload a media file. Required fields and limits are checked up front.</p>
          </li>
          <li className="launchpad-stage">
            <span className="launchpad-stage-order" aria-hidden="true">
              2
            </span>
            <h3>Process</h3>
            <p>Workers run each step in order. Status, attempts and errors are saved on every node.</p>
          </li>
          <li className="launchpad-stage">
            <span className="launchpad-stage-order" aria-hidden="true">
              3
            </span>
            <h3>Review</h3>
            <p>Pauses at the review gates you configured. Edit content, then approve to continue.</p>
          </li>
          <li className="launchpad-stage">
            <span className="launchpad-stage-order" aria-hidden="true">
              4
            </span>
            <h3>Export</h3>
            <p>Download the finished output while it is still within the asset retention window.</p>
          </li>
        </ol>
      </section>

      <section className="launchpad-footer">
        <p>
          Welcome, {meQuery.data.name} · <Link to="/profile">Manage your profile</Link>
        </p>
      </section>
    </main>
  );
}
