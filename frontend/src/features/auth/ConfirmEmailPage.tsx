import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
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
  email?: string | undefined;
  /** The button and the form's name; the same request mails a set-password link when that is needed. */
  action?: string;
  /** Shown once sent; it never says whether the address has an account. */
  sentMessage?: string;
}

/** "Resend confirmation email" with its sending, sent and refused states. */
export function ResendConfirmation({
  email,
  action = 'Resend confirmation email',
  sentMessage = 'If an account needs confirming, we sent a new link',
}: ResendConfirmationProps) {
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
    <form
      onSubmit={onSubmit}
      noValidate
      aria-label={action === 'Resend confirmation email' ? 'Resend confirmation' : action}
      className="auth-form"
    >
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
        {resend.isPending ? 'Sending…' : action}
      </button>
      {resend.isSuccess && (
        <p role="status" className="auth-subtitle">
          {sentMessage}
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

/**
 * Opened from the emailed link: spends `?token=` once and signs the member in, then asks for a
 * password when the account has none (confirming drops the one chosen at sign-up), else goes home.
 */
export function ConfirmEmailPage() {
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token') ?? '';
  const navigate = useNavigate();
  const confirm = useConfirmEmailMutation();
  const { mutate } = confirm;
  // A link spends on first use, so StrictMode's second effect run must not send it again.
  const sent = useRef(false);

  const spend = useCallback(
    () =>
      mutate(token, {
        onSuccess: ({ passwordSetupRequired, passwordSetupToken }) =>
          passwordSetupRequired
            ? navigate('/set-password', { replace: true, state: { passwordSetupToken } })
            : navigate('/', { replace: true }),
      }),
    [token, mutate, navigate],
  );

  useEffect(() => {
    if (!token || sent.current) {
      return;
    }
    sent.current = true;
    spend();
  }, [token, spend]);

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
            <button type="button" disabled={confirm.isPending} onClick={spend}>
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
