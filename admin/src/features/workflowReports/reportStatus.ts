import { ListWorkflowReportsStatus } from '@flow-cortex/api-client';
import type { TFunction } from 'i18next';
import type { StatusTone } from '../../app/StatusPill';

const REPORT_STATUSES: readonly string[] = Object.values(ListWorkflowReportsStatus);
const MODERATION_STATUSES: readonly string[] = ['visible', 'hidden', 'removed'];

/** The pill colour of a report status; a code this console does not know reads as informational. */
export function reportStatusTone(status: string): StatusTone {
  switch (status) {
    case ListWorkflowReportsStatus.pending:
      return 'warning';
    case ListWorkflowReportsStatus.resolved:
      return 'success';
    case ListWorkflowReportsStatus.hidden:
    case ListWorkflowReportsStatus.removed:
      return 'error';
    case ListWorkflowReportsStatus.dismissed:
      return 'muted';
    default:
      return 'info';
  }
}

/** The report status's label in the active language; a code this console does not know is shown as sent. */
export function reportStatusLabel(t: TFunction, status: string): string {
  return REPORT_STATUSES.includes(status) ? t(`workflowReports.status.${status}`) : status;
}

export function moderationStatusTone(status: string): StatusTone {
  if (status === 'visible') return 'success';
  return MODERATION_STATUSES.includes(status) ? 'error' : 'info';
}

/** The workflow's moderation status in the active language; a deleted workflow reads as deleted. */
export function moderationStatusLabel(t: TFunction, status: string, deleted: boolean): string {
  if (deleted) return t('workflowReports.moderation.deleted');
  return MODERATION_STATUSES.includes(status) ? t(`workflowReports.moderation.${status}`) : status;
}

/** The reason a member gave; a code this console does not know is shown as sent. */
export function reasonLabel(t: TFunction, reason: string): string {
  return t(`workflowReports.reasons.${reason}`, { defaultValue: reason });
}
