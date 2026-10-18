import { Link } from 'react-router-dom';
import type { WorkflowResponse } from '@flow-cortex/api-client';
import { iconForWorkflow } from './workflowIconKey';
import { WorkflowIcon } from './WorkflowIcon';
import { metaFor, stepsFor } from './workflowMeta';
import { PipelineThumb } from './PipelineThumb';
import './WorkflowCard.css';

interface WorkflowCardProps {
  readonly workflow: WorkflowResponse;
}

function etaLabel(seconds: number): string {
  if (seconds < 60) return `~${seconds}s`;
  const minutes = Math.round(seconds / 30) / 2;
  return `~${minutes}m`;
}

const TONE_TO_CLASS: Record<'ready' | 'experimental' | 'attention', string> = {
  ready: 'workflow-card__status--ready',
  experimental: 'workflow-card__status--experimental',
  attention: 'workflow-card__status--attention',
};

export function WorkflowCard({ workflow }: Readonly<WorkflowCardProps>) {
  const meta = metaFor(workflow);
  const steps = stepsFor(workflow);
  const linkTo = workflow.id ? `/workflows/${workflow.id}` : '/workflows';
  const iconKey = iconForWorkflow(workflow.name);
  const stepCount = steps.length;

  return (
    <article className="workflow-card" tabIndex={-1}>
      <Link to={linkTo} className="workflow-card__link" aria-label={`Open ${workflow.name ?? 'workflow'}`}>
        <header className="workflow-card__head">
          <span className="workflow-card__icon" aria-hidden="true">
            <WorkflowIcon icon={iconKey} size={20} />
          </span>
          <div className="workflow-card__head-text">
            <h3 className="workflow-card__title">{workflow.name ?? 'Untitled workflow'}</h3>
            {workflow.isShared && <p className="workflow-card__sub">Shared with every member</p>}
          </div>
          <span className={`workflow-card__status ${TONE_TO_CLASS[meta.statusTone]}`}>
            {meta.statusLabel}
          </span>
        </header>

        <PipelineThumb steps={steps} />

        <p className="workflow-card__description">{meta.description}</p>

        <footer className="workflow-card__foot">
          <span className="workflow-card__meta">{etaLabel(meta.etaSeconds)}</span>
          <span className="workflow-card__meta-sep" aria-hidden="true" />
          <span className="workflow-card__meta">{stepCount} steps</span>
          <span className="workflow-card__cta" aria-hidden="true">
            Launch
            <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" role="presentation">
              <path d="M5 12h14M13 6l6 6-6 6" />
            </svg>
          </span>
        </footer>
      </Link>
    </article>
  );
}
