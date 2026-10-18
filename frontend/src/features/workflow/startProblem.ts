import { ApiError, type ProblemDetail } from '@flow-cortex/api-client';
import { formatCredits } from '../credits/formatCredits';

export interface IncompleteStep {
  order: number;
  reason: string;
}

export interface IncompleteWorkflow {
  kind: 'incomplete';
  summary: string;
  /** The lowest failing step order, or `null` when the refusal names no step. */
  firstOrder: number | null;
  steps: IncompleteStep[];
}

/** A start refused for credits; only a positive balance may resend it with the acknowledgement. */
export interface NotEnoughCredits {
  kind: 'credits';
  message: string;
  canRunAnyway: boolean;
}

/** What a start form shows for a failed upload request or run start. */
export type StartProblem =
  | { kind: 'field'; message: string }
  | IncompleteWorkflow
  | NotEnoughCredits
  | { kind: 'alert'; message: string };

type Reader = (problem: ProblemDetail, status: number) => StartProblem | null;

/** Fields the server names when it refuses the picked file(s), read in this order. */
const FILE_FIELDS = ['uploadIds', 'uploadId', 'size', 'fileName'] as const;
const STEP_KEY = /^step(\d+)$/;

function problemCode(problem: ProblemDetail): unknown {
  return 'code' in problem ? problem.code : undefined;
}

const readIncomplete: Reader = (problem, status) => {
  if (status !== 422 || problemCode(problem) !== 'WORKFLOW_INCOMPLETE') {
    return null;
  }
  const steps = Object.entries(problem.errors ?? {})
    .flatMap(([key, reason]) => {
      const order = STEP_KEY.exec(key)?.[1];
      return order ? [{ order: Number(order), reason }] : [];
    })
    .sort((a, b) => a.order - b.order);
  const firstOrder = steps[0]?.order ?? null;
  const summary =
    problem.detail ??
    (firstOrder === null
      ? "This workflow can't run yet."
      : `This workflow can't run yet: step ${firstOrder} is not fully configured.`);
  return { kind: 'incomplete', summary, firstOrder, steps };
};

const readFileField: Reader = (problem) => {
  const message = FILE_FIELDS.map((field) => problem.errors?.[field]).find(Boolean);
  return message ? { kind: 'field', message } : null;
};

function amount(problem: ProblemDetail, name: 'balance' | 'required' | 'shortfall') {
  const value: unknown = name in problem ? (problem as Record<string, unknown>)[name] : undefined;
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

const readNotEnoughCredits: Reader = (problem, status) => {
  if (status !== 402 || problemCode(problem) !== 'INSUFFICIENT_CREDITS') {
    return null;
  }
  const balance = amount(problem, 'balance');
  const required = amount(problem, 'required');
  const shortfall = amount(problem, 'shortfall');
  if (balance === null || required === null || shortfall === null) {
    return null;
  }
  return {
    kind: 'credits',
    message:
      `Not enough credits: you have ${formatCredits(balance)}, this run needs ` +
      `${formatCredits(required)} (short ${formatCredits(shortfall)}).`,
    canRunAnyway: balance > 0,
  };
};

/** Checked in order; the first reader that recognises the refusal decides what is shown. */
const READERS: readonly Reader[] = [readIncomplete, readNotEnoughCredits, readFileField];

/**
 * Maps an error from `/uploads` or a run start to what the form shows. Anything no reader
 * recognises becomes an alert with the server's detail, else `fallback`.
 */
export function startProblem(error: unknown, fallback = 'Could not start the run.'): StartProblem {
  const problem = error instanceof ApiError ? error.problem : undefined;
  if (error instanceof ApiError && problem) {
    for (const read of READERS) {
      const shown = read(problem, error.status);
      if (shown) {
        return shown;
      }
    }
  }
  return { kind: 'alert', message: problem?.detail ?? fallback };
}
