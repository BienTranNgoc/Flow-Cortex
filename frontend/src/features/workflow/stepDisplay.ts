import type { RunStepResponse } from '@flow-cortex/api-client';
import type { TFunction } from 'i18next';
import { formatDateTime, formatNumber } from '../../lib/format';
import type { Locale } from '../../lib/i18n';

export type StepState = 'pending' | 'waiting' | 'running' | 'completed' | 'error' | 'needs-you';
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

const STATE_WORD_KEYS: Record<StepState, string> = {
  pending: 'workflow.step.state.pending',
  waiting: 'workflow.step.state.waiting',
  running: 'workflow.step.state.running',
  completed: 'workflow.step.state.completed',
  error: 'workflow.step.state.error',
  'needs-you': 'workflow.step.state.needsYou',
};

export function stateWord(state: StepState, t: TFunction): string {
  return t(STATE_WORD_KEYS[state]);
}

const CHECKPOINT = 'APPROVAL_CHECKPOINT';

const LABEL_KEYS: Record<string, string> = {
  probe: 'workflow.step.label.probe',
  extract_audio: 'workflow.step.label.extract_audio',
  [CHECKPOINT]: 'workflow.step.label.review',
};

/** The id of the element holding a node's name, so the detail panel can be labelled by it. */
export const stepNameId = (stepId: string) => `pipeline-name-${stepId}`;

export function stepLabel(step: RunStepResponse, t: TFunction): string {
  const key = LABEL_KEYS[step.type ?? ''];
  return key ? t(key) : t('workflow.step.fallback', { order: step.stepOrder });
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

/** Formats an ISO date string as a time of day (HH:MM:SS) in the given language's conventions. */
export function formatTime(iso: string | undefined, locale: Locale): string | null {
  if (!iso) {
    return null;
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return null;
  }
  return formatDateTime(date, locale, { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

/**
 * Rounded by `toFixed` (Intl rounds 0.35 up, `toFixed` down) and grouped in vi only, so English
 * keeps the output it always had ("11.3s", "3600s").
 */
function plain(value: number, locale: Locale, fractionDigits = 0): string {
  return formatNumber(Number(value.toFixed(fractionDigits)), locale, {
    useGrouping: locale === 'vi',
    minimumFractionDigits: fractionDigits,
    maximumFractionDigits: fractionDigits,
  });
}

function shortSeconds(s: number, t: TFunction, locale: Locale): string {
  return t('common.units.secondsShort', {
    value: Number.isInteger(s) ? plain(s, locale) : plain(s, locale, 1),
  });
}

/**
 * Execution duration of a step in the given language (e.g. "11.3s", "1m 20s"), from its timestamps,
 * else from the duration its output report carries.
 */
export function stepDuration(step: RunStepResponse, t: TFunction, locale: Locale): string | null {
  if (step.startedAt && step.finishedAt) {
    const start = new Date(step.startedAt).getTime();
    const end = new Date(step.finishedAt).getTime();
    if (!Number.isNaN(start) && !Number.isNaN(end) && end >= start) {
      const ms = end - start;
      const s = ms / 1000;
      if (s < 0.1) {
        return t('common.units.milliseconds', { value: plain(ms, locale) });
      }
      if (s < 60) {
        return shortSeconds(s, t, locale);
      }
      const mins = Math.floor(s / 60);
      const remSecs = Math.round(s % 60);
      return remSecs > 0
        ? t('common.units.minutesSeconds', {
            minutes: plain(mins, locale),
            seconds: plain(remSecs, locale),
          })
        : t('common.units.minutes', { value: plain(mins, locale) });
    }
  }

  if (step.output) {
    try {
      const report: unknown = JSON.parse(step.output);
      if (report && typeof report === 'object') {
        const r = report as Record<string, unknown>;
        if (typeof r.durationMs === 'number') {
          return shortSeconds(r.durationMs / 1000, t, locale);
        }
        if (typeof r.durationSeconds === 'number') {
          return shortSeconds(r.durationSeconds, t, locale);
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
