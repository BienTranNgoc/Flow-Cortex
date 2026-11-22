import { ListAdminRunsStatus } from '@flow-cortex/api-client';
import type { TFunction } from 'i18next';
import type { StatusTone } from '../../app/StatusPill';

const RUN_STATUSES: readonly string[] = Object.values(ListAdminRunsStatus);

/** The pill colour of a run status; a code this console does not know reads as in progress. */
export function runStatusTone(status: string): StatusTone {
  switch (status) {
    case ListAdminRunsStatus.completed:
      return 'success';
    case ListAdminRunsStatus.error:
      return 'error';
    case ListAdminRunsStatus.waiting_approval:
    case ListAdminRunsStatus.cancelling:
      return 'warning';
    case ListAdminRunsStatus.cancelled:
      return 'muted';
    default:
      return 'info';
  }
}

/** The run status's label in the active language; a code this console does not know is shown as sent. */
export function runStatusLabel(t: TFunction, status: string): string {
  return RUN_STATUSES.includes(status) ? t(`runs.status.${status}`) : status;
}
