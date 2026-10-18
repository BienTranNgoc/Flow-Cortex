import { useState } from 'react';
import { useTranslation } from 'react-i18next';
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
  const { t } = useTranslation();
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
    <section className="approval-panel" aria-label={t('workflow.approval.region')}>
      {scripted ? (
        <ScriptReview
          task={task}
          runId={runId}
          onApprovalBlockedChange={setScriptApprovalBlocked}
        />
      ) : (
        <div className="field">
          <label htmlFor="approval-payload">{t('workflow.approval.contentLabel')}</label>
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
          {approve.isPending ? t('workflow.approval.approving') : t('workflow.approval.approve')}
        </button>
        <button type="button" onClick={onReject} aria-disabled={busy} aria-busy={reject.isPending}>
          {reject.isPending ? t('workflow.approval.rejecting') : t('workflow.approval.reject')}
        </button>
      </div>
      {approve.isError && (
        <p role="alert" className="approval-error">
          {t(approveErrorKey(approve.error, scripted))}
        </p>
      )}
      {reject.isError && (
        <p role="alert" className="approval-error">
          {t('workflow.approval.rejectFailed')}
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
function approveErrorKey(error: unknown, scripted: boolean): string {
  if (!isLostRace(error)) {
    return 'workflow.approval.approveFailed';
  }
  return scripted ? 'workflow.approval.scriptMovedOn' : 'workflow.approval.alreadyDecided';
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
  const { t } = useTranslation();
  const revisions = useRevisionsQuery(task.id);
  if (revisions.isLoading) {
    return <output>{t('workflow.approval.loadingScript')}</output>;
  }
  if (revisions.isError) {
    return <p role="alert">{t('workflow.approval.scriptLoadError')}</p>;
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
  const { t } = useTranslation();
  const tasksQuery = useReviewTasksQuery(runId);

  if (tasksQuery.isLoading) {
    return <output>{t('workflow.approval.loadingTask')}</output>;
  }
  if (tasksQuery.isError) {
    return <p role="alert">{t('workflow.approval.taskLoadError')}</p>;
  }
  const task = tasksQuery.data?.find((candidate) => candidate.status === 'pending');
  if (!task?.id) {
    return null;
  }
  return (
    <ReviewForm key={task.id} runId={runId} task={task} originalPayload={task.originalPayload} />
  );
}
