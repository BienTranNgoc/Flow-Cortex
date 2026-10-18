import { useEffect, useId, useState, type FormEvent, type ReactNode } from 'react';
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
  text: string;
}

const FALLBACK = 'Something went wrong. Try again.';

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
  if (!(error instanceof ApiError)) return { text: FALLBACK };
  const fieldMessage = error.problem?.errors?.[field];
  if (fieldMessage) return { field, text: fieldMessage };
  const text = error.problem?.detail ?? FALLBACK;
  return problemCode(error) === 'OTP_INVALID' ? { field, text } : { text };
}

/** Proves the member owns a mobile number with a one-time SMS code. */
export function PhoneVerification() {
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
        {feedback.text}
      </p>
    ) : null;

  let body: ReactNode;
  if (phoneQuery.isPending) {
    body = <output>Loading your phone number…</output>;
  } else if (phoneQuery.isError) {
    body = (
      <p role="alert" className="form-error">
        Could not load your phone number.{' '}
        <button type="button" onClick={() => void phoneQuery.refetch()}>
          Retry
        </button>
      </p>
    );
  } else if (step === null && phoneQuery.data.verified) {
    const status = phoneQuery.data;
    body = (
      <>
        {justVerified && (
          <p className="profile-saved" role="status">
            Phone number verified
          </p>
        )}
        <div className="phone-verification__current">
          <span className="phone-verification__number">{status.phone}</span>
          <span className="phone-verification__badge">Verified</span>
        </div>
        <div className="profile-form__actions phone-verification__actions">
          <button type="button" className="cortex-btn cortex-btn--ghost" onClick={backToNumber}>
            Change number
          </button>
        </div>
      </>
    );
  } else if (step === 'code') {
    const resendLocked = countdown.remaining > 0 || sendMutation.isPending;
    body = (
      <form onSubmit={onVerifySubmit} noValidate>
        <p className="profile-card__hint">
          {sentTo ? `We sent a 6-digit code to ${sentTo}` : `Enter the code we last sent to ${phone.trim()}`}
        </p>
        <div className="field">
          <label htmlFor={codeId}>6-digit code</label>
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
          {feedbackFor('code')}
        </div>
        {feedbackFor(undefined)}
        <div className="phone-verification__resend">
          <button
            type="button"
            className="cortex-btn cortex-btn--ghost"
            onClick={() => send('code')}
            disabled={resendLocked}
          >
            Resend code
          </button>
          {countdown.remaining > 0 && (
            <span className="phone-verification__countdown">Resend in {clock(countdown.remaining)}</span>
          )}
        </div>
        <div className="profile-form__actions phone-verification__actions">
          <button
            type="button"
            className="cortex-btn cortex-btn--ghost"
            onClick={backToNumber}
            disabled={verifyMutation.isPending}
          >
            Use a different number
          </button>
          <button
            type="submit"
            className="cortex-btn cortex-btn--primary"
            disabled={verifyMutation.isPending}
          >
            {verifyMutation.isPending ? 'Verifying…' : 'Verify'}
          </button>
        </div>
      </form>
    );
  } else {
    const editingVerified = phoneQuery.data.verified;
    body = (
      <form onSubmit={onSendSubmit} noValidate>
        <div className="field">
          <label htmlFor={phoneId}>Mobile number</label>
          <input
            id={phoneId}
            type="tel"
            value={phone}
            onChange={(event) => setPhone(event.target.value)}
            inputMode="tel"
            autoComplete="tel"
            placeholder="0912345678"
            aria-invalid={feedback?.field === 'phone' || undefined}
            aria-describedby={feedback?.field === 'phone' ? `${feedbackId}-phone` : undefined}
          />
          {feedbackFor('phone')}
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
              Cancel
            </button>
          )}
          <button type="submit" className="cortex-btn cortex-btn--primary" disabled={sendMutation.isPending}>
            {sendMutation.isPending ? 'Sending…' : 'Send code'}
          </button>
        </div>
      </form>
    );
  }

  return (
    <section className="phone-verification" aria-labelledby={titleId}>
      <h2 className="profile-card__title" id={titleId}>
        Phone number
      </h2>
      <p className="profile-card__hint">We send a one-time code by SMS to confirm the number is yours.</p>
      {body}
    </section>
  );
}
