import type { TFunction } from 'i18next';

// waiting_approval is a run status the list can show but not filter on.
const RUN_STATUSES: readonly string[] = ['running', 'waiting_approval', 'completed', 'error'];

/** The run status's label in the active language; a code this console does not know is shown as sent. */
export function runStatusLabel(t: TFunction, status: string): string {
  return RUN_STATUSES.includes(status) ? t(`runs.status.${status}`) : status;
}
