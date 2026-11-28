import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import type {
  ReviewTaskResponse,
  RunStepResponse,
  ScriptRevisionResponse,
} from '@flow-cortex/api-client';
import { activeLocale } from '../../lib/i18n';
import { formatDateTime } from '../../lib/format';
import {
  useApproveTaskMutation,
  useDecisionKey,
  useRejectTaskMutation,
  useReviewTasksQuery,
  useRevisionsQuery,
} from './useWorkflows';
import { isLostRace } from './lostRace';
import { KeyframeReviewPanel } from './KeyframeReviewPanel';
import { PickList, type PickChoice } from './PickList';
import { QaReviewForm } from './QaReviewForm';
import { decisionRefusal, fieldMessage, ownSentence } from './reviewRefusal';
import { ScriptEditor } from './ScriptEditor';

interface ReviewFormProps {
  runId: string;
  task: ReviewTaskResponse;
  originalPayload: string | undefined;
  isMcTemplate?: boolean | undefined;
  keyframeStep?: RunStepResponse | undefined;
}

function ReviewForm({
  runId,
  task,
  originalPayload,
  isMcTemplate = false,
  keyframeStep,
}: Readonly<ReviewFormProps>) {
  const { t } = useTranslation();
  const original = originalPayload ?? '';
  const scripted = Boolean(task.currentRevisionId);
  const pickOptions = scripted || !task.pickOptions?.length ? undefined : task.pickOptions;
  const key = useDecisionKey();
  const [draft, setDraft] = useState(original);
  const [choice, setChoice] = useState<PickChoice>(undefined);
  const [own, setOwn] = useState('');
  const [scriptApprovalBlocked, setScriptApprovalBlocked] = useState(scripted);
  const [blankDraft, setBlankDraft] = useState(false);
  const approve = useApproveTaskMutation(runId, task.id ?? '', key);
  const reject = useRejectTaskMutation(runId, task.id ?? '', key);
  const busy = approve.isPending || reject.isPending;
  const approveBlocked = busy || scriptApprovalBlocked;
  const nothingChosen = pickOptions !== undefined && choice === undefined;
  const serverFieldError = scripted ? undefined : fieldMessage(approve.error, 'reviewedPayload');
  const contentError = blankDraft ? t('workflow.approval.contentEmpty') : serverFieldError;

  // Busy rather than disabled, so focus stays on the button while the decision is sent.
  const onApprove = () => {
    if (approveBlocked || nothingChosen) return;
    reject.reset();
    if (pickOptions) {
      approvePick();
      return;
    }
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
  const approvePick = () => {
    if (choice !== 'own') {
      approve.mutate({ pickedOption: choice });
    } else if (own.trim() === '') {
      approve.reset();
      setBlankDraft(true);
    } else {
      approve.mutate({ reviewedPayload: own });
    }
  };
  const onReject = () => {
    if (busy) return;
    approve.reset();
    reject.mutate();
  };

  if (!scripted && !pickOptions && keyframeStep) {
    return (
      <KeyframeReviewPanel
        runId={runId}
        keyframeStep={keyframeStep}
        onApprove={onApprove}
        onReject={onReject}
        approvePending={approve.isPending}
        rejectPending={reject.isPending}
        approveError={
          approve.isError && !serverFieldError ? approveError(approve.error, false, t) : undefined
        }
        rejectError={
          reject.isError
            ? (decisionRefusal(reject.error, t) ?? t('workflow.approval.rejectFailed'))
            : undefined
        }
      />
    );
  }

  return (
    <section className="approval-panel" aria-label={t('workflow.approval.region')}>
      {scripted ? (
        <ScriptReview
          task={task}
          runId={runId}
          isMcTemplate={isMcTemplate}
          onApprovalBlockedChange={setScriptApprovalBlocked}
        />
      ) : pickOptions ? (
        <PickList
          name={`pick-${task.id}`}
          options={pickOptions}
          choice={choice}
          onChoice={(next) => {
            setChoice(next);
            setBlankDraft(false);
          }}
          own={own}
          onOwn={(text) => {
            setOwn(text);
            setBlankDraft(false);
            if (serverFieldError) approve.reset();
          }}
          ownError={contentError}
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
          disabled={nothingChosen}
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
          {decisionRefusal(reject.error, t) ?? t('workflow.approval.rejectFailed')}
        </p>
      )}
    </section>
  );
}

/**
 * A 409 on a script checkpoint means the revision moved on, so the reviewer is told to reload
 * rather than shown the server's generic conflict sentence; an ended review has its own wording.
 */
function approveError(error: unknown, scripted: boolean, t: TFunction): string {
  const own = ownSentence(error, t);
  if (own) return own;
  if (scripted && isLostRace(error)) {
    return t('workflow.approval.scriptMovedOn');
  }
  return (
    fieldMessage(error, 'pickedOption') ??
    decisionRefusal(error, t) ??
    t('workflow.approval.approveFailed')
  );
}

/**
 * A script checkpoint loads its revisions once and passes them down: the editor needs the history
 * to show, and the approval needs the task's `currentRevisionId` — the revision the reviewer is
 * looking at, which a 409 then refuses if another tab has since saved past it.
 */
function ScriptReview({
  task,
  runId,
  isMcTemplate = false,
  onApprovalBlockedChange,
}: Readonly<{
  task: ReviewTaskResponse;
  runId: string;
  isMcTemplate?: boolean | undefined;
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
      isMcTemplate={isMcTemplate}
      onApprovalBlockedChange={onApprovalBlockedChange}
    />
  );
}

/** The time limit of the review, in the reader's language; nothing when the server sent none. */
function DecideBy({ expiresAt }: Readonly<{ expiresAt: string | undefined }>) {
  const { t } = useTranslation();
  if (!expiresAt || Number.isNaN(Date.parse(expiresAt))) {
    return null;
  }
  const when = formatDateTime(expiresAt, activeLocale(), { dateStyle: 'medium', timeStyle: 'short' });
  return (
    <p className="approval-decide-by">
      <time dateTime={expiresAt}>{t('workflow.approval.decideBy', { when })}</time>
    </p>
  );
}

/**
 * The pending review of a paused run: a checkpoint's content can be edited before it is approved,
 * its options picked from, or a QA review's questions answered.
 */
export function ApprovalPanel({
  runId,
  isMcTemplate = false,
  keyframeStep,
}: Readonly<{
  runId: string;
  isMcTemplate?: boolean | undefined;
  keyframeStep?: RunStepResponse | undefined;
}>) {
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
  // With options the pick list stands in for the questions, so a QA task with them is a plain form.
  const asking = task.reviewType === 'QA_REVIEW' && !task.pickOptions?.length;
  return (
    <div key={task.id} className="approval-review">
      <DecideBy expiresAt={task.expiresAt} />
      {asking ? (
        <QaReviewForm runId={runId} task={task} />
      ) : (
        <ReviewForm
          runId={runId}
          task={task}
          originalPayload={task.originalPayload}
          isMcTemplate={isMcTemplate}
          keyframeStep={keyframeStep}
        />
      )}
    </div>
  );
}
