import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { ReviewQuestion, ReviewTaskResponse } from '@flow-cortex/api-client';
import { decisionRefusal, fieldMessage } from './reviewRefusal';
import {
  useAnswerTaskMutation,
  useApproveTaskMutation,
  useDecisionKey,
  useRejectTaskMutation,
  type QaAnswers,
} from './useWorkflows';

type Draft = Record<string, string | string[]>;

/** Only what the reviewer filled in: an unanswered question is left out and reads "(no answer)". */
function answersOf(questions: readonly ReviewQuestion[], draft: Draft): QaAnswers {
  const answers: QaAnswers = {};
  for (const question of questions) {
    const value = draft[question.id];
    if (Array.isArray(value) ? value.length > 0 : (value ?? '').trim() !== '') {
      answers[question.id] = value as string | string[];
    }
  }
  return answers;
}

interface QuestionProps {
  question: ReviewQuestion;
  taskId: string;
  value: string | string[] | undefined;
  error: string | undefined;
  disabled: boolean;
  onChange: (value: string | string[]) => void;
}

function Question({ question, taskId, value, error, disabled, onChange }: Readonly<QuestionProps>) {
  const inputId = `qa-${taskId}-${question.id}`;
  const labelId = `${inputId}-label`;
  const errorId = `${inputId}-problem`;
  const describedBy = error ? errorId : undefined;
  const chosen = Array.isArray(value) ? value : [];
  return (
    <div className="qa-question">
      {question.type === 'text' ? (
        <>
          <label htmlFor={inputId} id={labelId}>
            {question.question}
          </label>
          <textarea
            id={inputId}
            rows={3}
            value={typeof value === 'string' ? value : ''}
            disabled={disabled}
            onChange={(event) => onChange(event.target.value)}
            aria-invalid={error ? true : undefined}
            aria-describedby={describedBy}
          />
        </>
      ) : (
        <>
          <p id={labelId} className="qa-question-text">
            {question.question}
          </p>
          <div
            className="qa-options"
            role={question.type === 'multi' ? 'group' : 'radiogroup'}
            aria-labelledby={labelId}
            aria-invalid={error && question.type !== 'multi' ? true : undefined}
            aria-describedby={describedBy}
          >
            {question.options.map((option) => (
              <label key={option} className="qa-option">
                {question.type === 'multi' ? (
                  <input
                    type="checkbox"
                    checked={chosen.includes(option)}
                    disabled={disabled}
                    onChange={(event) =>
                      onChange(
                        event.target.checked
                          ? [...chosen, option]
                          : chosen.filter((entry) => entry !== option),
                      )
                    }
                  />
                ) : (
                  <input
                    type="radio"
                    name={inputId}
                    checked={value === option}
                    disabled={disabled}
                    onChange={() => onChange(option)}
                  />
                )}
                <span>{option}</span>
              </label>
            ))}
          </div>
        </>
      )}
      {error && (
        <div id={errorId} className="field-error" role="alert">
          <p className="form-error">{error}</p>
        </div>
      )}
    </div>
  );
}

/**
 * A QA review: the model's or the standard questions to answer before the next step, or approve
 * and reject like any checkpoint. The questions arrive a moment after the review is opened.
 */
export function QaReviewForm({ runId, task }: Readonly<{ runId: string; task: ReviewTaskResponse }>) {
  const { t } = useTranslation();
  const taskId = task.id ?? '';
  const key = useDecisionKey();
  const answer = useAnswerTaskMutation(runId, taskId, key);
  const approve = useApproveTaskMutation(runId, taskId, key);
  const reject = useRejectTaskMutation(runId, taskId, key);
  const [draft, setDraft] = useState<Draft>({});
  const questions = task.questions ?? [];
  const preparing = task.questionsState === 'generating';
  const busy = answer.isPending || approve.isPending || reject.isPending;

  const fieldErrors = Object.fromEntries(
    questions.map((question) => [question.id, fieldMessage(answer.error, `answers.${question.id}`)]),
  );
  const answerAlert = Object.values(fieldErrors).some(Boolean)
    ? undefined
    : (decisionRefusal(answer.error, t) ?? (answer.isError ? t('workflow.qa.sendFailed') : undefined));

  const onSend = () => {
    if (busy) return;
    approve.reset();
    reject.reset();
    answer.mutate(answersOf(questions, draft));
  };
  const onApprove = () => {
    if (busy) return;
    answer.reset();
    reject.reset();
    approve.mutate({});
  };
  const onReject = () => {
    if (busy) return;
    answer.reset();
    approve.reset();
    reject.mutate();
  };
  const onChange = (id: string, value: string | string[]) => {
    setDraft((current) => ({ ...current, [id]: value }));
    if (fieldErrors[id]) answer.reset();
  };

  return (
    <section className="approval-panel qa-review" aria-label={t('workflow.qa.region')}>
      {preparing ? (
        <output className="qa-preparing">{t('workflow.qa.preparing')}</output>
      ) : (
        questions.map((question) => (
          <Question
            key={question.id}
            question={question}
            taskId={taskId}
            value={draft[question.id]}
            error={fieldErrors[question.id]}
            disabled={answer.isPending}
            onChange={(value) => onChange(question.id, value)}
          />
        ))
      )}
      <div className="approval-actions">
        {!preparing && (
          <button
            type="button"
            onClick={onSend}
            aria-disabled={busy}
            aria-busy={answer.isPending}
          >
            {answer.isPending ? t('workflow.qa.sending') : t('workflow.qa.send')}
          </button>
        )}
        <button
          type="button"
          onClick={onApprove}
          aria-disabled={busy}
          aria-busy={approve.isPending}
        >
          {approve.isPending ? t('workflow.approval.approving') : t('workflow.qa.approveAsIs')}
        </button>
        <button type="button" onClick={onReject} aria-disabled={busy} aria-busy={reject.isPending}>
          {reject.isPending ? t('workflow.approval.rejecting') : t('workflow.approval.reject')}
        </button>
      </div>
      {answerAlert && (
        <p role="alert" className="approval-error">
          {answerAlert}
        </p>
      )}
      {approve.isError && (
        <p role="alert" className="approval-error">
          {decisionRefusal(approve.error, t) ?? t('workflow.approval.approveFailed')}
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
