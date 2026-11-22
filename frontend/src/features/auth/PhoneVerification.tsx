import { useEffect, useId, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiError, StepUpRequestPurpose } from '@flow-cortex/api-client';
import { formatDateTime } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import { StepUpDialog } from './StepUpDialog';
import {
  problemCode,
  retryAfterSeconds,
  useChangeCancelMutation,
  useChangeStartMutation,
  useChangeVerifyMutation,
  usePhoneQuery,
  useRefreshPhone,
  useSendCodeMutation,
  useVerifyCodeMutation,
} from './usePhone';
import './PhoneVerification.css';

type Step = 'number' | 'code';
type Notice = 'verified' | 'changed' | 'reverted';

const NOTICE_KEY: Record<Notice, string> = {
  verified: 'auth.phone.verifiedNotice',
  changed: 'auth.phone.change.changedNotice',
  reverted: 'auth.phone.change.revertedNotice',
};

const DEADLINE_FORMAT: Intl.DateTimeFormatOptions = { dateStyle: 'medium', timeStyle: 'short' };

interface Feedback {
  field?: 'phone' | 'code';
  /** The server's wording; absent means the generic retry message in the active language. */
  text?: string | undefined;
}

const PHONE_FIELD = 'phone';
const CODE_FIELD = 'code';

/** Counts whole seconds down to a deadline; the deadline survives re-renders, the tick does not drift. */
function useCountdown() {
  const [deadline, setDeadline] = useState<number | null>(null);
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (deadline === null) return undefined;
    const id = setInterval(() => {
      const tick = Date.now();
      setNow(tick);
      if (tick >= deadline) setDeadline(null);
    }, 1000);
    return () => clearInterval(id);
  }, [deadline]);

  const remaining = deadline === null ? 0 : Math.max(0, Math.ceil((deadline - now) / 1000));
  const start = (seconds: number) => {
    const startedAt = Date.now();
    setNow(startedAt);
    setDeadline(seconds > 0 ? startedAt + seconds * 1000 : null);
  };
  return { remaining, start };
}

function clock(seconds: number): string {
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}

function feedbackFrom(error: unknown, field: 'phone' | 'code'): Feedback {
  if (!(error instanceof ApiError)) return {};
  const fieldMessage = error.problem?.errors?.[field];
  if (fieldMessage) return { field, text: fieldMessage };
  const text = error.problem?.detail;
  const code = problemCode(error);
  const belongsToField =
    code === 'OTP_INVALID' || (code === 'PHONE_SAME_NUMBER' && field === 'phone');
  return belongsToField ? { field, text } : { text };
}

/** Proves the member owns a mobile number with a one-time SMS code. */
export function PhoneVerification() {
  const { t } = useTranslation();
  const phoneQuery = usePhoneQuery();
  const sendMutation = useSendCodeMutation();
  const verifyMutation = useVerifyCodeMutation();
  const changeStartMutation = useChangeStartMutation();
  const changeVerifyMutation = useChangeVerifyMutation();
  const changeCancelMutation = useChangeCancelMutation();
  const refreshPhone = useRefreshPhone();
  const countdown = useCountdown();
  const ids = useId();

  const [step, setStep] = useState<Step | null>(null);
  const [phone, setPhone] = useState('');
  const [code, setCode] = useState('');
  const [sentTo, setSentTo] = useState<string | null>(null);
  const [feedback, setFeedback] = useState<Feedback | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [undoFailure, setUndoFailure] = useState<Feedback | null>(null);
  const [askingPassword, setAskingPassword] = useState(false);
  // The step a send came from, kept while the password dialog is open so its proof resumes there.
  const askedFrom = useRef<Step>('number');

  const titleId = `${ids}-title`;
  const phoneId = `${ids}-phone`;
  const codeId = `${ids}-code`;
  const feedbackId = `${ids}-feedback`;

  // A verified member can only replace the number through the password-checked change flow.
  const changing = phoneQuery.data?.verified === true;
  const sending = sendMutation.isPending || changeStartMutation.isPending;
  const verifying = verifyMutation.isPending || changeVerifyMutation.isPending;

  const sendOutcome = (fromStep: Step) => ({
    onSuccess: (sent: { maskedPhone: string; resendAfterSeconds: number }) => {
      setSentTo(sent.maskedPhone);
      setCode('');
      setStep('code');
      countdown.start(sent.resendAfterSeconds);
    },
    onError: (error: unknown) => {
      const refusal =
        problemCode(error) === 'STEP_UP_REQUIRED'
          ? { text: t('auth.phone.change.stepUpRequired') }
          : feedbackFrom(error, 'phone');
      // The code step has no phone field to attach a field message to.
      setFeedback(fromStep === 'code' ? { text: refusal.text } : refusal);
      if (problemCode(error) === 'OTP_COOLDOWN') {
        countdown.start(retryAfterSeconds(error) ?? 0);
        if (fromStep === 'number') {
          // A code is already on its way to this number; let the member enter it.
          setSentTo(null);
          setStep('code');
        }
      }
    },
  });

  const send = (fromStep: Step) => {
    setFeedback(null);
    setNotice(null);
    if (changing) {
      // Every change start spends a fresh proof, so a resend asks for the password again.
      askedFrom.current = fromStep;
      setAskingPassword(true);
      return;
    }
    sendMutation.mutate(phone.trim(), sendOutcome(fromStep));
  };

  const onProof = (stepUpProof: string) => {
    setAskingPassword(false);
    changeStartMutation.mutate({ phone: phone.trim(), stepUpProof }, sendOutcome(askedFrom.current));
  };

  const onSendSubmit = (event: FormEvent) => {
    event.preventDefault();
    send('number');
  };

  const onVerifySubmit = (event: FormEvent) => {
    event.preventDefault();
    setFeedback(null);
    const outcome = {
      onSuccess: () => {
        setStep(null);
        setCode('');
        setNotice(changing ? 'changed' : 'verified');
      },
      onError: (error: unknown) => setFeedback(feedbackFrom(error, 'code')),
    };
    if (changing) changeVerifyMutation.mutate(code.trim(), outcome);
    else verifyMutation.mutate(code.trim(), outcome);
  };

  const backToNumber = () => {
    setFeedback(null);
    setStep('number');
  };

  const startChange = () => {
    setPhone('');
    setNotice(null);
    setUndoFailure(null);
    backToNumber();
  };

  const onUndo = () => {
    setUndoFailure(null);
    setNotice(null);
    changeCancelMutation.mutate(undefined, {
      onSuccess: () => setNotice('reverted'),
      onError: (error) => {
        setUndoFailure(feedbackFrom(error, 'phone'));
        const refusal = problemCode(error);
        // The cached status still offers an undo the server no longer allows.
        if (refusal === 'PHONE_NO_UNDO_WINDOW' || refusal === 'PHONE_REVERT_CONFLICT') {
          void refreshPhone();
        }
      },
    });
  };

  const feedbackFor = (field: Feedback['field']) =>
    feedback && feedback.field === field ? (
      <p role="alert" className="form-error" id={field ? `${feedbackId}-${field}` : feedbackId}>
        {feedback.text ?? t('common.errors.requestFailed')}
      </p>
    ) : null;

  let body: ReactNode;
  if (phoneQuery.isPending) {
    body = <output>{t('auth.phone.loading')}</output>;
  } else if (phoneQuery.isError) {
    body = (
      <p role="alert" className="form-error">
        {t('auth.phone.loadError')}{' '}
        <button type="button" onClick={() => void phoneQuery.refetch()}>
          {t('auth.phone.retry')}
        </button>
      </p>
    );
  } else if (step === null && phoneQuery.data.verified) {
    const status = phoneQuery.data;
    const undoUntil = status.cancelAvailableUntil;
    // The server decides on the click; this only keeps an expired deadline from being offered.
    const undoOpen = typeof undoUntil === 'string' && new Date(undoUntil).getTime() > Date.now();
    body = (
      <>
        {notice && (
          <p className="profile-saved" role="status">
            {t(NOTICE_KEY[notice])}
          </p>
        )}
        <div className="phone-verification__current">
          <span className="phone-verification__number">{status.phone}</span>
          <span className="phone-verification__badge">{t('auth.phone.verifiedBadge')}</span>
        </div>
        {undoOpen && (
          <p className="profile-card__hint phone-verification__undo-until">
            {t('auth.phone.change.undoUntil', {
              deadline: formatDateTime(undoUntil, activeLocale(), DEADLINE_FORMAT),
            })}
          </p>
        )}
        {undoFailure && (
          <p role="alert" className="form-error phone-verification__undo-failure">
            {undoFailure.text ?? t('common.errors.requestFailed')}
          </p>
        )}
        <div className="profile-form__actions phone-verification__actions">
          {undoOpen && (
            <button
              type="button"
              className="cortex-btn cortex-btn--ghost"
              onClick={onUndo}
              disabled={changeCancelMutation.isPending}
              aria-busy={changeCancelMutation.isPending}
            >
              {changeCancelMutation.isPending
                ? t('auth.phone.change.undoing')
                : t('auth.phone.change.undo')}
            </button>
          )}
          <button type="button" className="cortex-btn cortex-btn--ghost" onClick={startChange}>
            {t('auth.phone.changeNumber')}
          </button>
        </div>
      </>
    );
  } else if (step === 'code') {
    const resendLocked = countdown.remaining > 0 || sending;
    body = (
      <form onSubmit={onVerifySubmit} noValidate>
        <p className="profile-card__hint">
          {sentTo
            ? t('auth.phone.codeSent', { phone: sentTo })
            : t('auth.phone.codeSentEarlier', { phone: phone.trim() })}
        </p>
        <div className="field">
          <label htmlFor={codeId}>{t('auth.phone.codeLabel')}</label>
          <input
            id={codeId}
            className="phone-verification__code"
            value={code}
            onChange={(event) => setCode(event.target.value)}
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={6}
            aria-invalid={feedback?.field === 'code' || undefined}
            aria-describedby={feedback?.field === 'code' ? `${feedbackId}-code` : undefined}
          />
          {feedbackFor(CODE_FIELD)}
        </div>
        {feedbackFor(undefined)}
        <div className="phone-verification__resend">
          <button
            type="button"
            className="cortex-btn cortex-btn--ghost"
            onClick={() => send('code')}
            disabled={resendLocked}
          >
            {t('auth.phone.resend')}
          </button>
          {countdown.remaining > 0 && (
            <span className="phone-verification__countdown">
              {t('auth.phone.resendIn', { time: clock(countdown.remaining) })}
            </span>
          )}
        </div>
        <div className="profile-form__actions phone-verification__actions">
          <button
            type="button"
            className="cortex-btn cortex-btn--ghost"
            onClick={backToNumber}
            disabled={verifying}
          >
            {t('auth.phone.differentNumber')}
          </button>
          <button
            type="submit"
            className="cortex-btn cortex-btn--primary"
            disabled={verifying}
          >
            {verifying ? t('auth.phone.verifying') : t('auth.phone.verify')}
          </button>
        </div>
      </form>
    );
  } else {
    const hintId = `${ids}-number-hint`;
    body = (
      <form onSubmit={onSendSubmit} noValidate>
        {changing && (
          <p className="profile-card__hint" id={hintId}>
            {t('auth.phone.change.numberHint')}
          </p>
        )}
        <div className="field">
          <label htmlFor={phoneId}>
            {changing ? t('auth.phone.change.numberLabel') : t('auth.phone.numberLabel')}
          </label>
          <input
            id={phoneId}
            type="tel"
            value={phone}
            onChange={(event) => setPhone(event.target.value)}
            inputMode="tel"
            autoComplete="tel"
            placeholder={t('auth.phone.numberPlaceholder')}
            aria-invalid={feedback?.field === 'phone' || undefined}
            aria-describedby={
              [changing ? hintId : null, feedback?.field === 'phone' ? `${feedbackId}-phone` : null]
                .filter(Boolean)
                .join(' ') || undefined
            }
          />
          {feedbackFor(PHONE_FIELD)}
        </div>
        {feedbackFor(undefined)}
        <div className="profile-form__actions phone-verification__actions">
          {changing && (
            <button
              type="button"
              className="cortex-btn cortex-btn--ghost"
              onClick={() => {
                setFeedback(null);
                setStep(null);
              }}
              disabled={sending}
            >
              {t('auth.phone.cancel')}
            </button>
          )}
          <button type="submit" className="cortex-btn cortex-btn--primary" disabled={sending}>
            {sending && t('auth.phone.sending')}
            {!sending && changing && t('auth.phone.change.continue')}
            {!sending && !changing && t('auth.phone.send')}
          </button>
        </div>
      </form>
    );
  }

  return (
    <section className="phone-verification" aria-labelledby={titleId}>
      <h2 className="profile-card__title" id={titleId}>
        {t('auth.phone.title')}
      </h2>
      <p className="profile-card__hint">{t('auth.phone.hint')}</p>
      {body}
      <StepUpDialog
        purpose={StepUpRequestPurpose.PHONE_CHANGE}
        open={askingPassword}
        onProof={onProof}
        onCancel={() => setAskingPassword(false)}
      />
    </section>
  );
}
