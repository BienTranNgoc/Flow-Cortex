import { useState } from 'react';
import type { ReviewTaskResponse, ScriptRevisionResponse } from '@flow-cortex/api-client';
import {
  useApproveTaskMutation,
  useRejectTaskMutation,
  useReviewTasksQuery,
  useRevisionsQuery,
} from './useWorkflows';
import { isLostRace } from './lostRace';
import { ScriptEditor } from './ScriptEditor';

interface ReviewFormProps {
  runId: string;
  task: ReviewTaskResponse;
  originalPayload: string | undefined;
}

function ReviewForm({ runId, task, originalPayload }: Readonly<ReviewFormProps>) {
  const original = originalPayload ?? '';
  const scripted = Boolean(task.currentRevisionId);
  const [draft, setDraft] = useState(original);
  const [scriptApprovalBlocked, setScriptApprovalBlocked] = useState(scripted);
  const approve = useApproveTaskMutation(runId, task.id ?? '');
  const reject = useRejectTaskMutation(runId, task.id ?? '');
  const busy = approve.isPending || reject.isPending;
  const approveBlocked = busy || scriptApprovalBlocked;

  // Busy rather than disabled, so focus stays on the button while the decision is sent.
  const onApprove = () => {
    if (approveBlocked) return;
    reject.reset();
    approve.mutate(
      scripted
        ? { revisionId: task.currentRevisionId, expectedVersion: task.version }
        : { reviewedPayload: draft === original ? undefined : draft },
    );
  };
  const onReject = () => {
    if (busy) return;
    approve.reset();
    reject.mutate();
  };

  return (
    <section className="approval-panel" aria-label="Review before the run continues">
      {scripted ? (
        <ScriptReview
          task={task}
          runId={runId}
          onApprovalBlockedChange={setScriptApprovalBlocked}
        />
      ) : (
        <div className="field">
          <label htmlFor="approval-payload">Content to approve</label>
          <textarea
            id="approval-payload"
            rows={6}
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
          />
        </div>
      )}
      <div className="approval-actions">
        <button
          type="button"
          onClick={onApprove}
          aria-disabled={approveBlocked}
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
          {approveError(approve.error, scripted)}
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

/**
 * A 409 on a script checkpoint means the revision moved on; the same status on a plain checkpoint
 * means the decision was already taken. Either way the reviewer has to reload — retrying the same
 * click can only lose again.
 */
function approveError(error: unknown, scripted: boolean): string {
  if (!isLostRace(error)) {
    return 'Could not approve this step';
  }
  return scripted
    ? 'This script moved on in another tab. Reload the page to see the latest revision.'
    : 'This step was already decided elsewhere.';
}

/**
 * A script checkpoint loads its revisions once and passes them down: the editor needs the history
 * to show, and the approval needs the task's `currentRevisionId` — the revision the reviewer is
 * looking at, which a 409 then refuses if another tab has since saved past it.
 */
function ScriptReview({
  task,
  runId,
  onApprovalBlockedChange,
}: Readonly<{
  task: ReviewTaskResponse;
  runId: string;
  onApprovalBlockedChange: (blocked: boolean) => void;
}>) {
  const revisions = useRevisionsQuery(task.id);
  if (revisions.isLoading) {
    return <output>Loading the script…</output>;
  }
  if (revisions.isError) {
    return <p role="alert">Could not load the script revisions.</p>;
  }
  const list: ScriptRevisionResponse[] = revisions.data ?? [];
  return (
    <ScriptEditor
      runId={runId}
      task={task}
      revisions={list}
      onApprovalBlockedChange={onApprovalBlockedChange}
    />
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
    <ReviewForm key={task.id} runId={runId} task={task} originalPayload={task.originalPayload} />
  );
}
