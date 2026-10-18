import type { RunStepResponse } from '@flow-cortex/api-client';

export type StepState =
  'pending' | 'waiting' | 'running' | 'completed' | 'error' | 'skipped' | 'needs-you';
export type EdgeState = 'done' | 'active' | 'broken' | 'pending';

/** The member's own pick, with the run status and the step waiting for approval at that moment. */
export interface StepChoice {
  stepId: string;
  runStatus: string | undefined;
  needsYouId: string | undefined;
}

/** A step's identity: its id, else a value unique to its position, so keys and ids never collide. */
export function stepKey(step: Pick<RunStepResponse, 'id' | 'stepOrder'>): string {
  return step.id ?? `order-${step.stepOrder}`;
}

export const STATE_WORDS: Record<StepState, string> = {
  pending: 'Pending',
  waiting: 'Waiting',
  running: 'Running',
  completed: 'Completed',
  error: 'Error',
  skipped: 'Skipped',
  'needs-you': 'Needs you',
};

const CHECKPOINT = 'APPROVAL_CHECKPOINT';

const LABELS: Record<string, string> = {
  probe: 'Inspect the file',
  extract_audio: 'Extract source audio',
  [CHECKPOINT]: 'Review',
};

/** The id of the element holding a node's name, so the detail panel can be labelled by it. */
export const stepNameId = (stepId: string) => `pipeline-name-${stepId}`;

export function stepLabel(step: RunStepResponse): string {
  return LABELS[step.type ?? ''] ?? `Step ${step.stepOrder}`;
}

/** The step a waiting run is paused on: its first pending approval checkpoint. */
export function needsYouStep(
  steps: readonly RunStepResponse[],
  runStatus: string | undefined,
): RunStepResponse | undefined {
  if (runStatus !== 'waiting_approval') {
    return undefined;
  }
  return steps.find((step) => step.type === CHECKPOINT && (step.status ?? 'pending') === 'pending');
}

export function stepState(
  step: RunStepResponse,
  steps: readonly RunStepResponse[],
  runStatus: string | undefined,
): StepState {
  const waitingOn = needsYouStep(steps, runStatus);
  if (waitingOn && stepKey(waitingOn) === stepKey(step)) {
    return 'needs-you';
  }
  switch (step.status) {
    case 'running':
      return step.pickedUpAt ? 'running' : 'waiting';
    case 'completed':
    case 'error':
    case 'skipped':
      return step.status;
    default:
      return 'pending';
  }
}

export function edgeState(from: RunStepResponse, to: RunStepResponse): EdgeState {
  if (from.status === 'completed' && to.status === 'completed') {
    return 'done';
  }
  if (to.status === 'running') {
    return 'active';
  }
  return from.status === 'error' ? 'broken' : 'pending';
}

export function defaultStep(
  steps: readonly RunStepResponse[],
  runStatus: string | undefined,
): RunStepResponse | undefined {
  return (
    needsYouStep(steps, runStatus) ??
    steps.find((step) => step.status === 'error') ??
    steps.find((step) => step.status === 'running') ??
    steps.filter((step) => step.status === 'completed').at(-1) ??
    steps[0]
  );
}

/**
 * Whether the pick no longer applies: its step left the run, the run waits on another approval step
 * than at the pick, or the run ended in error after a pick made under another status.
 */
export function choiceTakenBack(
  steps: readonly RunStepResponse[],
  runStatus: string | undefined,
  choice: StepChoice,
): boolean {
  if (!steps.some((step) => stepKey(step) === choice.stepId)) {
    return true;
  }
  if (runStatus === 'waiting_approval') {
    const waitingOn = needsYouStep(steps, runStatus);
    return (waitingOn && stepKey(waitingOn)) !== choice.needsYouId;
  }
  return runStatus === 'error' && choice.runStatus !== runStatus;
}

export function selectedStep(
  steps: readonly RunStepResponse[],
  runStatus: string | undefined,
  choice: StepChoice | undefined,
): RunStepResponse | undefined {
  const picked =
    choice && !choiceTakenBack(steps, runStatus, choice)
      ? steps.find((step) => stepKey(step) === choice.stepId)
      : undefined;
  return picked ?? defaultStep(steps, runStatus);
}

/** Formats an ISO date string to a localized time string (HH:MM:SS). */
export function formatTime(iso?: string): string | null {
  if (!iso) {
    return null;
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return null;
  }
  return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

/**
 * Execution duration of a step, formatted as human-readable string (e.g. "11.3s", "1m 20s").
 * Evaluates step timestamps (startedAt & finishedAt), falling back to output reports with duration.
 */
export function stepDuration(step: RunStepResponse): string | null {
  if (step.startedAt && step.finishedAt) {
    const start = new Date(step.startedAt).getTime();
    const end = new Date(step.finishedAt).getTime();
    if (!Number.isNaN(start) && !Number.isNaN(end) && end >= start) {
      const ms = end - start;
      const s = ms / 1000;
      if (s < 0.1) {
        return `${ms}ms`;
      }
      if (s < 60) {
        return Number.isInteger(s) ? `${s}s` : `${s.toFixed(1)}s`;
      }
      const mins = Math.floor(s / 60);
      const remSecs = Math.round(s % 60);
      return remSecs > 0 ? `${mins}m ${remSecs}s` : `${mins}m`;
    }
  }

  if (step.output) {
    try {
      const report: unknown = JSON.parse(step.output);
      if (report && typeof report === 'object') {
        const r = report as Record<string, unknown>;
        if (typeof r.durationMs === 'number') {
          const s = r.durationMs / 1000;
          return Number.isInteger(s) ? `${s}s` : `${s.toFixed(1)}s`;
        }
        if (typeof r.durationSeconds === 'number') {
          const s = r.durationSeconds;
          return Number.isInteger(s) ? `${s}s` : `${s.toFixed(1)}s`;
        }
        if (typeof r.duration === 'string') {
          return r.duration;
        }
      }
    } catch {
      // not a json report
    }
  }

  return null;
}
