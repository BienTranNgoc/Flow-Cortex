import type { MediaJobResponse } from '@flow-cortex/api-client';

export interface JobDetail {
  text: string;
  /** The engine's own words under an error code, for operators. */
  message?: string;
  /** True when the reaper is about to close this step. */
  marker: boolean;
}

// The API sends null for a deadline an older row never had.
const passed = (deadline: string | null | undefined, now: number) =>
  deadline != null && Date.parse(deadline) < now;

/**
 * What an operator needs to know about a step beyond its status. Mirrors the backend reaper's rule
 * (`LeaseReaper.Reason.of`): an unpicked step is bound by its queue deadline; a picked-up one by its
 * lease and its run deadline, and when both have passed the earlier one wins.
 */
export function jobDetail(job: MediaJobResponse, now: number): JobDetail {
  if (job.status === 'error') {
    if (job.errorCode && job.errorMessage) {
      return { text: job.errorCode, message: job.errorMessage, marker: false };
    }
    return { text: job.errorCode ?? job.errorMessage ?? '—', marker: false };
  }
  if (job.status !== 'running') {
    return { text: '—', marker: false };
  }
  if (!job.pickedUpAt) {
    return passed(job.queueDeadline, now)
      ? { text: 'queue timeout pending', marker: true }
      : { text: 'waiting', marker: false };
  }
  const leasePassed = passed(job.leaseUntil, now);
  const runPassed = passed(job.runDeadline, now);
  if (leasePassed && runPassed) {
    const runFirst = Date.parse(job.runDeadline as string) < Date.parse(job.leaseUntil as string);
    return { text: runFirst ? 'run timeout pending' : 'lease expired', marker: true };
  }
  if (leasePassed) {
    return { text: 'lease expired', marker: true };
  }
  if (runPassed) {
    return { text: 'run timeout pending', marker: true };
  }
  return { text: 'running', marker: false };
}
