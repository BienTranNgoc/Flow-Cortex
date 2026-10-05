import { useState } from 'react';
import { useApproveTaskMutation, useRejectTaskMutation, useReviewTasksQuery } from './useWorkflows';

interface ReviewFormProps {
  runId: string;
  taskId: string;
  originalPayload: string | undefined;
}

function ReviewForm({ runId, taskId, originalPayload }: Readonly<ReviewFormProps>) {
  const original = originalPayload ?? '';
  const [draft, setDraft] = useState(original);
  const approve = useApproveTaskMutation(runId, taskId);
  const reject = useRejectTaskMutation(runId, taskId);
  const busy = approve.isPending || reject.isPending;

  // Busy rather than disabled, so focus stays on the button while the decision is sent.
  const onApprove = () => {
    if (busy) return;
    reject.reset();
    approve.mutate(draft === original ? undefined : draft);
  };
  const onReject = () => {
    if (busy) return;
    approve.reset();
    reject.mutate();
  };

  return (
    <section className="approval-panel" aria-label="Review before the run continues">
      <div className="field">
        <label htmlFor="approval-payload">Content to approve</label>
        <textarea
          id="approval-payload"
          rows={6}
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
        />
      </div>
      <div className="approval-actions">
        <button
          type="button"
          onClick={onApprove}
          aria-disabled={busy}
          aria-busy={approve.isPending}
        >
          {approve.isPending ? 'Approving…' : 'Approve'}
        </button>
        <button type="button" onClick={onReject} aria-disabled={busy} aria-busy={reject.isPending}>
          {reject.isPending ? 'Rejecting…' : 'Reject'}
        </button>
      </div>
      {approve.isError && (
        <p role="alert" className="approval-error">
          Could not approve this step
        </p>
      )}
      {reject.isError && (
        <p role="alert" className="approval-error">
          Could not reject this step
        </p>
      )}
    </section>
  );
}

/** The pending review of a paused run: the content can be edited before it is approved. */
export function ApprovalPanel({ runId }: Readonly<{ runId: string }>) {
  const tasksQuery = useReviewTasksQuery(runId);

  if (tasksQuery.isLoading) {
    return <output>Loading the step to review…</output>;
  }
  if (tasksQuery.isError) {
    return <p role="alert">Could not load the step to review.</p>;
  }
  const task = tasksQuery.data?.find((candidate) => candidate.status === 'pending');
  if (!task?.id) {
    return null;
  }
  return (
    <ReviewForm
      key={task.id}
      runId={runId}
      taskId={task.id}
      originalPayload={task.originalPayload}
    />
  );
}
