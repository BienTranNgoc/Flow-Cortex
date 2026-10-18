import { useQuery } from '@tanstack/react-query';
import { Check, X } from 'lucide-react';
import { EmptyState } from '../../app/EmptyState';
import { PageHeading } from '../../app/PageHeading';
import { StatusPill } from '../../app/StatusPill';
import { Timestamp } from '../../app/AdminList';
import { usePageTitle } from '../../app/usePageTitle';
import { fetchAdminApprovals, type AdminApproval } from '../../app/mockAdminApi';

export function ApprovalQueuePage() {
  usePageTitle('Approval Queue');
  const query = useQuery({ queryKey: ['admin', 'approvals'], queryFn: fetchAdminApprovals });
  const items = query.data ?? [];

  return (
    <div>
      <PageHeading
        eyebrow="Workflow Ops"
        title="Approval Queue"
        description="Human-in-the-loop checkpoints waiting on a decision. Approve or reject on behalf of a user when systems are blocked."
      />

      {query.isLoading && <output className="cortex-admin-loading">Loading approvals…</output>}
      {query.isError && (
        <div className="cortex-admin-error">
          <p role="alert">Could not load approvals.</p>
          <button type="button" className="cortex-admin-btn" onClick={() => query.refetch()}>
            Retry
          </button>
        </div>
      )}

      {items.length === 0 && !query.isLoading && (
        <EmptyState
          title="No approvals waiting."
          description="The queue is clear — every checkpoint has been actioned."
        />
      )}

      {items.length > 0 && (
        <div className="cortex-admin-stack-list">
          {items.map((ap) => (
            <ApprovalCard key={ap.id} approval={ap} />
          ))}
        </div>
      )}
    </div>
  );
}

function ApprovalCard({ approval }: Readonly<{ approval: AdminApproval }>) {
  return (
    <article className="cortex-admin-panel">
      <header className="cortex-admin-section-heading">
        <div>
          <h2>
            <code className="cortex-admin-table-job-id">{approval.runId}</code> ·{' '}
            {approval.workflowName}
          </h2>
          <p>
            Step: <strong>{approval.stepName}</strong> · Owner {approval.ownerEmail}
          </p>
        </div>
        <StatusPill tone="warning" label={`Waiting ${approval.waitingForMinutes} min`} dot />
      </header>
      {approval.scriptPreview && (
        <pre className="cortex-admin-script-preview">{approval.scriptPreview}</pre>
      )}
      <footer className="cortex-admin-section-heading" style={{ marginTop: '1rem' }}>
        <p>
          <Timestamp value={approval.createdAt} />
        </p>
        <div className="cortex-admin-stack">
          <button type="button" className="cortex-admin-btn cortex-admin-btn--primary">
            <Check size={16} aria-hidden="true" />
            <span>Override approve</span>
          </button>
          <button type="button" className="cortex-admin-btn cortex-admin-btn--danger">
            <X size={16} aria-hidden="true" />
            <span>Reject</span>
          </button>
        </div>
      </footer>
    </article>
  );
}
