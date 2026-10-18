import type { RunStepResponse } from '@flow-cortex/api-client';
import './Pipeline.css';
import { edgeState, STATE_WORDS, stepDuration, stepNameId, stepState, type StepState } from './stepDisplay';
import { outputFields, parseReport } from './stepOutputFields';
import { StatusGlyph, TypeGlyph } from './stepGlyphs';

export interface PipelineStep {
  id: string;
  name: string;
  /** The saved run step; a step without one is drawn as a plain list item. */
  run?: RunStepResponse;
}

interface PipelineProps {
  steps: readonly PipelineStep[];
  runStatus?: string | undefined;
  selectedId?: string | undefined;
  /** Present on a run: nodes become buttons that report the step to open. */
  onSelect?: (id: string) => void;
  panelId?: string;
}

const CURRENT_STATES: ReadonlySet<StepState> = new Set(['waiting', 'running', 'needs-you']);

/** "Retried N×" for a redelivered job, else the first labelled field of a completed step. */
function metaLine(step: RunStepResponse): string {
  const deliveries = step.deliveryCount ?? 0;
  if (deliveries >= 1) {
    return `Retried ${deliveries}×`;
  }
  const report = step.status === 'completed' && step.output ? parseReport(step.output) : null;
  const first = report ? outputFields(report)[0] : undefined;
  return first ? `${first.label}: ${first.value}` : '';
}

interface NodeProps {
  step: PipelineStep;
  order: number;
  state: StepState | undefined;
  selected: boolean;
  panelId: string | undefined;
  onSelect: ((id: string) => void) | undefined;
}

function Node({ step, order, state, selected, panelId, onSelect }: Readonly<NodeProps>) {
  const duration = step.run ? stepDuration(step.run) : null;
  const content = (
    <>
      <span className="pipeline-order">{order}</span>
      <span className="pipeline-type">
        <TypeGlyph type={step.run?.type} />
      </span>
      <span className="pipeline-name" id={stepNameId(step.id)}>
        {step.name}
      </span>
      {state && (
        <span className="run-step-status" data-state={state}>
          <StatusGlyph state={state} />
          {STATE_WORDS[state]}
        </span>
      )}
      {duration && (
        <span className="pipeline-duration" title="Time taken">
          <svg
            className="pipeline-clock-icon"
            width="12"
            height="12"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <circle cx="12" cy="12" r="10" />
            <polyline points="12 6 12 12 16 14" />
          </svg>
          {duration}
        </span>
      )}
      {step.run && <span className="pipeline-meta">{metaLine(step.run)}</span>}
    </>
  );

  if (!onSelect || !state) {
    return (
      <div className="pipeline-node" data-state={state}>
        {content}
      </div>
    );
  }
  return (
    <button
      type="button"
      className="pipeline-node"
      data-state={state}
      aria-pressed={selected}
      aria-current={CURRENT_STATES.has(state) ? 'step' : undefined}
      aria-controls={panelId}
      onClick={() => onSelect(step.id)}
    >
      {content}
    </button>
  );
}

function Connector({
  from,
  to,
}: Readonly<{ from: RunStepResponse | undefined; to: RunStepResponse | undefined }>) {
  const state = from && to ? edgeState(from, to) : 'pending';
  return (
    <span className="pipeline-connector" data-state={state} aria-hidden="true">
      {state === 'active' && <span className="pipeline-flow" />}
    </span>
  );
}

/**
 * The steps of a workflow or run as nodes joined by connectors. With `onSelect` the nodes are
 * buttons and carry the run's status; without it they are plain list items.
 */
export function Pipeline({
  steps,
  runStatus,
  selectedId,
  onSelect,
  panelId,
}: Readonly<PipelineProps>) {
  const runSteps = steps.flatMap((step) => (step.run ? [step.run] : []));
  const layout = steps.length > 4 ? 'stack' : 'row';

  return (
    <section className="pipeline" aria-label="Steps" data-layout={layout}>
      <ol className="pipeline-list">
        {steps.map((step, index) => {
          const next = steps[index + 1];
          return (
            <li key={step.id} className="pipeline-item">
              <Node
                step={step}
                order={index + 1}
                state={step.run && stepState(step.run, runSteps, runStatus)}
                selected={step.id === selectedId}
                panelId={panelId}
                onSelect={onSelect}
              />
              {next && <Connector from={step.run} to={next.run} />}
            </li>
          );
        })}
      </ol>
      {onSelect && steps.length > 1 && (
        <p className="pipeline-hint">Select a step to see its result.</p>
      )}
    </section>
  );
}
