import type { RunFeedbackRequestVerdict, RunFeedbackResponse } from '@flow-cortex/api-client';
import { useEffect, useId, useRef, useState, type Ref } from 'react';
import { useTranslation } from 'react-i18next';
import { RunEvalDialog } from './RunEvalDialog';
import './RunExtras.css';

interface ThumbProps {
  verdict: RunFeedbackRequestVerdict;
  label: string;
  buttonRef?: Ref<HTMLButtonElement>;
  onPress: () => void;
}

function Thumb({ verdict, label, buttonRef, onPress }: Readonly<ThumbProps>) {
  return (
    <button
      ref={buttonRef}
      type="button"
      className={`run-eval-thumb run-eval-thumb--${verdict}`}
      aria-haspopup="dialog"
      onClick={onPress}
    >
      <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" focusable="false">
        <path
          fill="currentColor"
          d="M1 21h4V9H1v12Zm22-11a2 2 0 0 0-2-2h-6.3l1-4.6v-.3c0-.4-.2-.8-.4-1.1L14.2 1 7.6 7.6C7.2 7.9 7 8.4 7 9v10a2 2 0 0 0 2 2h9c.8 0 1.5-.5 1.8-1.2l3-7.1c.1-.2.2-.5.2-.7v-2Z"
        />
      </svg>
      <span>{label}</span>
    </button>
  );
}

interface RunEvalBarProps {
  runId: string;
  /** The feedback stored on the run, null or absent until one was given. */
  feedback: RunFeedbackResponse | null | undefined;
}

/**
 * "How is this result?" with a thumb each way. A thumb opens the feedback dialog; once sent, the
 * bar says so and offers "Change", which brings the thumbs back.
 */
export function RunEvalBar({ runId, feedback }: Readonly<RunEvalBarProps>) {
  const { t } = useTranslation();
  const questionId = useId();
  const [dialog, setDialog] = useState<RunFeedbackRequestVerdict | null>(null);
  // The answer to a send shows before the re-read of the run brings it back as `feedback`.
  const [sent, setSent] = useState<RunFeedbackResponse>();
  const [changing, setChanging] = useState(false);
  const stored = sent ?? feedback ?? undefined;
  const shown = changing ? undefined : stored;
  const showSent = shown !== undefined;
  const upRef = useRef<HTMLButtonElement>(null);
  const changeRef = useRef<HTMLButtonElement>(null);
  const moveFocus = useRef<'change' | 'thumbs' | null>(null);

  // Whichever control had focus leaves with the state it belonged to; focus follows to its successor.
  useEffect(() => {
    const target = moveFocus.current === 'change' ? changeRef.current : upRef.current;
    if (moveFocus.current) target?.focus();
    moveFocus.current = null;
  }, [showSent]);

  return (
    <div className="run-eval" role="group" aria-labelledby={questionId}>
      <p id={questionId} className="run-eval-question">
        {t('workflow.eval.question')}
      </p>
      {shown ? (
        <div className="run-eval-sent">
          <p role="status">
            {t(shown.verdict === 'down' ? 'workflow.eval.sent.down' : 'workflow.eval.sent.up')}
          </p>
          <button
            ref={changeRef}
            type="button"
            onClick={() => {
              moveFocus.current = 'thumbs';
              setChanging(true);
            }}
          >
            {t('workflow.eval.change')}
          </button>
        </div>
      ) : (
        <div className="run-eval-thumbs">
          <Thumb
            verdict="up"
            label={t('workflow.eval.up')}
            buttonRef={upRef}
            onPress={() => setDialog('up')}
          />
          <Thumb verdict="down" label={t('workflow.eval.down')} onPress={() => setDialog('down')} />
        </div>
      )}
      <RunEvalDialog
        runId={runId}
        verdict={dialog}
        stored={stored}
        onClose={() => setDialog(null)}
        onSent={(answer) => {
          moveFocus.current = 'change';
          setSent(answer);
          setChanging(false);
          setDialog(null);
        }}
      />
    </div>
  );
}
