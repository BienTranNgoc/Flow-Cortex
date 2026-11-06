import { useEffect, useId, useState, type FormEvent, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@flow-cortex/api-client';
import {
  problemCode,
  retryAfterSeconds,
  usePhoneQuery,
  useSendCodeMutation,
  useVerifyCodeMutation,
} from './usePhone';
import './PhoneVerification.css';

type Step = 'number' | 'code';

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
  return problemCode(error) === 'OTP_INVALID' ? { field, text } : { text };
}

/** Proves the member owns a mobile number with a one-time SMS code. */
export function PhoneVerification() {
  const { t } = useTranslation();
  const phoneQuery = usePhoneQuery();
  const sendMutation = useSendCodeMutation();
  const verifyMutation = useVerifyCodeMutation();
  const countdown = useCountdown();
  const ids = useId();

  const [step, setStep] = useState<Step | null>(null);
  const [phone, setPhone] = useState('');
  const [code, setCode] = useState('');
  const [sentTo, setSentTo] = useState<string | null>(null);
  const [feedback, setFeedback] = useState<Feedback | null>(null);
  const [justVerified, setJustVerified] = useState(false);

  const titleId = `${ids}-title`;
  const phoneId = `${ids}-phone`;
  const codeId = `${ids}-code`;
  const feedbackId = `${ids}-feedback`;

  const send = (fromStep: Step) => {
    setFeedback(null);
    setJustVerified(false);
    sendMutation.mutate(phone.trim(), {
      onSuccess: (sent) => {
        setSentTo(sent.maskedPhone);
        setCode('');
        setStep('code');
        countdown.start(sent.resendAfterSeconds);
      },
      onError: (error) => {
        const refusal = feedbackFrom(error, 'phone');
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
  };

  const onSendSubmit = (event: FormEvent) => {
    event.preventDefault();
    send('number');
  };

  const onVerifySubmit = (event: FormEvent) => {
    event.preventDefault();
    setFeedback(null);
    verifyMutation.mutate(code.trim(), {
      onSuccess: () => {
        setStep(null);
        setCode('');
        setJustVerified(true);
      },
      onError: (error) => setFeedback(feedbackFrom(error, 'code')),
    });
  };

  const backToNumber = () => {
    setFeedback(null);
    setStep('number');
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
    body = (
      <>
        {justVerified && (
          <p className="profile-saved" role="status">
            {t('auth.phone.verifiedNotice')}
          </p>
        )}
        <div className="phone-verification__current">
          <span className="phone-verification__number">{status.phone}</span>
          <span className="phone-verification__badge">{t('auth.phone.verifiedBadge')}</span>
        </div>
        <div className="profile-form__actions phone-verification__actions">
          <button type="button" className="cortex-btn cortex-btn--ghost" onClick={backToNumber}>
            {t('auth.phone.changeNumber')}
          </button>
        </div>
      </>
    );
  } else if (step === 'code') {
    const resendLocked = countdown.remaining > 0 || sendMutation.isPending;
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
            disabled={verifyMutation.isPending}
          >
            {t('auth.phone.differentNumber')}
          </button>
          <button
            type="submit"
            className="cortex-btn cortex-btn--primary"
            disabled={verifyMutation.isPending}
          >
            {verifyMutation.isPending ? t('auth.phone.verifying') : t('auth.phone.verify')}
          </button>
        </div>
      </form>
    );
  } else {
    const editingVerified = phoneQuery.data.verified;
    body = (
      <form onSubmit={onSendSubmit} noValidate>
        <div className="field">
          <label htmlFor={phoneId}>{t('auth.phone.numberLabel')}</label>
          <input
            id={phoneId}
            type="tel"
            value={phone}
            onChange={(event) => setPhone(event.target.value)}
            inputMode="tel"
            autoComplete="tel"
            placeholder={t('auth.phone.numberPlaceholder')}
            aria-invalid={feedback?.field === 'phone' || undefined}
            aria-describedby={feedback?.field === 'phone' ? `${feedbackId}-phone` : undefined}
          />
          {feedbackFor(PHONE_FIELD)}
        </div>
        {feedbackFor(undefined)}
        <div className="profile-form__actions phone-verification__actions">
          {editingVerified && (
            <button
              type="button"
              className="cortex-btn cortex-btn--ghost"
              onClick={() => {
                setFeedback(null);
                setStep(null);
              }}
              disabled={sendMutation.isPending}
            >
              {t('auth.phone.cancel')}
            </button>
          )}
          <button type="submit" className="cortex-btn cortex-btn--primary" disabled={sendMutation.isPending}>
            {sendMutation.isPending ? t('auth.phone.sending') : t('auth.phone.send')}
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
    </section>
  );
}
