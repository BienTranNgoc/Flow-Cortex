import type { RunStepResponse } from '@flow-cortex/api-client';
import type { RefObject } from 'react';
import { ApprovalPanel } from './ApprovalPanel';
import { StepDownloads } from './StepDownloads';
import { stepKey, stepNameId } from './stepDisplay';
import { stepErrorSentence } from './stepErrorMessages';
import { StepOutput } from './StepOutput';
import { StoredOutputDownload } from './StoredOutputDownload';

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
      <progress
        max={100}
        value={step.progressPercent}
        aria-label={`Step ${step.stepOrder} progress`}
      />
      <span>{step.progressPercent} %</span>
    </p>
  );
}

interface StepDetailProps {
  id: string;
  panelRef: RefObject<HTMLElement>;
  runId: string;
  step: RunStepResponse;
  /** The run is paused for the member's approval, on this step or another. */
  awaitingApproval: boolean;
  /** The run is paused on this step: the panel shows the approval form. */
  needsYou: boolean;
  fileName: string | undefined;
}

/** The details of the selected step: progress, output, downloads, or the failure and its code. */
export function StepDetail({
  id,
  panelRef,
  runId,
  step,
  awaitingApproval,
  needsYou,
  fileName,
}: Readonly<StepDetailProps>) {
  const status = step.status ?? 'pending';
  const outputs = step.outputs ?? [];
  const details = outputs.find((output) => output.mime === 'application/json');
  const artifacts = outputs.filter((output) => output.mime !== 'application/json');
  const stepOrder = step.stepOrder;

  return (
    <section
      id={id}
      ref={panelRef}
      className="step-detail"
      aria-labelledby={stepNameId(stepKey(step))}
    >
      {awaitingApproval && (
        // Stays mounted while another step is shown, so an unsaved edit is still there on return.
        <div className="step-detail-body" hidden={!needsYou}>
          <ApprovalPanel runId={runId} />
        </div>
      )}
      <div key={stepKey(step)} className="step-detail-body">
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
        {status === 'completed' &&
          stepOrder !== undefined &&
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
      </div>
    </section>
  );
}
