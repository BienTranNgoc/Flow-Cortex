import type { ProblemDetail } from '@flow-cortex/api-client';

export type StepUpFailure =
  | { kind: 'invalidPassword' }
  | { kind: 'passwordRequired' }
  | { kind: 'rateLimited'; seconds: number }
  | { kind: 'unknown' };

const DEFAULT_WAIT_SECONDS = 60;

function waitSeconds(retryAfterHeader: string | null): number {
  const header = retryAfterHeader?.trim() ?? '';
  const seconds = /^\d+$/.test(header) ? Number(header) : 0;
  return seconds > 0 ? seconds : DEFAULT_WAIT_SECONDS;
}

/** Names what a refused step-up request means for the dialog, from its status, problem code and `Retry-After`. */
export function stepUpFailure(
  status: number,
  problem: ProblemDetail | undefined,
  retryAfterHeader: string | null,
): StepUpFailure {
  if (status === 422 && problem?.code === 'STEP_UP_INVALID_PASSWORD') {
    return { kind: 'invalidPassword' };
  }
  if (status === 409 && problem?.code === 'STEP_UP_PASSWORD_REQUIRED') {
    return { kind: 'passwordRequired' };
  }
  if (status === 429) {
    return { kind: 'rateLimited', seconds: waitSeconds(retryAfterHeader) };
  }
  return { kind: 'unknown' };
}
