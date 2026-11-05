import type { AdminSupportRequestSummaryStatus } from '@flow-cortex/api-client';
import { supportCopy } from '../supportCopy';

const TONE: Record<AdminSupportRequestSummaryStatus, string> = {
  new: 'warning',
  in_progress: 'warning',
  waiting_member: 'info',
  resolved: 'success',
  closed: 'muted',
};

/** Mới and Đang xử lý wait on an admin, so they share the warning tone of the open count. */
export function StatusBadge({ status }: Readonly<{ status: AdminSupportRequestSummaryStatus }>) {
  return (
    <span className={`status-badge cortex-admin-pill cortex-admin-pill--${TONE[status]}`}>
      {supportCopy.requests.status[status]}
    </span>
  );
}

export function RequesterLabel({
  requester,
}: Readonly<{ requester: { email?: string; deleted: boolean } }>) {
  if (requester.deleted || !requester.email) {
    return (
      <span className="status-badge cortex-admin-pill cortex-admin-pill--error">
        {supportCopy.requests.requesterDeleted}
      </span>
    );
  }
  return <>{requester.email}</>;
}
