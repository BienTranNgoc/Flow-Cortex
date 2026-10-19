import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import { ApiError } from '@flow-cortex/api-client';
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
  const [blankDraft, setBlankDraft] = useState(false);
  const approve = useApproveTaskMutation(runId, task.id ?? '');
  const reject = useRejectTaskMutation(runId, task.id ?? '');
  const busy = approve.isPending || reject.isPending;
  const approveBlocked = busy || scriptApprovalBlocked;
  const serverFieldError = scripted ? undefined : payloadFieldError(approve.error);
  const contentError = blankDraft ? t('workflow.approval.contentEmpty') : serverFieldError;

  // Busy rather than disabled, so focus stays on the button while the decision is sent.
  const onApprove = () => {
    if (approveBlocked) return;
    reject.reset();
    if (!scripted && draft !== original && draft.trim() === '') {
      approve.reset();
      setBlankDraft(true);
      return;
    }
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
            onChange={(event) => {
              setDraft(event.target.value);
              setBlankDraft(false);
              if (serverFieldError) approve.reset();
            }}
            aria-invalid={contentError ? true : undefined}
            aria-describedby={contentError ? 'approval-payload-problem' : undefined}
          />
          {contentError && (
            <div id="approval-payload-problem" className="field-error" role="alert">
              <p className="form-error">{contentError}</p>
            </div>
          )}
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
      {approve.isError && !serverFieldError && (
        <p role="alert" className="approval-error">
          {approveError(approve.error, scripted, t)}
        </p>
      )}
      {reject.isError && (
        <p role="alert" className="approval-error">
          {refusalReason(reject.error) ?? t('workflow.approval.rejectFailed')}
        </p>
      )}
    </section>
  );
}

/**
 * A 409 on a script checkpoint means the revision moved on, so the reviewer is told to reload
 * rather than shown the server's generic conflict sentence.
 */
function approveError(error: unknown, scripted: boolean, t: TFunction): string {
  if (scripted && isLostRace(error)) {
    return t('workflow.approval.scriptMovedOn');
  }
  return refusalReason(error) ?? t('workflow.approval.approveFailed');
}

/**
 * A refused decision (403, 409, 422…) carries a sentence written for the member; a 5xx detail
 * does not, so the caller falls back to its own wording.
 */
function refusalReason(error: unknown): string | undefined {
  if (!(error instanceof ApiError) || error.status >= 500) return undefined;
  return error.problem?.detail;
}

function payloadFieldError(error: unknown): string | undefined {
  return error instanceof ApiError ? error.problem?.errors?.reviewedPayload : undefined;
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
