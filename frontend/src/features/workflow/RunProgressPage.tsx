import type { RunStepResponse } from '@flow-cortex/api-client';
import { Link, useParams } from 'react-router-dom';
import './WorkflowPages.css';
import { StepDownloads } from './StepDownloads';
import { NO_MESSAGE, stepErrorSentence } from './stepErrorMessages';
import { StepOutput } from './StepOutput';
import { useRunQuery } from './useWorkflows';
import { StoredOutputDownload } from './StoredOutputDownload';
const STEP_LABELS: Record<string, string> = {
  pending: 'Pending',
  running: 'Running',
  completed: 'Completed',
  error: 'Error',
};

/**
 * Runs written before uploads existed may hold a JSON reference as their input; that is never a
 * file name, so it is not shown.
 */
function looksLikeJson(value: string): boolean {
  try {
    const parsed: unknown = JSON.parse(value);
    return typeof parsed === 'object' && parsed !== null;
  } catch {
    return false;
  }
}

function StepProgress({ step }: Readonly<{ step: RunStepResponse }>) {
  if (step.status !== 'running') {
    return null;
  }
  if (!step.pickedUpAt) {
    return <p className="run-step-waiting">Waiting to start…</p>;
  }
  if (step.progressPercent === undefined || step.progressPercent === null) {
    return null;
  }
  return (
    <p className="run-step-progress">
      <progress max={100} value={step.progressPercent} aria-label={`Step ${step.stepOrder} progress`} />
      <span>{step.progressPercent} %</span>
    </p>
  );
}

/**
 * `/workflows/:workflowId/runs/:runId` — polls the run until it reaches a terminal status: each
 * step advances pending -> running -> completed|error. Everything shown comes from the saved run,
 * so a run read long after it finished renders the same as it did then.
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
  const startsWithUpload = run.steps?.[0]?.mode === 'remote';
  const fileName =
    startsWithUpload && run.input && !looksLikeJson(run.input) ? run.input : undefined;
  const failedWithoutStep =
    run.status === 'error' && !(run.steps ?? []).some((step) => step.status === 'error');

  return (
    <main>
      <h1>Run {run.status === 'running' ? 'in progress' : run.status}</h1>
      {failedWithoutStep && (
        <p role="alert" className="run-step-error">
          {NO_MESSAGE}
        </p>
      )}
      {fileName && <p className="run-file">File: {fileName}</p>}
      <ol className="run-steps">
        {(run.steps ?? []).map((step) => {
          const status = step.status ?? 'pending';
          const waiting = status === 'running' && !step.pickedUpAt;
          const deliveries = step.deliveryCount ?? 0;
          // const details = (step.outputs ?? []).find((output) => output.mime === 'application/json');
          const outputs = step.outputs ?? [];
          const details = outputs.find((output) => output.mime === 'application/json');
          const artifacts = outputs.filter((output) => output.mime !== 'application/json');
          const stepOrder = step.stepOrder;
          return (
            <li key={step.id} className={`run-step run-step-${status}`}>
              <div className="run-step-head">
                <span className="run-step-order">Step {step.stepOrder}</span>
                <span className="run-step-status">
                  {waiting ? 'Waiting' : (STEP_LABELS[status] ?? status)}
                </span>
                {deliveries >= 1 && <span className="run-step-retried">Retried {deliveries}×</span>}
              </div>
              <StepProgress step={step} />
              {status === 'completed' && step.output && <StepOutput output={step.output} />}
              {status === 'completed' && details && stepOrder !== undefined && (
              <StepDownloads
                runId={runId}
                stepOrder={stepOrder}
                output={step.output}
                details={details}
                fileName={fileName}
              />
            )}

            {status === 'completed' && stepOrder !== undefined &&
              artifacts.map((artifact) => (
                <StoredOutputDownload
                  key={artifact.name}
                  runId={runId}
                  stepOrder={stepOrder}
                  output={artifact}
                />
              ))}
              {status === 'error' && (
                <div className="run-step-failure">
                  <p role="alert" className="run-step-error">
                    {stepErrorSentence(step.errorCode, step.errorMessage, step.mode === 'remote')}
                  </p>
                  {step.errorCode && <small className="run-step-code">{step.errorCode}</small>}
                </div>
              )}
            </li>
          );
        })}
      </ol>
      {workflowId && (
        <Link className="run-back-link action-link" to={`/workflows/${workflowId}`}>
          Back to workflow
        </Link>
      )}
    </main>
  );
}
