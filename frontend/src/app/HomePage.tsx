import { type WorkflowResponse } from '@flow-cortex/api-client';
import { Link, Navigate, useLocation } from 'react-router-dom';
import { useMeQuery } from '../features/auth/useAuth';
import { useWorkflowsQuery } from '../features/workflow/useWorkflows';
import './HomePage.css';

type Stage = { label: string; tone: 'blue' | 'cyan' | 'violet' | 'amber' | 'green' | 'rose' };

type CardSpec = {
  /** Matches against `workflow.name` returned by the API. */
  name: string;
  icon: string;
  iconTone: 'blue' | 'cyan' | 'amber';
  priority: 'P0' | 'P1' | 'P2';
  category: string;
  categoryTone: 'gold' | 'cyan' | 'violet';
  status: 'Ready' | 'Live' | 'Beta';
  statusTone: 'green' | 'cyan' | 'amber';
  etaSeconds: number;
  stages: readonly Stage[];
  gpuCredits: number;
};

/** Three showcase cards. Each card picks its real workflow by `name` from the API list. */
const CARDS: readonly CardSpec[] = [
  {
    name: 'Dougyin Translation & Dubbing',
    icon: 'DT',
    iconTone: 'blue',
    priority: 'P0',
    category: 'CORE MVP',
    categoryTone: 'gold',
    status: 'Ready',
    statusTone: 'green',
    etaSeconds: 45,
    stages: [
      { label: 'Video Ingest', tone: 'blue' },
      { label: 'Scrap', tone: 'cyan' },
      { label: 'Demucs BGM Separation', tone: 'violet' },
      { label: 'Human Gate Review', tone: 'amber' },
      { label: 'ElevenLabs V6 Dub', tone: 'blue' },
    ],
    gpuCredits: 1,
  },
  {
    name: 'Product Content & Video',
    icon: 'PV',
    iconTone: 'cyan',
    priority: 'P1',
    category: 'E-COMMERCE',
    categoryTone: 'cyan',
    status: 'Live',
    statusTone: 'cyan',
    etaSeconds: 60,
    stages: [
      { label: 'Shopee/Shop Scrape', tone: 'cyan' },
      { label: 'USF Feature Extraction', tone: 'violet' },
      { label: 'Script Approval Gate', tone: 'amber' },
      { label: 'Multi-Aspect', tone: 'blue' },
    ],
    gpuCredits: 1,
  },
  {
    name: 'Check Video',
    icon: 'CV',
    iconTone: 'amber',
    priority: 'P1',
    category: 'MEDIA INTAKE',
    categoryTone: 'violet',
    status: 'Live',
    statusTone: 'cyan',
    etaSeconds: 30,
    stages: [
      { label: 'Inspect the file', tone: 'blue' },
      { label: 'Extract source audio', tone: 'cyan' },
    ],
    gpuCredits: 0,
  },
];

function findWorkflowByName(
  workflows: readonly WorkflowResponse[] | undefined,
  name: string,
): WorkflowResponse | undefined {
  return workflows?.find((wf) => wf.name === name);
}

function formatEta(seconds: number): string {
  return `~${seconds}s`;
}

export function HomePage() {
  const location = useLocation();
  const meQuery = useMeQuery();
  const workflowsQuery = useWorkflowsQuery();

  if (meQuery.isLoading) return <output>Loading…</output>;
  if (meQuery.isError || !meQuery.data) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }

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
            Could not load workflows.
          </p>
        )}
        {!workflowsQuery.isLoading && !workflowsQuery.isError && (
          <div className="workflow-grid">
            {CARDS.map((card) => {
              const workflow = findWorkflowByName(workflowsQuery.data, card.name);
              const linkTo = workflow ? `/workflows/${workflow.id}` : '/workflows';
              const disabled = !workflow;
              return (
                <article
                  key={card.name}
                  className={`workflow-card-tile${disabled ? ' is-disabled' : ''}`}
                >
                  <header className="workflow-card-header">
                    <span className={`workflow-card-icon tone-${card.iconTone}`} aria-hidden="true">
                      {card.icon}
                    </span>
                    <span className="workflow-card-priority">{card.priority}</span>
                    <span className={`workflow-card-category tone-${card.categoryTone}`}>
                      {card.category}
                    </span>
                    <span className={`workflow-card-status tone-${card.statusTone}`}>
                      <span className="workflow-card-dot" aria-hidden="true" />
                      {card.status}
                    </span>
                    <span className="workflow-card-eta" aria-label="Estimated duration">
                      {formatEta(card.etaSeconds)}
                    </span>
                  </header>

                  <h3 className="workflow-card-title">{card.name}</h3>

                  <ul className="workflow-card-stages">
                    {card.stages.map((stage) => (
                      <li key={stage.label} className={`workflow-card-stage tone-${stage.tone}`}>
                        {stage.label}
                      </li>
                    ))}
                  </ul>

                  <footer className="workflow-card-footer">
                    <span className="workflow-card-cost" aria-label="GPU credit cost">
                      <span aria-hidden="true">⚡</span> {card.gpuCredits} GPU Credit
                      {card.gpuCredits === 1 ? '' : 's'}
                    </span>
                    {disabled ? (
                      <span className="workflow-card-launch disabled" aria-disabled="true">
                        Coming soon
                      </span>
                    ) : (
                      <Link className="workflow-card-launch" to={linkTo}>
                        Launch Workflow →
                      </Link>
                    )}
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
