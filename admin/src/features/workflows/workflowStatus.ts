import { ListAdminWorkflowsStatus } from '@flow-cortex/api-client';
import type { TFunction } from 'i18next';

const WORKFLOW_STATUSES: readonly string[] = Object.values(ListAdminWorkflowsStatus);

/** The workflow status's label in the active language; a code this console does not know is shown as sent. */
export function workflowStatusLabel(t: TFunction, status: string): string {
  return WORKFLOW_STATUSES.includes(status) ? t(`workflows.status.${status}`) : status;
}
