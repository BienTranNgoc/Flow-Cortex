import type { StepView } from './workflowMeta';
import './PipelineThumb.css';

interface PipelineThumbProps {
  readonly steps: readonly StepView[];
}

/**
 * Mini timeline of a workflow's steps joined by arrows. The card version of {@link Pipeline}:
 * no interaction, no status; just enough to glance at the flow.
 */
export function PipelineThumb({ steps }: Readonly<PipelineThumbProps>) {
  if (steps.length === 0) {
    return <p className="pipeline-thumb__empty">No steps configured.</p>;
  }
  return (
    <ol className="pipeline-thumb" aria-label="Workflow steps">
      {steps.map((step, index) => {
        const next = steps[index + 1];
        const isCheckpoint = step.kind === 'checkpoint';
        return (
          <li key={step.id} className="pipeline-thumb__row">
            <span
              className={`pipeline-thumb__chip pipeline-thumb__chip--${step.kind}`}
              data-kind={step.kind}
              title={step.label}
            >
              {isCheckpoint && (
                <svg
                  className="pipeline-thumb__icon"
                  viewBox="0 0 24 24"
                  width="12"
                  height="12"
                  fill="currentColor"
                  aria-hidden="true"
                >
                  <path d="M6 2h12v6l-4 4 4 4v6H6v-6l4-4-4-4V2z" />
                </svg>
              )}
              <span className="pipeline-thumb__label">{step.label}</span>
            </span>
            {next && (
              <span className="pipeline-thumb__connector" aria-hidden="true">
                <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" role="presentation">
                  <path d="M5 12h14M13 6l6 6-6 6" />
                </svg>
              </span>
            )}
          </li>
        );
      })}
    </ol>
  );
}
