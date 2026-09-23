import { Link, useParams } from 'react-router-dom';
import './WorkflowPages.css';
import { useRunQuery } from './useWorkflows';

const STEP_LABELS: Record<string, string> = {
  pending: 'Pending',
  running: 'Running',
  completed: 'Completed',
  error: 'Error',
};

/**
 * `/workflows/:workflowId/runs/:runId` — polls the run until it reaches a
 * terminal status: each step advances pending -> running -> completed|error.
 * A failing step shows its own message and no step after it ever leaves
 * `pending`.
 */
export function RunProgressPage() {
  const { workflowId, runId } = useParams<{ workflowId: string; runId: string }>();
  const runQuery = useRunQuery(runId);

  if (!runId) {
    return <p role="alert">No run selected.</p>;
  }
  if (runQuery.isLoading) {
    return <output>Loading run…</output>;
  }
  if (runQuery.isError || !runQuery.data) {
    return <p role="alert">Could not load this run.</p>;
  }

  const run = runQuery.data;

  return (
    <main>
      <h1>Run {run.status === 'running' ? 'in progress' : run.status}</h1>
      {run.status === 'error' && run.errorMessage && (
        <p role="alert" className="run-error">
          {run.errorMessage}
        </p>
      )}
      <ol className="run-steps">
        {(run.steps ?? []).map((step) => (
          <li key={step.id} className={`run-step run-step-${step.status ?? 'pending'}`}>
            <span className="run-step-order">Step {step.stepOrder}</span>
            <span className="run-step-status">{STEP_LABELS[step.status ?? 'pending'] ?? step.status}</span>
            {step.status === 'completed' && step.output && <p className="run-step-output">{step.output}</p>}
            {step.status === 'error' && step.errorMessage && (
              <p role="alert" className="run-step-error">
                {step.errorMessage}
              </p>
            )}
          </li>
        ))}
      </ol>
      {workflowId && <Link to={`/workflows/${workflowId}`}>Back to workflow</Link>}
    </main>
  );
}
