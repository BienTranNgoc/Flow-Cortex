import type { MediaJobResponse } from '@flow-cortex/api-client';

/** `text` is shown as received; `labelKey` is an i18n key the page translates. */
export type JobDetail =
  | {
      text: string;
      /** The engine's own words under an error code, for operators. */
      message?: string;
      marker: false;
    }
  | {
      labelKey: string;
      /** True when the reaper is about to close this step. */
      marker: boolean;
    };

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
      ? { labelKey: 'mediaJobs.detail.queueTimeoutPending', marker: true }
      : { labelKey: 'mediaJobs.detail.waiting', marker: false };
  }
  const leasePassed = passed(job.leaseUntil, now);
  const runPassed = passed(job.runDeadline, now);
  if (leasePassed && runPassed) {
    const runFirst = Date.parse(job.runDeadline as string) < Date.parse(job.leaseUntil as string);
    return {
      labelKey: runFirst ? 'mediaJobs.detail.runTimeoutPending' : 'mediaJobs.detail.leaseExpired',
      marker: true,
    };
  }
  if (leasePassed) {
    return { labelKey: 'mediaJobs.detail.leaseExpired', marker: true };
  }
  if (runPassed) {
    return { labelKey: 'mediaJobs.detail.runTimeoutPending', marker: true };
  }
  return { labelKey: 'mediaJobs.detail.running', marker: false };
}
