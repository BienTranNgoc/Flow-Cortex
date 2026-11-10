import type { AdminSupportRequestSummaryStatus } from '@flow-cortex/api-client';
import { useTranslation } from 'react-i18next';

const TONE: Record<AdminSupportRequestSummaryStatus, string> = {
  new: 'warning',
  in_progress: 'warning',
  waiting_member: 'info',
  resolved: 'success',
  closed: 'muted',
};

/** `new` and `in_progress` wait on an admin, so they share the warning tone of the open count. */
export function StatusBadge({ status }: Readonly<{ status: AdminSupportRequestSummaryStatus }>) {
  const { t } = useTranslation();
  return (
    <span className={`status-badge cortex-admin-pill cortex-admin-pill--${TONE[status]}`}>
      {t(`support.requests.status.${status}`)}
    </span>
  );
}

export function AssigneeLabel({
  assignee,
}: Readonly<{ assignee?: { email?: string; deleted: boolean } | undefined }>) {
  const { t } = useTranslation();
  if (!assignee) {
    return <span className="request-muted-text">{t('support.requests.unassigned')}</span>;
  }
  if (assignee.deleted || !assignee.email) {
    return (
      <span className="status-badge cortex-admin-pill cortex-admin-pill--error">
        {t('support.requests.assigneeDeleted')}
      </span>
    );
  }
  return <>{assignee.email}</>;
}

export function RequesterLabel({
  requester,
}: Readonly<{ requester: { email?: string; deleted: boolean } }>) {
  const { t } = useTranslation();
  if (requester.deleted || !requester.email) {
    return (
      <span className="status-badge cortex-admin-pill cortex-admin-pill--error">
        {t('support.requests.requesterDeleted')}
      </span>
    );
  }
  return <>{requester.email}</>;
}
