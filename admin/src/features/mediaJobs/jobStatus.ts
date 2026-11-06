import { ListAdminMediaJobsStatus } from '@flow-cortex/api-client';
import type { TFunction } from 'i18next';

const JOB_STATUSES: readonly string[] = Object.values(ListAdminMediaJobsStatus);

/** The media job status's label in the active language; a code this console does not know is shown as sent. */
export function jobStatusLabel(t: TFunction, status: string): string {
  return JOB_STATUSES.includes(status) ? t(`mediaJobs.status.${status}`) : status;
}
