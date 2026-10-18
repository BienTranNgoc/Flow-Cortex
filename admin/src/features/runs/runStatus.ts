import { ListAdminRunsStatus } from '@flow-cortex/api-client';
import type { TFunction } from 'i18next';

const RUN_STATUSES: readonly string[] = Object.values(ListAdminRunsStatus);

/** The run status's label in the active language; a code this console does not know is shown as sent. */
export function runStatusLabel(t: TFunction, status: string): string {
  return RUN_STATUSES.includes(status) ? t(`runs.status.${status}`) : status;
}
