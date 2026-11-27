import {
  ApiError,
  RunFeedbackRequestIssue,
  type RunFeedbackRequest,
  type RunFeedbackRequestVerdict,
  type RunFeedbackResponse,
} from '@flow-cortex/api-client';
import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useRunFeedback } from './useRunFeedback';
import './RunExtras.css';

const ISSUES = Object.values(RunFeedbackRequestIssue);
const MAX_DETAIL = 1000;

interface EvalFormProps {
  verdict: RunFeedbackRequestVerdict;
  stored: RunFeedbackResponse | undefined;
  pending: boolean;
  /** Why the last send failed; null until one does. */
  error: Error | null;
  onSend: (body: RunFeedbackRequest) => void;
  onCancel: () => void;
}

/** The fields of one verdict; the entered values live here, so a failed send keeps them. */
function EvalForm({ verdict, stored, pending, error, onSend, onCancel }: Readonly<EvalFormProps>) {
  const { t } = useTranslation();
  const idBase = useId();
  const keep = stored?.verdict === verdict ? stored : undefined;
  const [issue, setIssue] = useState(keep?.issue ?? '');
  const [detail, setDetail] = useState(keep?.detail ?? '');
  const formRef = useRef<HTMLFormElement>(null);
  const down = verdict === 'down';
  const chosen = ISSUES.find((code) => code === issue);
  const ready = !pending && (!down || chosen !== undefined);

  useEffect(() => {
    formRef.current?.querySelector<HTMLElement>('select, textarea')?.focus();
  }, []);

  const submit = () => {
    const body: RunFeedbackRequest = { verdict };
    if (down && chosen) body.issue = chosen;
    if (detail.trim() !== '') body.detail = detail;
    onSend(body);
  };
  const refused = error instanceof ApiError && error.status === 400;

  return (
    <form
      ref={formRef}
      className="run-eval-form"
      onSubmit={(event) => {
        event.preventDefault();
        if (ready) submit();
      }}
    >
      <p className="run-eval-note">{t('workflow.eval.dialog.note')}</p>
      {down && (
        <div className="run-eval-field">
          <label htmlFor={`${idBase}-issue`}>{t('workflow.eval.dialog.issue')}</label>
          <select
            id={`${idBase}-issue`}
            required
            value={issue}
            disabled={pending}
            onChange={(event) => setIssue(event.target.value)}
          >
            <option value="">{t('workflow.eval.dialog.issuePlaceholder')}</option>
            {ISSUES.map((code) => (
              <option key={code} value={code}>
                {t(`workflow.eval.issues.${code}`)}
              </option>
            ))}
          </select>
        </div>
      )}
      <div className="run-eval-field">
        <label htmlFor={`${idBase}-detail`}>{t('workflow.eval.dialog.detail')}</label>
        <textarea
          id={`${idBase}-detail`}
          rows={4}
          maxLength={MAX_DETAIL}
          value={detail}
          readOnly={pending}
          onChange={(event) => setDetail(event.target.value)}
        />
      </div>
      {error && (
        <p role="alert" className="run-eval-error">
          {t(refused ? 'workflow.eval.error.invalid' : 'workflow.eval.error.send')}
        </p>
      )}
      <div className="run-eval-actions">
        <button type="button" onClick={onCancel} disabled={pending}>
          {t('workflow.eval.dialog.cancel')}
        </button>
        <button type="submit" disabled={!ready} aria-busy={pending}>
          {pending ? t('workflow.eval.dialog.sending') : t('workflow.eval.dialog.send')}
        </button>
      </div>
    </form>
  );
}

interface RunEvalDialogProps {
  runId: string;
  /** The verdict being given; null while the dialog is closed. */
  verdict: RunFeedbackRequestVerdict | null;
  /** Feedback already stored, offered back when the owner reopens the same verdict. */
  stored: RunFeedbackResponse | undefined;
  onClose: () => void;
  onSent: (sent: RunFeedbackResponse) => void;
}

/**
 * The feedback form as a native modal dialog: focus starts on its first field, Escape and Cancel
 * close it unless a send is in flight, and focus goes back to the thumb that opened it. A failed
 * send keeps it open with the entered values.
 */
export function RunEvalDialog({
  runId,
  verdict,
  stored,
  onClose,
  onSent,
}: Readonly<RunEvalDialogProps>) {
  const { t } = useTranslation();
  const titleId = useId();
  const dialogRef = useRef<HTMLDialogElement>(null);
  const openerRef = useRef<HTMLElement | null>(null);
  const feedback = useRunFeedback(runId);
  const { reset } = feedback;
  const open = verdict !== null;

  // Read before the form's own effect moves focus to its first field.
  useLayoutEffect(() => {
    if (open && !dialogRef.current?.open) {
      openerRef.current =
        document.activeElement instanceof HTMLElement ? document.activeElement : null;
    }
  }, [open]);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (open) {
      if (!dialog.open) {
        reset();
        dialog.showModal();
      }
      return;
    }
    if (dialog.open) dialog.close();
    const opener = openerRef.current;
    openerRef.current = null;
    if (opener?.isConnected) opener.focus();
  }, [open, reset]);

  const close = () => {
    if (!feedback.isPending) onClose();
  };

  return (
    <dialog
      ref={dialogRef}
      className="run-eval-dialog"
      aria-modal="true"
      aria-labelledby={titleId}
      onCancel={(event) => {
        event.preventDefault();
        close();
      }}
      // A browser may close the dialog on a repeated Escape despite `preventDefault`.
      onClose={() => {
        if (open) onClose();
      }}
    >
      <h2 id={titleId}>
        {t(verdict === 'down' ? 'workflow.eval.dialog.titleDown' : 'workflow.eval.dialog.titleUp')}
      </h2>
      {verdict && (
        <EvalForm
          key={verdict}
          verdict={verdict}
          stored={stored}
          pending={feedback.isPending}
          error={feedback.error}
          onSend={(body) => feedback.mutate(body, { onSuccess: onSent })}
          onCancel={close}
        />
      )}
    </dialog>
  );
}
