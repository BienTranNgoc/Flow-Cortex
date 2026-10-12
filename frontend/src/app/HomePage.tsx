import { type WorkflowResponse, type WorkflowStepResponseMode } from '@flow-cortex/api-client';
import { Link } from 'react-router-dom';
import { useMeQuery } from '../features/auth/useAuth';
import { useWorkflowsQuery } from '../features/workflow/useWorkflows';
import './HomePage.css';

type StageTone = 'blue' | 'cyan' | 'violet' | 'amber' | 'green' | 'rose';
type IconTone = 'blue' | 'cyan' | 'amber';

const TONE_BY_MODE: Record<WorkflowStepResponseMode, IconTone> = {
  inline: 'blue',
  remote: 'cyan',
};

const STAGE_TONE_CYCLE: readonly StageTone[] = ['blue', 'cyan', 'violet', 'amber', 'green', 'rose'];

function firstStepMode(workflow: WorkflowResponse): WorkflowStepResponseMode | undefined {
  const first = workflow.steps?.[0];
  return first?.mode;
}

function iconFor(workflow: WorkflowResponse): string {
  return (workflow.name ?? workflow.id ?? '?').slice(0, 2).toUpperCase();
}

function stageToneAt(index: number): StageTone {
  return STAGE_TONE_CYCLE[index % STAGE_TONE_CYCLE.length] as StageTone;
}

export function HomePage() {
  const meQuery = useMeQuery();
  const workflowsQuery = useWorkflowsQuery();
  if (meQuery.isLoading) return <output>Loading…</output>;
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
    return <output>Loading…</output>;
  }

  const workflows = workflowsQuery.data ?? [];

  return (
    <main className="launchpad">
      <section className="launchpad-hero">
        <p className="eyebrow">CREATOR LAUNCHPAD · READY TO CREATE</p>
        <h1>
          Your AI media <span>studio</span>
        </h1>
        <p>
          One click launches a production-ready pipeline: source, process, review, export.
        </p>
      </section>

      <section className="launchpad-cards" aria-labelledby="launchpad-cards-title">
        <h2 id="launchpad-cards-title" className="launchpad-section-title">
          Pick a pipeline
        </h2>
        {workflowsQuery.isLoading && <output>Loading workflows…</output>}
        {workflowsQuery.isError && (
          <p role="alert" className="form-error">
            Could not load workflows.{' '}
            <button type="button" onClick={() => void workflowsQuery.refetch()}>
              Retry
            </button>
          </p>
        )}
        {!workflowsQuery.isLoading && !workflowsQuery.isError && workflows.length === 0 && (
          <p>No workflows available yet.</p>
        )}
        {workflows.length > 0 && (
          <div className="workflow-grid">
            {workflows.map((workflow) => {
              const mode = firstStepMode(workflow);
              const iconTone: IconTone = mode ? TONE_BY_MODE[mode] : 'amber';
              const linkTo = workflow.id ? `/workflows/${workflow.id}` : '/workflows';
              return (
                <article key={workflow.id ?? workflow.name} className="workflow-card-tile">
                  <header className="workflow-card-header">
                    <span className={`workflow-card-icon tone-${iconTone}`} aria-hidden="true">
                      {iconFor(workflow)}
                    </span>
                    <h3 className="workflow-card-title">{workflow.name ?? 'Untitled workflow'}</h3>
                  </header>

                  {workflow.steps && workflow.steps.length > 0 && (
                    <ul className="workflow-card-stages">
                      {workflow.steps.map((step, index) => (
                        <li
                          key={step.id ?? `${index}-${step.name}`}
                          className={`workflow-card-stage tone-${stageToneAt(index)}`}
                        >
                          {step.name ?? `Step ${step.stepOrder ?? index + 1}`}
                        </li>
                      ))}
                    </ul>
                  )}

                  <footer className="workflow-card-footer">
                    <Link className="workflow-card-launch" to={linkTo}>
                      Launch Workflow →
                    </Link>
                  </footer>
                </article>
              );
            })}
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
          Welcome, {meQuery.data.name} · <Link to="/workflows">View all workflows</Link>
        </p>
      </section>
    </main>
  );
}
