import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { BrandPanel } from '../../app/BrandPanel';
import { resendEmailSchema } from './registerSchema';
import {
  CONFIRMATION_LINK_INVALID,
  detailFrom,
  problemCodeFrom,
  useConfirmEmailMutation,
  useResendConfirmationMutation,
} from './useAuth';

const LINK_INVALID_MESSAGE = 'This confirmation link is no longer valid';

interface ResendConfirmationProps {
  /** The address to send to; when omitted the member types it in. */
  email?: string;
}

/** "Resend confirmation email" with its sending, sent and refused states. */
export function ResendConfirmation({ email }: ResendConfirmationProps) {
  const resend = useResendConfirmationMutation();
  const [typedEmail, setTypedEmail] = useState('');
  const [emailError, setEmailError] = useState<string | null>(null);

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    const parsed = resendEmailSchema.safeParse(email ?? typedEmail);
    if (!parsed.success) {
      setEmailError(parsed.error.issues[0]?.message ?? 'Enter a valid email address');
      return;
    }
    setEmailError(null);
    resend.mutate(parsed.data);
  };

  const refusal = resend.isError
    ? (detailFrom(resend) ?? 'Could not send a new link. Please try again.')
    : null;

  return (
    <form onSubmit={onSubmit} noValidate aria-label="Resend confirmation" className="auth-form">
      {email === undefined && (
        <div className="field">
          <label htmlFor="resend-email">Email</label>
          <input
            id="resend-email"
            type="email"
            autoComplete="email"
            value={typedEmail}
            onChange={(event) => setTypedEmail(event.target.value)}
            aria-invalid={emailError ? 'true' : 'false'}
            aria-describedby={emailError ? 'resend-email-error' : undefined}
          />
          {emailError && (
            <p id="resend-email-error" role="alert">
              {emailError}
            </p>
          )}
        </div>
      )}
      <button type="submit" disabled={resend.isPending}>
        {resend.isPending ? 'Sending…' : 'Resend confirmation email'}
      </button>
      {resend.isSuccess && (
        <p role="status" className="auth-subtitle">
          If an account needs confirming, we sent a new link
        </p>
      )}
      {refusal && (
        <p role="alert" className="form-error">
          {refusal}
        </p>
      )}
    </form>
  );
}

/** Opened from the emailed link: spends `?token=` once, then lands on the home page signed in. */
export function ConfirmEmailPage() {
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token') ?? '';
  const navigate = useNavigate();
  const confirm = useConfirmEmailMutation();
  const { mutate } = confirm;
  // A link spends on first use, so StrictMode's second effect run must not send it again.
  const sent = useRef(false);

  useEffect(() => {
    if (!token || sent.current) {
      return;
    }
    sent.current = true;
    mutate(token, { onSuccess: () => navigate('/', { replace: true }) });
  }, [token, mutate, navigate]);

  const linkInvalid =
    !token || (confirm.isError && problemCodeFrom(confirm.error) === CONFIRMATION_LINK_INVALID);
  const otherFailure =
    !linkInvalid && confirm.isError
      ? (detailFrom(confirm) ?? 'Could not confirm your email. Please try again.')
      : null;

  return (
    <div className="auth-page">
      <BrandPanel />

      <section className="auth-card">
        <p className="eyebrow">CREATOR ACCESS · CONFIRM EMAIL</p>
        <h1 className="auth-card__title">Confirm your email</h1>

        {linkInvalid && (
          <>
            <p role="alert" className="form-error">
              {LINK_INVALID_MESSAGE}
            </p>
            <p className="auth-subtitle">Enter your email and we will send you a new link.</p>
            <ResendConfirmation />
          </>
        )}

        {otherFailure && (
          <>
            <p role="alert" className="form-error">
              {otherFailure}
            </p>
            <button
              type="button"
              disabled={confirm.isPending}
              onClick={() => mutate(token, { onSuccess: () => navigate('/', { replace: true }) })}
            >
              Try again
            </button>
          </>
        )}

        {!linkInvalid && !otherFailure && (
          <p role="status" className="auth-subtitle">
            Confirming your email…
          </p>
        )}

        <p className="auth-footer">
          <Link to="/login">Back to sign in</Link>
        </p>
      </section>
    </div>
  );
}
